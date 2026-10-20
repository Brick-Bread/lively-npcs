// What the editor does with the server: save, make, copy, delete, move, add skins, play, and the
// live preview in game. Every answer from the server goes back into the store.

import {api, apiUpload, ApiError, stopPreviewOnExit} from './api.js';
import * as St from './store.js';
import {S, emit} from './store.js';
import * as A from './anim.js';
import {toast, confirmDialog, promptDialog, debounce} from './ui.js';

const ID = /^[a-z0-9_-]{1,40}$/;

/** Counts writes, so a /api/state answer that started before one is not trusted. */
export let writes = 0;

function failed(e, what) {
  if (e instanceof ApiError && e.status === 401) return;
  toast(`${what}: ${e.message}`, 'error');
}

function idProblem(id, taken, kind) {
  if (!id) return 'Give it an id.';
  if (!ID.test(id)) return 'Use only a-z, 0-9, _ and - (at most 40).';
  if (taken.has(id)) return `There is already ${kind} called ${id}.`;
  return '';
}

/** "Mob Shop 2!" -> "mob-shop-2". */
export function slug(text) {
  return String(text).toLowerCase().normalize('NFKD').replace(/[^a-z0-9_ -]/g, '').trim().replace(/[\s]+/g, '-').slice(0, 40);
}

function freeId(base, taken) {
  base = (slug(base) || 'new').slice(0, 34);
  if (!taken.has(base)) return base;
  for (let i = 2; ; i++) if (!taken.has(base + '-' + i)) return base + '-' + i;
}

// ---- saving ----

export async function saveCurrent() {
  if (S.mode === 'npcs') return S.npcId ? saveNpc(S.npcId) : false;
  return S.animId ? saveAnim(S.animId) : false;
}

export async function saveAnim(id) {
  const e = S.anims.get(id);
  if (!e) return false;
  writes++;
  try {
    const stored = await api('PUT', 'animations/' + encodeURIComponent(id), A.toMap(e.draft, S.channelKeys));
    writes++;
    St.saved('anim', stored);
    toast(`Saved ${stored.name || id}.`, 'ok', 2200);
    return true;
  } catch (err) {
    failed(err, `Could not save ${id}`);
    return false;
  }
}

export async function saveNpc(id) {
  const e = S.npcs.get(id);
  if (!e) return false;
  writes++;
  try {
    const stored = await api('PUT', 'npcs/' + encodeURIComponent(id), St.cleanNpc(e.draft));
    writes++;
    St.saved('npc', stored);
    toast(`Saved ${id}.`, 'ok', 2200);
    return true;
  } catch (err) {
    failed(err, `Could not save ${id}`);
    return false;
  }
}

// ---- NPCs ----

export async function newNpc() {
  const id = await promptDialog({
    title: 'New NPC', label: 'Id', mono: true, confirm: 'Make it',
    hint: 'It appears where you stand in game, facing your way. a-z, 0-9, _ and -.',
    validate: v => idProblem(v, new Set(S.npcs.keys()), 'an NPC'),
  });
  if (!id) return;
  writes++;
  try {
    const made = await api('POST', 'npcs', {id});
    writes++;
    St.saved('npc', made);
    St.selectNpc(made.id);
    toast(`${made.id} is standing where you are.`, 'ok');
  } catch (err) {
    failed(err, 'Could not make the NPC');
  }
}

export async function duplicateNpc(from) {
  const id = await promptDialog({
    title: `Duplicate ${from}`, label: 'Id of the copy', mono: true, confirm: 'Duplicate', value: freeId(from + '-copy', new Set(S.npcs.keys())),
    hint: 'The copy stands on the same spot; use "Move to me" to put it elsewhere.',
    validate: v => idProblem(v, new Set(S.npcs.keys()), 'an NPC'),
  });
  if (!id) return;
  writes++;
  try {
    const made = await api('POST', 'npcs', {id, copyFrom: from});
    writes++;
    St.saved('npc', made);
    St.selectNpc(made.id);
    toast(`Made ${made.id}, a copy of ${from}.`, 'ok');
  } catch (err) {
    failed(err, 'Could not duplicate');
  }
}

export async function deleteNpc(id) {
  const e = S.npcs.get(id);
  if (!e) return;
  const ok = await confirmDialog({
    title: `Delete ${id}?`, danger: true, confirm: 'Delete',
    message: `${id} disappears for everyone, and its settings are gone for good.`,
  });
  if (!ok) return;
  writes++;
  try {
    await api('DELETE', 'npcs/' + encodeURIComponent(id));
    writes++;
    St.removeEntry('npc', id);
    toast(`Deleted ${id}.`, 'ok', 2200);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      St.removeEntry('npc', id);
      return;
    }
    failed(err, `Could not delete ${id}`);
  }
}

export async function moveNpcToMe(id) {
  writes++;
  try {
    const moved = await api('POST', 'npcs/' + encodeURIComponent(id) + '/move-to-me');
    writes++;
    // The server has the new spot; any other unsaved change stays in the draft.
    St.rebaseNpc(moved, ['world', 'x', 'y', 'z', 'yaw']);
    toast(`${id} is now where you stand.`, 'ok');
  } catch (err) {
    failed(err, `Could not move ${id}`);
  }
}

// ---- animations ----

export async function newAnim() {
  const name = await promptDialog({
    title: 'New animation', label: 'Name', confirm: 'Make it', placeholder: 'Happy dance',
    hint: 'Its id (for npcs.yml and commands) is made from the name.',
    validate: v => {
      if (!v.trim()) return 'Give it a name.';
      const id = slug(v);
      if (!id) return 'Use some letters or numbers.';
      return S.anims.has(id) ? `There is already an animation with the id ${id}.` : '';
    },
  });
  if (!name) return;
  const id = slug(name);
  const draft = A.normalize({id, name: name.trim(), length: 40, loop: false, glance: false,
    keyframes: [{at: 0, ease: 'linear', values: {}}, {at: 40, ease: 'ease-in-out', values: {}}]});
  St.addDraft('anim', draft);
  St.selectAnim(id);
  toast('Pose it at a tick, and a keyframe appears there. Save when you like it.', 'info', 6000);
}

export async function duplicateAnim(from) {
  const src = S.anims.get(from);
  if (!src) return;
  const taken = new Set(S.anims.keys());
  const id = await promptDialog({
    title: `Duplicate ${src.draft.name}`, label: 'Id of the copy', mono: true, confirm: 'Duplicate', value: freeId(from + '-copy', taken),
    validate: v => idProblem(v, taken, 'an animation'),
  });
  if (!id) return;
  const copy = A.clone(src.draft);
  copy.id = id;
  copy.name = src.draft.name + ' (copy)';
  copy.builtin = false;
  copy.edited = false;
  St.addDraft('anim', copy);
  St.selectAnim(id);
}

/** Deletes an animation of the server's own, restores an edited preset, or drops an unsaved one. */
export async function deleteAnim(id) {
  const e = S.anims.get(id);
  if (!e) return;
  if (e.isNew) {
    if (await confirmDialog({title: `Discard ${e.draft.name}?`, message: 'It was never saved.', confirm: 'Discard', danger: true})) {
      St.removeEntry('anim', id);
    }
    return;
  }
  const preset = e.base && e.base.builtin;
  if (preset && !(e.base && e.base.edited)) {
    toast('This preset is as it ships. Edit it, or duplicate it to make your own.', 'info');
    return;
  }
  const ok = await confirmDialog(preset
    ? {title: `Restore ${e.draft.name}?`, confirm: 'Restore', danger: true, message: 'Your saved version goes, and the preset comes back as Lively NPCs ships it.'}
    : {title: `Delete ${e.draft.name}?`, confirm: 'Delete', danger: true, message: 'NPCs that use it stop playing it. This cannot be undone.'});
  if (!ok) return;
  writes++;
  try {
    const res = await api('DELETE', 'animations/' + encodeURIComponent(id));
    writes++;
    if (res && res.animation) {
      e.dirty = false;
      St.saved('anim', res.animation);
      toast(`${e.draft.name} is back as it ships.`, 'ok');
    } else {
      St.removeEntry('anim', id);
      toast(`Deleted ${id}.`, 'ok', 2200);
    }
    emit('select');
  } catch (err) {
    failed(err, `Could not delete ${id}`);
  }
}

/** Plays the saved animation on the model NPC for everyone near it. */
export async function playForEveryone() {
  const e = St.animEntry();
  if (!e || !S.modelNpcId) return;
  if (e.isNew) {
    toast('Save it first: players can only see saved animations.', 'warn');
    return;
  }
  try {
    await api('POST', 'play', {npc: S.modelNpcId, animation: e.id, everyone: true});
    toast(e.dirty ? `Playing the saved ${e.draft.name} on ${S.modelNpcId} for everyone (your unsaved changes are not in it).`
      : `Playing ${e.draft.name} on ${S.modelNpcId} for everyone.`, 'ok');
  } catch (err) {
    failed(err, 'Could not play it');
  }
}

// ---- skins ----

/** Adds a skin (player name, image link or uploaded file as base64); resolves with {key, slim, source}. */
export async function addSkin(request, onProgress) {
  writes++;
  const skin = request.type === 'upload'
    ? await apiUpload('skins', request, onProgress)
    : await api('POST', 'skins', request);
  writes++;
  if (!S.skins.some(s => s.key === skin.key)) S.skins = [...S.skins, skin];
  emit('server');
  return skin;
}

// ---- live preview in game ----

export const live = {status: 'off', message: ''};

function setLiveStatus(status, message = '') {
  live.status = status;
  live.message = message;
  emit('live');
}

const sendPreview = debounce(async () => {
  const e = St.animEntry();
  if (!S.live || !e || !S.modelNpcId) return;
  try {
    await api('POST', 'preview', {npc: S.modelNpcId, animation: {id: e.id, ...A.toMap(e.draft, S.channelKeys)}});
    setLiveStatus('on', `Playing on ${S.modelNpcId} for you`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) setLiveStatus('warn', err.message);
    else if (!(err instanceof ApiError && err.status === 401)) setLiveStatus('warn', err.message);
  }
}, 300);

let previewNpc = null;

export function setLive(onOff) {
  S.live = onOff;
  if (onOff) {
    setLiveStatus('pending', 'Starting…');
    previewNpc = S.modelNpcId;
    sendPreview.flush();
  } else {
    sendPreview.cancel();
    previewNpc = null;
    setLiveStatus('off');
    api('POST', 'preview/stop', {}).catch(() => {});
  }
}

/** Sends the draft again (debounced) after any change, while live preview is on. */
export function previewChanged() {
  if (!S.live) return;
  if (previewNpc && previewNpc !== S.modelNpcId) {
    api('POST', 'preview/stop', {}).catch(() => {});
  }
  previewNpc = S.modelNpcId;
  sendPreview();
}

St.on('anim', () => previewChanged());
St.on('select', () => previewChanged());
window.addEventListener('pagehide', () => {
  if (S.live) stopPreviewOnExit();
});
