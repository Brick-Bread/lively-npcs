// The editor page: logs in with the link's token, loads /api/state, puts the parts together (sidebar,
// 3D view, inspector, timeline), runs the frame loop, the shortcuts and the 5-second refresh.

import {api, ApiError, takeTokenFromUrl, extractToken, setToken, hasToken, setUnauthorizedHandler} from './api.js';
import * as St from './store.js';
import {S, on, emit} from './store.js';
import * as Act from './actions.js';
import {Viewport} from './viewport.js';
import {mountSidebar} from './sidebar.js';
import {mountTimeline} from './timeline.js';
import {mountAnimInspector} from './inspector-anim.js';
import {mountNpcInspector} from './inspector-npc.js';
import {loadSkin} from './skins.js';
import {render as renderMini} from './minimessage.js';
import {PART_NAMES} from './labels.js';
import {h, fill, icon, button, toggle, toast, dialog, duration, isTyping} from './ui.js';
import {mountGizmo} from './gizmo.js';

const gate = document.getElementById('gate');
const gateBody = document.getElementById('gate-body');
const app = document.getElementById('app');
let started = false;
let expired = false;

// ---- the gate: loading, no link, expired ----

function showGate(kind, detail = '') {
  gate.hidden = false;
  if (kind === 'loading') {
    fill(gateBody, h('div', {class: 'spinner'}), h('p', {text: 'Opening the editor…'}));
    return;
  }
  if (kind === 'offline') {
    fill(gateBody, h('h1', {text: 'The server did not answer'}),
      h('p', {text: detail || 'Lively NPCs\' web server could not be reached. Is the Minecraft server running?'}),
      h('div', {class: 'gate-row'}, button('Try again', 'restore', {class: 'btn-primary', onClick: () => start()})));
    return;
  }
  const input = h('input', {class: 'input', placeholder: 'Paste the new link here', attrs: {spellcheck: 'false', autocomplete: 'off', 'aria-label': 'New editor link'}});
  const error = h('div', {class: 'error-text'});
  const go = () => {
    const t = extractToken(input.value);
    if (!t) {
      error.textContent = 'That is not an editor link. It looks like http://…/editor/#…';
      return;
    }
    setToken(t);
    expired = false;
    if (started) {
      gate.hidden = true;
      refresh(true);
    } else {
      start();
    }
  };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
  const intro = kind === 'none'
    ? [h('h1', {text: 'Open the editor from the game'}), h('p', {}, 'In Minecraft, run ', h('span', {class: 'cmd', text: '/npc editor'}), ' and click the link it gives you. The link is yours alone and logs you in.')]
    : [h('h1', {text: 'This editor link has expired'}), h('p', {}, 'Links last a while, then stop working. Run ', h('span', {class: 'cmd', text: '/npc editor'}), ' again in game for a new one.'),
      started ? h('p', {class: 'small', text: 'Your unsaved changes are still on this page: paste the new link below to carry on where you were.'}) : null];
  fill(gateBody, ...intro, h('div', {class: 'gate-row'}, input, button('Continue', null, {class: 'btn-primary', onClick: go})), error);
  setTimeout(() => input.focus(), 50);
}

setUnauthorizedHandler(() => {
  if (expired) return;
  expired = true;
  S.live = false;
  showGate('expired');
});

window.addEventListener('hashchange', () => {
  const t = extractToken(location.hash.slice(1));
  if (!t) return;
  setToken(t);
  history.replaceState(null, '', location.pathname + location.search);
  expired = false;
  if (started) {
    gate.hidden = true;
    refresh(true);
  } else {
    start();
  }
});

// ---- start ----

async function start() {
  takeTokenFromUrl();
  if (!hasToken()) {
    showGate('none');
    return;
  }
  showGate('loading');
  let data;
  try {
    data = await api('GET', 'state');
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return;
    showGate('offline', e.message);
    return;
  }
  St.applyState(data, !started);
  if (!started) {
    started = true;
    mount();
  }
  gate.hidden = true;
  app.hidden = false;
  emit('select');
  emit('view');
}

// ---- refreshing every 5 seconds ----

let refreshing = false;
async function refresh(force = false) {
  if (!started || expired || refreshing) return;
  if (!force && document.hidden) return;
  refreshing = true;
  const writesBefore = Act.writes;
  try {
    const data = await api('GET', 'state');
    // A save finished while this was on its way: its answer is older than what we have.
    if (Act.writes === writesBefore) St.applyState(data, false);
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) connection(false, e.message);
    refreshing = false;
    return;
  }
  connection(true);
  refreshing = false;
}

let connected = true;
function connection(ok, message) {
  if (ok === connected) return;
  connected = ok;
  if (!ok) toast('Lost the connection to the server: ' + message + ' Retrying…', 'warn', 6000);
  else toast('Connected again.', 'ok', 2000);
}

// ---- the page ----

function mount() {
  mountSidebar(document.getElementById('sidebar'));
  mountTimeline(document.getElementById('timeline'));
  const inspector = document.getElementById('inspector');
  const animBox = h('div');
  const npcBox = h('div');
  inspector.append(animBox, npcBox);
  mountAnimInspector(animBox);
  mountNpcInspector(npcBox);

  const viewport = new Viewport(document.getElementById('viewport'), {
    onPick: part => { if (S.mode === 'animations') St.selectPart(part); },
    partName: part => PART_NAMES[part] || part,
  });
  mountGizmo(viewport);
  window.__editor = {S, viewport};

  mountTopbar();
  mountStageBars(viewport);
  mountPlate(viewport);

  const layout = () => {
    app.classList.toggle('mode-npcs', S.mode === 'npcs');
    viewport.picking = S.mode === 'animations';
    animBox.hidden = S.mode !== 'animations';
    npcBox.hidden = S.mode !== 'npcs';
  };
  on('select', layout);
  layout();

  // The skin on the model, the slim arms, the held item.
  let shownKey = null;
  const showSkin = () => {
    const n = St.shownNpc();
    const key = n && n.entity === 'player' ? n.skin : '';
    const slim = !!(key && (S.skins.find(s => s.key === key) || {}).slim);
    viewport.setMuted(!!n && n.entity !== 'player');
    viewport.setHeldVisible(!!(n && n.held && n.entity === 'player'));
    const want = key + '|' + slim;
    if (want === shownKey) return;
    shownKey = want;
    if (!key) {
      viewport.setSkin(null, false);
      return;
    }
    loadSkin(key).then(s => {
      if (shownKey === want) viewport.setSkin(s ? s.img : null, slim);
    }).catch(err => {
      if (shownKey === want) viewport.setSkin(null, slim);
      toast(`The skin ${key} could not be shown: ${err.message}`, 'warn');
    });
  };
  on('view', showSkin);
  on('select', showSkin);
  on('npc', showSkin);
  on('server', showSkin);
  // Parts are picked and lit only while posing an animation.
  const light = () => viewport.highlight(S.mode === 'animations' ? S.part : null);
  on('part', light);
  on('select', light);

  // The frame loop: playing, the live layers, drawing.
  let last = performance.now();
  const frame = now => {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    St.advance(dt);
    const n = St.shownNpc();
    const values = S.mode === 'animations' ? St.currentValues() : {};
    const t = now / 1000;
    const layers = S.mode === 'npcs' || S.layers;
    const live = {
      slim: viewport.slim,
      breath: layers ? (1 - Math.cos(2 * Math.PI * t / 3)) / 2 : 0,
      shift: layers ? 0.9 * Math.sin(2 * Math.PI * t / 9.5) : 0,
    };
    viewport.setPose(values, live);
    viewport.render(now);
    requestAnimationFrame(frame);
    void n;
  };
  requestAnimationFrame(frame);

  bindKeys();
  setInterval(() => refresh(false), 5000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(true); });
  window.addEventListener('beforeunload', e => {
    const dirty = [...S.npcs.values(), ...S.anims.values()].some(x => x.dirty);
    if (dirty) {
      e.preventDefault();
      e.returnValue = '';
    }
  });
}

function mountTopbar() {
  const right = document.getElementById('topbar-right');
  const mid = document.getElementById('topbar-mid');
  const who = h('div', {class: 'who'});
  const timer = h('div', {class: 'timer', title: 'How long this editor link still works'});
  const help = button(null, 'keyboard', {class: 'btn-ghost', title: 'Keyboard shortcuts (?)', onClick: () => shortcuts()});
  // On a narrow window the inspector slides in over the view.
  const panel = button('Settings', 'dots', {class: 'btn-sm narrow-only', title: 'Show or hide the settings panel', onClick: () => app.classList.toggle('show-inspector')});
  fill(right, timer, who, panel, help);

  const drawWho = () => {
    const p = S.player;
    fill(who, h('div', {class: 'who-avatar', text: (p.name || '?').slice(0, 1).toUpperCase()}),
      h('div', {}, h('div', {class: 'who-name', text: p.name || 'Unknown player'}),
        h('div', {class: 'who-status'}, h('span', {class: ['dot', p.online ? 'dot-ok' : 'dot-off']}), p.online ? 'in game' + (p.world ? ' · ' + p.world : '') : 'not in game')));
  };
  const drawTimer = () => {
    if (!S.expiresAt) return;
    const left = S.expiresAt - Date.now();
    timer.classList.toggle('soon', left < 10 * 60_000);
    timer.classList.toggle('very-soon', left < 2 * 60_000);
    fill(timer, icon('clock', 14), left > 0 ? duration(left) + ' left' : 'expired');
    if (left <= 0 && !expired) {
      expired = true;
      showGate('expired');
    }
  };
  const drawMid = () => {
    const e = S.mode === 'npcs' ? St.npcEntry() : St.animEntry();
    const what = S.mode === 'npcs' ? 'NPC' : 'Animation';
    if (!e) {
      fill(mid);
      return;
    }
    const name = S.mode === 'npcs' ? e.draft.id : (e.draft.name || e.id);
    fill(mid, h('div', {class: 'row muted small'}, h('span', {text: what}), icon('next', 12), h('span', {style: {color: 'var(--text)', fontWeight: 600}, text: name}),
      e.dirty ? h('span', {class: 'badge badge-unsaved', text: e.isNew ? 'not saved yet' : 'unsaved'}) : null));
  };
  on('server', () => { drawWho(); drawTimer(); });
  on('select', drawMid);
  on('lists', drawMid);
  on('anim', drawMid);
  on('npc', drawMid);
  setInterval(drawTimer, 1000);
  drawWho();
  drawTimer();
  drawMid();
}

function mountStageBars(viewport) {
  const left = document.getElementById('stage-left');
  const right = document.getElementById('stage-right');
  const hint = document.getElementById('stage-hint');

  const views = h('div', {class: 'seg'}, ...[['front', 'Front'], ['side', 'Side'], ['back', 'Back'], ['three-quarter', '3/4']].map(([k, label]) =>
    h('button', {type: 'button', text: label, title: 'Look from the ' + (k === 'three-quarter' ? 'front, a little to the side' : k), on: {click: () => viewport.view(k)}})));
  const modelSel = h('select', {class: 'select', title: 'The NPC this animation is shown on (and live-previewed on)', on: {change: () => St.setModelNpc(modelSel.value)}});
  const modelGroup = h('div', {class: 'glass toolbar-group'}, h('span', {class: 'toolbar-label', text: 'Shown on'}), modelSel);
  const viewGroup = h('div', {class: 'glass toolbar-group'}, icon('camera', 14), views);
  fill(left, modelGroup, viewGroup);

  const liveToggle = toggle('Live preview in game', S.live, v => Act.setLive(v), 'Plays your draft on the NPC for you in game as you edit');
  const liveStatus = h('span', {class: 'live-status'});
  const playAll = button('Play for everyone', 'broadcast', {class: 'btn-sm', title: 'Plays the saved animation on this NPC for everyone near it',
    onClick: () => (Act.forEveryone.playing ? Act.stopForEveryone() : Act.playForEveryone())});
  const liveGroup = h('div', {class: 'glass toolbar-group'}, liveToggle.el, liveStatus, h('div', {class: 'sep'}), playAll);
  fill(right, liveGroup);

  let options = '';
  const draw = () => {
    const anim = S.mode === 'animations';
    modelGroup.hidden = !anim;
    liveGroup.hidden = !anim;
    const people = [...S.npcs.values()].filter(e => e.draft.entity === 'player');
    const list = people.length ? people : [...S.npcs.values()];
    // Only rebuild the list when it changed (rebuilding closes it while someone has it open).
    const sig = list.map(e => e.id).join(',') + '|' + S.modelNpcId;
    if (sig !== options) {
      options = sig;
      fill(modelSel, ...list.map(e => h('option', {value: e.id, text: e.id, selected: e.id === S.modelNpcId})),
        !S.npcs.size ? h('option', {value: '', text: 'No NPCs yet'}) : null);
    }
    modelSel.disabled = !S.npcs.size;
    const e = St.animEntry();
    const looping = Act.forEveryone.playing;
    if (looping) {
      playAll.replaceChildren(icon('x', 15), h('span', {text: 'Stop for everyone'}));
      playAll.disabled = false;
      playAll.title = `${looping.name} goes round and round on ${looping.npc} for everyone: stop it`;
      playAll.classList.add('on');
    } else {
      playAll.replaceChildren(icon('broadcast', 15), h('span', {text: 'Play for everyone'}));
      playAll.classList.remove('on');
      playAll.disabled = !e || !S.modelNpcId || e.isNew;
      playAll.title = !e ? '' : e.isNew ? 'Save it first: players only see saved animations' : e.dirty ? 'Plays the saved version (your unsaved changes are not in it) for everyone near the NPC' : 'Plays it on this NPC for everyone near it';
    }
    liveToggle.input.checked = S.live;
    liveToggle.input.disabled = !S.modelNpcId;
    const st = Act.live;
    liveStatus.className = 'live-status' + (st.status === 'on' ? ' on' : st.status === 'warn' ? ' warn' : '');
    fill(liveStatus, S.live && st.message ? [h('span', {class: ['dot', st.status === 'on' ? 'dot-ok' : 'dot-warn']}), h('span', {class: 'ellipsis', style: {maxWidth: '220px'}, text: st.message})] : []);
    if (S.mode === 'npcs') {
      const n = St.shownNpc();
      hint.className = 'stage-hint glass';
      hint.textContent = !n ? '' : n.entity !== 'player' ? `${n.id} is a ${n.entity.replace(/_/g, ' ')}: shown here as a person` : 'Drag to turn the view · scroll to zoom · double-click to reset';
    } else {
      hint.className = 'stage-hint glass';
      hint.textContent = S.part ? `${PART_NAMES[S.part]}: drag a ring to turn it, or use the sliders · Esc to let go` : 'Click a body part to pose it · drag to turn the view · scroll to zoom';
    }
  };
  for (const ev of ['select', 'lists', 'live', 'server', 'part', 'view', 'anim']) on(ev, draw);
  draw();
}

/** The name plate floating over the NPC's head in the 3D view. */
function mountPlate(viewport) {
  const plate = h('div', {class: 'plate-3d'});
  document.getElementById('stage').append(plate);
  let lastText = null;
  viewport.addOverlay(project => {
    const n = St.shownNpc();
    const text = n ? JSON.stringify([n.plate, n.id]) : '';
    if (text !== lastText) {
      lastText = text;
      fill(plate, n ? n.plate.flatMap(l => renderMini(l, {npc: n.id})) : []);
    }
    if (!n || !n.plate.length || !viewport.posed) {
      plate.hidden = true;
      return;
    }
    const o = viewport.posed.o;
    const p = project([o[0], o[1] + 37, o[2]]);
    if (!p) {
      plate.hidden = true;
      return;
    }
    plate.hidden = false;
    plate.style.left = p.x + 'px';
    plate.style.top = p.y + 'px';
  });
}

// ---- keyboard ----

function bindKeys() {
  document.addEventListener('keydown', e => {
    if (!started || !gate.hidden) return;
    if (document.querySelector('.modal-back')) return;
    const ctrl = e.ctrlKey || e.metaKey;
    const typing = isTyping(e);
    const key = e.key.toLowerCase();
    if (ctrl && key === 's') {
      e.preventDefault();
      if (document.activeElement && document.activeElement.blur) document.activeElement.dispatchEvent(new Event('change', {bubbles: true}));
      Act.saveCurrent();
      return;
    }
    if (typing) return;
    if (ctrl && (key === 'z' || key === 'y')) {
      e.preventDefault();
      const redo = key === 'y' || (key === 'z' && e.shiftKey);
      if (!(redo ? St.redo() : St.undo())) toast(redo ? 'Nothing to redo.' : 'Nothing to undo.', 'info', 1400);
      return;
    }
    if (key === '?' || (e.shiftKey && key === '/')) {
      shortcuts();
      return;
    }
    if (key === 'escape') {
      St.selectPart(null);
      return;
    }
    if (S.mode !== 'animations' || !St.animEntry()) return;
    if (ctrl && key === 'c') {
      if (String(window.getSelection())) return;
      e.preventDefault();
      S.clipboard = Object.fromEntries(Object.entries(St.currentValues()).filter(([, v]) => v));
      toast('Pose copied.', 'ok', 1400);
      emit('anim', {});
      return;
    }
    if (ctrl && key === 'v') {
      e.preventDefault();
      if (S.clipboard) St.setPose(S.clipboard);
      else toast('Copy a pose first.', 'info', 1500);
      return;
    }
    if (ctrl || e.altKey) return;
    if (key === ' ') {
      e.preventDefault();
      St.togglePlay();
    } else if (key === 'k') {
      e.preventDefault();
      if (St.addKeyframe() === 'exists') toast('There is a keyframe here already: it is selected.', 'info', 1800);
    } else if (key === 'delete' || key === 'backspace') {
      e.preventDefault();
      const r = St.deleteKeyframe();
      if (r === 'first') toast('The first keyframe always stays at 0. Change its pose instead.', 'info');
    } else if (key === 'arrowleft' || key === 'arrowright') {
      e.preventDefault();
      St.pause();
      St.setTick(Math.round(S.tick) + (key === 'arrowleft' ? -1 : 1) * (e.shiftKey ? 5 : 1));
    } else if (key === 'home') {
      e.preventDefault();
      St.pause();
      St.setTick(0);
    } else if (key === 'end') {
      e.preventDefault();
      St.pause();
      St.setTick(St.animEntry().draft.length);
    } else if (key === 'm') {
      e.preventDefault();
      const values = Object.fromEntries(Object.entries(St.currentValues()).filter(([, v]) => v));
      import('./anim.js').then(A => St.setPose(A.mirror(values)));
    } else if (key === ',' || key === '.') {
      // Jump to the previous / next keyframe.
      e.preventDefault();
      St.pause();
      const ks = St.animEntry().draft.keyframes.map(k => k.at);
      const t = Math.round(S.tick);
      const to = key === ',' ? [...ks].reverse().find(a => a < t) : ks.find(a => a > t);
      if (to !== undefined) St.setTick(to);
    }
  });
}

function shortcuts() {
  const row = (keys, what) => [h('div', {class: 'keys'}, ...keys.map(k => h('span', {class: 'kbd', text: k}))), h('div', {text: what})];
  dialog({
    title: 'Keyboard shortcuts',
    body: h('div', {class: 'shortcuts'},
      row(['Space'], 'Play or pause'),
      row(['K'], 'Add a keyframe from the pose you see'),
      row(['Delete'], 'Delete the selected keyframe'),
      row(['←', '→'], 'One tick back or on (Shift: 5)'),
      row([',', '.'], 'Previous or next keyframe'),
      row(['Home', 'End'], 'To the start or the end'),
      row(['Ctrl', 'C'], 'Copy the pose'),
      row(['Ctrl', 'V'], 'Paste the pose'),
      row(['M'], 'Mirror the pose left and right'),
      row(['Ctrl', 'Z'], 'Undo'),
      row(['Ctrl', 'Y'], 'Redo'),
      row(['Ctrl', 'S'], 'Save'),
      row(['Esc'], 'Let go of the selected part'),
      row(['Double-click'], 'Reset the view (on empty space)')),
    actions: [{label: 'Close', kind: 'primary', value: true}],
  });
}

start();
