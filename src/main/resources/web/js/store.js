// The editor's state: what the server has, the drafts being edited (with undo), what is selected,
// the playhead. Components listen for events and redraw; every change goes through here.

import * as A from './anim.js';

const listeners = new Map();

/** Listens for an event: 'server', 'lists', 'select', 'anim', 'npc', 'tick', 'play', 'part', 'live', 'view'. */
export function on(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(fn);
  return () => listeners.get(event).delete(fn);
}

export function emit(event, data) {
  for (const fn of listeners.get(event) || []) {
    try {
      fn(data);
    } catch (e) {
      console.error('Lively NPCs editor: a "' + event + '" listener failed', e);
    }
  }
}

export const S = {
  player: {name: '', uuid: '', online: false, world: ''},
  channels: [],
  channelKeys: [...A.DEFAULT_CHANNELS],
  channelInfo: {},
  easings: [...A.EASINGS],
  items: [],
  itemSet: new Set(),
  skins: [],
  settings: {scale: 0.9375, keyTicks: 2},
  expiresAt: 0,

  /** id -> {id, base, draft, isNew, dirty, remote ('changed' | 'deleted' | null), undo, redo} */
  npcs: new Map(),
  anims: new Map(),

  mode: 'animations',
  npcId: null,
  animId: null,
  /** The NPC whose skin the animation is shown on (and live-previewed on). */
  modelNpcId: null,

  tick: 0,
  playing: false,
  /** The tick of the selected keyframe, or null. */
  selectedAt: null,
  /** The selected part ('root', 'head', 'body', 'right_arm', ...) or null for all. */
  part: null,
  /** A pose changed between keyframes while auto key is off: shown, not yet stored. */
  pending: null,
  autoKey: true,
  layers: true,
  live: false,
  clipboard: null,
};

// ---- loading and merging what the server sends ----

const NPC_FIELDS = ['id', 'entity', 'variant', 'world', 'x', 'y', 'z', 'yaw', 'skin', 'plate', 'icon', 'held', 'click', 'idle',
  'greet', 'clicked', 'beckon', 'look'];

/** An NPC with exactly NpcDef's fields, copied. */
export function cleanNpc(n) {
  const out = {};
  for (const f of NPC_FIELDS) out[f] = Array.isArray(n[f]) ? [...n[f]] : n[f];
  return out;
}

function npcText(n) {
  return n ? JSON.stringify(cleanNpc(n)) : '';
}

function animText(a) {
  return a ? A.canonical(a, S.channelKeys) : '';
}

function refreshDirty(entry, kind) {
  const text = kind === 'npc' ? npcText : animText;
  entry.dirty = entry.isNew || text(entry.draft) !== text(entry.base);
}

/** Applies a /api/state answer. The first one fills everything; later ones keep unsaved drafts. */
export function applyState(data, first) {
  S.player = data.player || S.player;
  S.channels = data.channels || [];
  if (S.channels.length) S.channelKeys = S.channels.map(c => c.key);
  S.channelInfo = Object.fromEntries(S.channels.map(c => [c.key, c]));
  S.easings = data.easings && data.easings.length ? data.easings : A.EASINGS;
  S.items = data.items || [];
  S.itemSet = new Set(S.items);
  S.skins = data.skins || [];
  S.settings = data.settings || S.settings;
  if (data.session && typeof data.session.expiresIn === 'number') S.expiresAt = Date.now() + data.session.expiresIn;

  let listsChanged = first;
  listsChanged = mergeEntries(S.npcs, (data.npcs || []).map(cleanNpc), 'npc', n => n) || listsChanged;
  listsChanged = mergeEntries(S.anims, data.animations || [], 'anim', A.fromServer) || listsChanged;

  if (first) {
    const people = [...S.npcs.values()].filter(e => e.draft.entity === 'player');
    S.modelNpcId = people.length ? people[0].id : (S.npcs.size ? S.npcs.keys().next().value : null);
    S.npcId = S.npcs.size ? S.npcs.keys().next().value : null;
    S.animId = S.anims.has('wave') ? 'wave' : (S.anims.size ? S.anims.keys().next().value : null);
  }
  if (S.modelNpcId && !S.npcs.has(S.modelNpcId)) S.modelNpcId = S.npcs.size ? S.npcs.keys().next().value : null;
  if (S.npcId && !S.npcs.has(S.npcId)) S.npcId = null;
  if (S.animId && !S.anims.has(S.animId)) S.animId = null;

  emit('server');
  if (listsChanged) {
    emit('lists');
    emit('select');
  }
}

/** Merges the server's list into the drafts; true when something visible changed. */
function mergeEntries(map, list, kind, convert) {
  const text = kind === 'npc' ? npcText : animText;
  let changed = false;
  const seen = new Set();
  for (const raw of list) {
    const value = convert(raw);
    seen.add(value.id);
    const entry = map.get(value.id);
    if (!entry) {
      map.set(value.id, {id: value.id, base: value, draft: kind === 'npc' ? cleanNpc(value) : A.clone(value), isNew: false, dirty: false, remote: null, undo: [], redo: []});
      changed = true;
      continue;
    }
    const sameAsBase = text(entry.base) === text(value) && (kind === 'npc' || (entry.base && entry.base.builtin === value.builtin && entry.base.edited === value.edited));
    if (sameAsBase && !entry.isNew) {
      if (entry.remote) {
        entry.remote = null;
        changed = true;
      }
      continue;
    }
    if (!entry.dirty) {
      entry.base = value;
      entry.draft = kind === 'npc' ? cleanNpc(value) : A.clone(value);
      entry.isNew = false;
      entry.remote = null;
      changed = true;
      emit(kind, {id: value.id, remote: true});
    } else if (entry.isNew) {
      // Someone made one with this id in game while we were drafting ours: ours would replace it.
      entry.remote = 'exists';
      entry.base = value;
      entry.isNew = false;
      refreshDirty(entry, kind);
      changed = true;
    } else if (entry.remote !== 'changed' || text(entry.remoteValue) !== text(value)) {
      entry.remote = 'changed';
      entry.remoteValue = value;
      changed = true;
    }
  }
  for (const [id, entry] of map) {
    if (seen.has(id) || entry.isNew) continue;
    if (!entry.dirty) {
      map.delete(id);
      changed = true;
    } else if (entry.remote !== 'deleted') {
      entry.remote = 'deleted';
      changed = true;
    }
  }
  if (changed) sortMap(map);
  return changed;
}

function sortMap(map) {
  const entries = [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  map.clear();
  for (const [k, v] of entries) map.set(k, v);
}

/** After a save: the server's version becomes both the base and the draft. */
export function saved(kind, value, keepDraft = false) {
  const map = kind === 'npc' ? S.npcs : S.anims;
  const v = kind === 'npc' ? cleanNpc(value) : A.fromServer(value);
  let entry = map.get(v.id);
  if (!entry) {
    entry = {id: v.id, undo: [], redo: []};
    map.set(v.id, entry);
    sortMap(map);
  }
  entry.base = v;
  if (!keepDraft || !entry.draft) entry.draft = kind === 'npc' ? cleanNpc(v) : A.clone(v);
  entry.isNew = false;
  entry.remote = null;
  entry.remoteValue = null;
  refreshDirty(entry, kind);
  emit(kind, {id: v.id, external: true});
  emit('lists');
}

/** The server changed some fields of an NPC (moved it): they go into the base and the draft; other unsaved changes stay. */
export function rebaseNpc(value, fields) {
  const v = cleanNpc(value);
  const entry = S.npcs.get(v.id);
  if (!entry) {
    saved('npc', value);
    return;
  }
  entry.base = v;
  for (const f of fields) entry.draft[f] = v[f];
  entry.isNew = false;
  entry.remote = null;
  entry.remoteValue = null;
  refreshDirty(entry, 'npc');
  emit('npc', {id: v.id, external: true});
  emit('lists');
}

/** Adds a new, unsaved draft and returns its entry. */
export function addDraft(kind, value) {
  const map = kind === 'npc' ? S.npcs : S.anims;
  const entry = {id: value.id, base: null, draft: value, isNew: true, dirty: true, remote: null, undo: [], redo: []};
  map.set(value.id, entry);
  sortMap(map);
  emit('lists');
  return entry;
}

export function removeEntry(kind, id) {
  const map = kind === 'npc' ? S.npcs : S.anims;
  map.delete(id);
  if (kind === 'npc') {
    if (S.npcId === id) S.npcId = null;
    if (S.modelNpcId === id) S.modelNpcId = firstPerson();
  } else if (S.animId === id) {
    S.animId = null;
  }
  emit('lists');
  emit('select');
}

/** Takes the version in game instead of the unsaved draft. */
export function takeRemote(kind, id) {
  const map = kind === 'npc' ? S.npcs : S.anims;
  const entry = map.get(id);
  if (!entry) return;
  if (entry.remote === 'deleted') {
    removeEntry(kind, id);
    return;
  }
  const value = entry.remoteValue || entry.base;
  entry.base = value;
  entry.draft = kind === 'npc' ? cleanNpc(value) : A.clone(value);
  entry.remote = null;
  entry.remoteValue = null;
  refreshDirty(entry, kind);
  emit(kind, {id, external: true});
  emit('lists');
}

export function firstPerson() {
  for (const e of S.npcs.values()) if (e.draft.entity === 'player') return e.id;
  return S.npcs.size ? S.npcs.keys().next().value : null;
}

// ---- selection ----

export function animEntry() {
  return S.animId ? S.anims.get(S.animId) || null : null;
}

export function npcEntry() {
  return S.npcId ? S.npcs.get(S.npcId) || null : null;
}

/** The NPC drawn in the 3D view: the selected one with NPCs open, else the one animations are shown on. */
export function shownNpc() {
  const id = S.mode === 'npcs' ? S.npcId : S.modelNpcId;
  const e = id ? S.npcs.get(id) : null;
  return e ? e.draft : null;
}

export function setMode(mode) {
  if (S.mode === mode) return;
  pause();
  S.mode = mode;
  S.pending = null;
  emit('select');
  emit('view');
}

export function selectAnim(id) {
  pause();
  S.animId = id;
  S.pending = null;
  S.selectedAt = null;
  const e = animEntry();
  S.tick = 0;
  if (e) S.selectedAt = 0;
  emit('select');
  emit('tick');
}

export function selectNpc(id) {
  S.npcId = id;
  const e = S.npcs.get(id);
  if (e && e.draft.entity === 'player') S.modelNpcId = id;
  emit('select');
  emit('view');
}

export function setModelNpc(id) {
  S.modelNpcId = id;
  emit('view');
  emit('live');
}

export function selectPart(part) {
  S.part = part;
  emit('part');
}

// ---- history (undo / redo) ----

const COALESCE_MS = 700;

function snapshot(kind, entry) {
  return JSON.stringify(entry.draft);
}

function restore(kind, entry, text) {
  entry.draft = JSON.parse(text);
}

/** Records the draft before a change; changes with the same gesture close together are one step. */
function remember(kind, entry, gesture) {
  const now = performance.now();
  if (gesture && entry.gesture === gesture && now - entry.gestureAt < COALESCE_MS) {
    entry.gestureAt = now;
    return;
  }
  entry.undo.push(snapshot(kind, entry));
  if (entry.undo.length > 200) entry.undo.shift();
  entry.redo.length = 0;
  entry.gesture = gesture || null;
  entry.gestureAt = now;
}

/** Ends a gesture so the next change starts a new undo step (a slider let go, say). */
export function endGesture() {
  for (const e of [animEntry(), npcEntry()]) if (e) e.gesture = null;
}

function historyEntry() {
  return S.mode === 'npcs' ? ['npc', npcEntry()] : ['anim', animEntry()];
}

export function canUndo() {
  const [, e] = historyEntry();
  return !!(e && e.undo.length);
}

export function canRedo() {
  const [, e] = historyEntry();
  return !!(e && e.redo.length);
}

export function undo() {
  const [kind, e] = historyEntry();
  if (!e || !e.undo.length) return false;
  e.redo.push(snapshot(kind, e));
  restore(kind, e, e.undo.pop());
  e.gesture = null;
  afterChange(kind, e, true);
  return true;
}

export function redo() {
  const [kind, e] = historyEntry();
  if (!e || !e.redo.length) return false;
  e.undo.push(snapshot(kind, e));
  restore(kind, e, e.redo.pop());
  e.gesture = null;
  afterChange(kind, e, true);
  return true;
}

function afterChange(kind, entry, history = false) {
  if (kind === 'anim') {
    A.normalize(entry.draft);
    if (S.selectedAt !== null && A.keyAt(entry.draft, S.selectedAt) < 0) S.selectedAt = null;
    S.pending = null;
  }
  const wasDirty = entry.dirty;
  refreshDirty(entry, kind);
  emit(kind, {id: entry.id, history});
  if (wasDirty !== entry.dirty) emit('lists');
}

/** Changes the selected NPC's draft: fn(draft) edits it in place. */
export function changeNpc(fn, gesture) {
  const e = npcEntry();
  if (!e) return;
  remember('npc', e, gesture);
  fn(e.draft);
  afterChange('npc', e);
}

/** Changes the selected animation's draft: fn(draft) edits it in place. */
export function changeAnim(fn, gesture) {
  const e = animEntry();
  if (!e) return;
  remember('anim', e, gesture);
  fn(e.draft);
  afterChange('anim', e);
}

// ---- the pose at the playhead, and editing it ----

/** Every channel's value now: the pose waiting to be keyed, else the animation at the playhead. */
export function currentValues() {
  const e = animEntry();
  if (!e) return {};
  if (S.pending) return fullPose(S.pending);
  return A.sample(e.draft, S.tick, S.channelKeys);
}

function fullPose(values) {
  const out = {};
  for (const k of S.channelKeys) out[k] = values[k] || 0;
  return out;
}

/** The whole tick under the playhead (it rests on whole ticks unless playing). */
export function wholeTick() {
  return Math.max(0, Math.round(S.tick));
}

/** The keyframe under the playhead, or -1. */
export function keyUnderPlayhead() {
  const e = animEntry();
  if (!e || S.tick !== Math.round(S.tick)) return -1;
  return A.keyAt(e.draft, S.tick);
}

/**
 * Sets channels of the pose at the playhead: on a keyframe it changes that keyframe; between
 * keyframes it adds one there (auto key) or holds the pose until "Add keyframe".
 */
export function setValues(values, gesture) {
  const e = animEntry();
  if (!e) return;
  pause();
  S.tick = wholeTick();
  const i = A.keyAt(e.draft, S.tick);
  if (i >= 0) {
    changeAnim(d => {
      const k = d.keyframes[i];
      for (const [key, v] of Object.entries(values)) {
        if (v) k.values[key] = v;
        else delete k.values[key];
      }
    }, gesture);
    S.selectedAt = S.tick;
  } else if (S.autoKey) {
    const pose = A.compact({...A.sample(e.draft, S.tick, S.channelKeys), ...values});
    const at = S.tick;
    changeAnim(d => {
      d.keyframes.push({at, ease: 'linear', values: pose});
      if (at > d.length) d.length = at;
    }, gesture);
    S.selectedAt = at;
    emit('lists');
  } else {
    S.pending = A.compact({...currentValues(), ...values});
    emit('anim', {id: e.id, pending: true});
  }
  emit('tick');
}

export function setValue(key, value, gesture) {
  setValues({[key]: value}, gesture);
}

/** Replaces the whole pose at the playhead (paste, mirror). */
export function setPose(values, gesture) {
  const full = {};
  for (const k of S.channelKeys) full[k] = values[k] || 0;
  setValues(full, gesture);
}

/** Stores the pose at the playhead as a keyframe (or selects the one already there). */
export function addKeyframe() {
  const e = animEntry();
  if (!e) return null;
  pause();
  const at = wholeTick();
  const i = A.keyAt(e.draft, at);
  if (i >= 0) {
    if (S.pending) {
      const pose = S.pending;
      changeAnim(d => { d.keyframes[i].values = {...pose}; });
      S.pending = null;
    }
    S.selectedAt = at;
    S.tick = at;
    emit('tick');
    return 'exists';
  }
  const pose = A.compact(currentValues());
  changeAnim(d => {
    d.keyframes.push({at, ease: 'linear', values: pose});
    if (at > d.length) d.length = at;
  });
  S.pending = null;
  S.selectedAt = at;
  S.tick = at;
  emit('tick');
  return 'added';
}

/** Removes the selected keyframe (never the one at 0). */
export function deleteKeyframe() {
  const e = animEntry();
  if (!e || S.selectedAt === null) return 'none';
  if (S.selectedAt === 0) return 'first';
  const at = S.selectedAt;
  changeAnim(d => { d.keyframes = d.keyframes.filter(k => k.at !== at); });
  S.selectedAt = null;
  emit('tick');
  return 'deleted';
}

/** Moves a keyframe to another tick (not the first, not onto another). Returns the tick it is at. */
export function moveKeyframe(from, to, gesture) {
  const e = animEntry();
  if (!e || from === 0) return from;
  to = Math.max(1, Math.round(to));
  if (to === from) return from;
  if (A.keyAt(e.draft, to) >= 0) return from;
  changeAnim(d => {
    const k = d.keyframes.find(x => x.at === from);
    if (k) k.at = to;
    if (to > d.length) d.length = to;
  }, gesture);
  if (S.selectedAt === from) S.selectedAt = to;
  return to;
}

export function setEase(ease) {
  const e = animEntry();
  if (!e || S.selectedAt === null) return;
  const at = S.selectedAt;
  changeAnim(d => {
    const k = d.keyframes.find(x => x.at === at);
    if (k) k.ease = ease;
  });
}

// ---- playhead and playing ----

export function setTick(t, keepPending = false) {
  const e = animEntry();
  const max = e ? e.draft.length : 0;
  S.tick = Math.max(0, Math.min(max, t));
  if (!keepPending) S.pending = null;
  if (!S.playing && e) {
    const i = A.keyAt(e.draft, S.tick);
    if (i >= 0) S.selectedAt = S.tick;
  }
  emit('tick');
}

export function play() {
  const e = animEntry();
  if (!e || S.playing) return;
  S.pending = null;
  if (!e.draft.loop && S.tick >= e.draft.length) S.tick = 0;
  S.playing = true;
  emit('play');
}

export function pause() {
  if (!S.playing) return;
  S.playing = false;
  S.tick = Math.round(S.tick);
  emit('play');
  emit('tick');
}

export function togglePlay() {
  if (S.playing) pause();
  else play();
}

/** Moves the playhead on while playing (dt in seconds); stops at the end of a one-shot animation. */
export function advance(dt) {
  const e = animEntry();
  if (!S.playing || !e) return;
  const len = e.draft.length;
  let t = S.tick + dt * A.TICKS_PER_SECOND;
  if (e.draft.loop) {
    t = t % len;
  } else if (t >= len) {
    t = len;
    S.tick = t;
    pause();
    return;
  }
  S.tick = t;
  emit('tick');
}
