// The editor page: logs in with the link's token, loads /api/state, puts the parts together (menu bar,
// side panels, 3D view, timeline), runs the frame loop, the shortcuts and the 5-second refresh.

import {api, ApiError, takeTokenFromUrl, extractToken, setToken, hasToken, setUnauthorizedHandler} from './api.js';
import * as St from './store.js';
import {S, on, emit} from './store.js';
import * as Act from './actions.js';
import * as A from './anim.js';
import {Viewport} from './viewport.js';
import {mountMenubar} from './menu.js';
import {mountSidebar} from './sidebar.js';
import {mountTimeline} from './timeline.js';
import {mountAnimInspector} from './inspector-anim.js';
import {mountNpcInspector} from './inspector-npc.js';
import {loadSkin} from './skins.js';
import {render as renderMini} from './minimessage.js';
import {PART_NAMES} from './labels.js';
import {h, fill, icon, button, toggle, toast, dialog, isTyping} from './ui.js';
import {mountGizmo} from './gizmo.js';
import {mountOnion} from './onion.js';

const gate = document.getElementById('gate');
const gateBody = document.getElementById('gate-body');
const app = document.getElementById('app');
let started = false;
let expired = false;
let viewport = null;

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

// ---- what the menus and keys do ----

function copyPose() {
  if (!St.animEntry()) return;
  S.clipboard = A.compact(St.currentValues());
  toast('Pose copied.', 'ok', 1400);
  emit('anim', {});
}

function pastePose() {
  if (!St.animEntry()) return;
  if (S.clipboard) St.setPose(S.clipboard);
  else toast('Copy a pose first.', 'info', 1500);
}

function mirrorPose() {
  if (!St.animEntry()) return;
  St.setPose(A.mirror(A.compact(St.currentValues())));
}

function undo() {
  if (!St.undo()) toast('Nothing to undo.', 'info', 1400);
}

function redo() {
  if (!St.redo()) toast('Nothing to redo.', 'info', 1400);
}

function openImport() {
  import('./import-dialog.js').then(m => m.openImportDialog()).catch(() => toast('Import from Blockbench is not here yet.', 'info'));
}

const animOpen = () => S.mode === 'animations' && !!St.animEntry();
const COMMANDS = {
  save: {label: 'Save', icon: 'save', keys: 'Ctrl+S', run: () => Act.saveCurrent(), enabled: () => !!(S.mode === 'npcs' ? St.npcEntry() : St.animEntry())},
  revert: {label: 'Revert', icon: 'revert', run: () => Act.revertCurrent(), hint: 'Drop the unsaved changes',
    enabled: () => !!((S.mode === 'npcs' ? St.npcEntry() : St.animEntry()) || {}).dirty},
  importBb: {label: 'Import from Blockbench…', icon: 'upload', keys: 'Ctrl+I', run: () => openImport(), hint: 'A .bbmodel or Bedrock .animation.json made on a player model'},
  exportAnim: {label: 'Export animation (.yml)', icon: 'download', run: () => Act.exportAnim(), enabled: () => !!St.animEntry()},
  undo: {label: 'Undo', icon: 'undo', keys: 'Ctrl+Z', run: undo, enabled: () => St.canUndo()},
  redo: {label: 'Redo', icon: 'redo', keys: 'Ctrl+Y', run: redo, enabled: () => St.canRedo()},
  copyPose: {label: 'Copy pose', icon: 'copy', keys: 'Ctrl+C', run: copyPose, enabled: animOpen},
  pastePose: {label: 'Paste pose', icon: 'paste', keys: 'Ctrl+V', run: pastePose, enabled: () => animOpen() && !!S.clipboard},
  mirrorPose: {label: 'Mirror pose', icon: 'mirror', keys: 'M', run: mirrorPose, enabled: animOpen},
  selectAll: {label: 'Select all keyframes', icon: 'diamond', keys: 'Ctrl+A', run: () => St.selectAllKeys(), enabled: animOpen},
  onion: {label: 'Onion skin', keys: 'O', run: () => St.setView({onion: !S.view.onion}), checked: () => !!S.view.onion},
  graph: {label: 'Graph editor', keys: 'G', run: () => St.setView({graph: !S.view.graph}), checked: () => !!S.view.graph},
  grid: {label: 'Grid', run: () => St.setView({grid: !S.view.grid}), checked: () => !!S.view.grid},
  layers: {label: 'Breathing and weight shift', run: () => { S.layers = !S.layers; emit('view'); }, checked: () => !!S.layers,
    hint: 'Show the breathing and weight shift every NPC adds on top of its animation'},
  resetCamera: {label: 'Reset camera', icon: 'focus', run: () => viewport && viewport.view('three-quarter')},
  shortcuts: {label: 'Shortcuts', icon: 'keyboard', keys: '?', run: () => shortcuts()},
  about: {label: 'About', icon: 'info', run: () => about()},
};
const MENUS = [
  ['File', ['save', 'revert', '-', 'importBb', 'exportAnim']],
  ['Edit', ['undo', 'redo', '-', 'copyPose', 'pastePose', 'mirrorPose', '-', 'selectAll']],
  ['View', ['onion', 'graph', 'grid', 'layers', '-', 'resetCamera']],
  ['Help', ['shortcuts', 'about']],
];

// ---- the page ----

function mount() {
  mountMenubar(document.getElementById('menubar'), COMMANDS, MENUS, () => {
    if (!expired) {
      expired = true;
      showGate('expired');
    }
  });
  mountSidebar(document.getElementById('left'), {onImport: openImport});

  const right = document.getElementById('right');
  const animBox = h('div', {class: 'side', style: {flex: '1', minHeight: '0'}});
  const npcBody = h('div', {class: 'panel-body'});
  const npcBox = h('div', {class: 'panel grow-panel'}, h('div', {class: 'panel-head'}, h('span', {class: 'title', text: 'NPC settings'})), npcBody);
  right.append(animBox, npcBox);
  mountAnimInspector(animBox);
  mountNpcInspector(npcBody);

  viewport = new Viewport(document.getElementById('viewport'), {
    onPick: part => { if (S.mode === 'animations') St.selectPart(part); },
    partName: part => PART_NAMES[part] || part,
  });
  mountGizmo(viewport);
  mountOnion(viewport, {S, St});
  window.__editor = {S, viewport, St};
  mountTimeline(document.getElementById('timeline'));
  mountStageBar(viewport);
  mountPlate(viewport);

  const layout = () => {
    app.classList.toggle('mode-npcs', S.mode === 'npcs');
    viewport.picking = S.mode === 'animations';
    animBox.hidden = S.mode !== 'animations';
    npcBox.hidden = S.mode !== 'npcs';
  };
  on('select', layout);
  layout();
  viewport.setGrid(S.view.grid);
  on('viewset', () => viewport.setGrid(S.view.grid));

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
    const values = S.mode === 'animations' ? St.currentValues() : {};
    const t = now / 1000;
    const layers = S.mode === 'npcs' || S.layers;
    const live = {
      slim: viewport.slim,
      breath: layers ? (1 - Math.cos(2 * Math.PI * t / 3)) / 2 : 0,
      shift: layers ? 0.9 * Math.sin(2 * Math.PI * t / 9.5) : 0,
    };
    viewport.setPose(values, live);
    if (viewport.onion) viewport.onion.update(live);
    viewport.render(now);
    requestAnimationFrame(frame);
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

/** The strip above the 3D view: camera views, the NPC animations are shown on, live preview, play for everyone. */
function mountStageBar(vp) {
  const bar = document.getElementById('stage-bar');
  const hint = document.getElementById('stage-hint');
  const views = h('div', {class: 'seg'}, ...[['front', 'Front'], ['side', 'Side'], ['back', 'Back'], ['three-quarter', '3/4']].map(([k, label]) =>
    h('button', {type: 'button', text: label, title: 'Look from the ' + (k === 'three-quarter' ? 'front, a little to the side' : k), on: {click: () => vp.view(k)}})));
  const modelSel = h('select', {class: 'select', title: 'The NPC this animation is shown on (and live-previewed on)', on: {change: () => St.setModelNpc(modelSel.value)}});
  const modelGroup = h('div', {class: 'row', style: {gap: '2px'}}, h('span', {class: 'label', text: 'Shown on'}), modelSel);
  const liveToggle = toggle('Live preview in game', S.live, v => Act.setLive(v), 'Plays your draft on the NPC for you in game as you edit');
  const liveStatus = h('span', {class: 'live-status'});
  const playAll = button('Play for everyone', 'broadcast', {class: 'btn-xs btn-ghost', title: 'Plays the saved animation on this NPC for everyone near it',
    onClick: () => (Act.forEveryone.playing ? Act.stopForEveryone() : Act.playForEveryone())});
  const liveGroup = h('div', {class: 'row', style: {gap: '6px', minWidth: '0'}}, liveToggle.el, liveStatus, h('div', {class: 'sep'}), playAll);
  const panelBtn = button('Settings', 'tune', {class: 'btn-xs btn-ghost narrow-only', title: 'Show or hide the settings panel', onClick: () => app.classList.toggle('show-right')});
  fill(bar, h('span', {class: 'label'}, icon('camera', 14)), views, h('div', {class: 'sep'}), modelGroup, h('div', {class: 'spacer'}), liveGroup, panelBtn);

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
      playAll.replaceChildren(icon('stop', 14), h('span', {text: 'Stop for everyone'}));
      playAll.disabled = false;
      playAll.title = `${looping.name} goes round and round on ${looping.npc} for everyone: stop it`;
      playAll.classList.add('on');
    } else {
      playAll.replaceChildren(icon('broadcast', 14), h('span', {text: 'Play for everyone'}));
      playAll.classList.remove('on');
      playAll.disabled = !e || !S.modelNpcId || e.isNew;
      playAll.title = !e ? '' : e.isNew ? 'Save it first: players only see saved animations' : e.dirty ? 'Plays the saved version (your unsaved changes are not in it) for everyone near the NPC' : 'Plays it on this NPC for everyone near it';
    }
    liveToggle.input.checked = S.live;
    liveToggle.input.disabled = !S.modelNpcId;
    const st = Act.live;
    const offline = !S.player.online;
    liveStatus.className = 'live-status' + (st.status === 'on' ? ' on' : st.status === 'warn' || (S.live && offline) ? ' warn' : '');
    const message = S.live ? st.message : offline ? 'You are not in game: live preview needs you in game' : '';
    liveStatus.title = message;
    liveToggle.el.title = offline ? 'Live preview plays your draft on the NPC for you in game: join the server to watch it' : 'Plays your draft on the NPC for you in game as you edit';
    fill(liveStatus, message ? [h('span', {class: ['dot', st.status === 'on' && S.live ? 'dot-ok' : offline && !S.live ? 'dot-off' : 'dot-warn']}),
      h('span', {class: 'ellipsis', style: {maxWidth: '230px'}, text: S.live ? message : 'Not in game'})] : []);
    if (S.mode === 'npcs') {
      const n = St.shownNpc();
      hint.textContent = !n ? '' : n.entity !== 'player' ? `${n.id} is a ${n.entity.replace(/_/g, ' ')}: shown here as a person` : 'Drag to turn the view · scroll to zoom · double-click to reset';
    } else {
      hint.textContent = S.part ? `${PART_NAMES[S.part]}: drag a ring to turn it, or use the Keyframe panel · Esc to let go` : 'Click a body part (or pick one in the Outliner) to pose it · drag to turn the view · scroll to zoom';
    }
  };
  for (const ev of ['select', 'lists', 'live', 'server', 'part', 'view', 'anim']) on(ev, draw);
  draw();
}

/** The name plate floating over the NPC's head in the 3D view. */
function mountPlate(vp) {
  const plate = h('div', {class: 'plate-3d'});
  document.querySelector('.viewport-wrap').append(plate);
  let lastText = null;
  vp.addOverlay(project => {
    const n = St.shownNpc();
    const text = n ? JSON.stringify([n.plate, n.id]) : '';
    if (text !== lastText) {
      lastText = text;
      fill(plate, n ? n.plate.flatMap(l => renderMini(l, {npc: n.id})) : []);
    }
    // Only with NPCs open: while posing it would sit in the way and add nothing.
    if (!n || !n.plate.length || !vp.posed || S.mode !== 'npcs') {
      plate.hidden = true;
      return;
    }
    const o = vp.posed.o;
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
    if (ctrl && key === 'i') {
      e.preventDefault();
      openImport();
      return;
    }
    if (typing) return;
    if (ctrl && (key === 'z' || key === 'y')) {
      e.preventDefault();
      if (key === 'y' || (key === 'z' && e.shiftKey)) redo();
      else undo();
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
      copyPose();
      return;
    }
    if (ctrl && key === 'v') {
      e.preventDefault();
      pastePose();
      return;
    }
    if (ctrl && key === 'a') {
      e.preventDefault();
      St.selectAllKeys();
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
      mirrorPose();
    } else if (key === 'o') {
      e.preventDefault();
      St.setView({onion: !S.view.onion});
    } else if (key === 'g') {
      e.preventDefault();
      St.setView({graph: !S.view.graph});
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
  const group = title => h('div', {class: 'group', text: title});
  dialog({
    title: 'Keyboard shortcuts',
    body: h('div', {class: 'shortcuts'},
      group('Playing'),
      row(['Space'], 'Play or pause'),
      row(['←', '→'], 'One tick back or on (Shift: 5)'),
      row([',', '.'], 'Previous or next keyframe'),
      row(['Home', 'End'], 'To the start or the end'),
      group('Keyframes'),
      row(['K'], 'Add a keyframe from the pose you see'),
      row(['Delete'], 'Delete the selected keyframes'),
      row(['Ctrl', 'A'], 'Select all keyframes'),
      row(['Shift', 'drag'], 'Drag without snapping (keyframes, rings, numbers, graph)'),
      row(['Ctrl', 'wheel'], 'Zoom the timeline'),
      group('Posing'),
      row(['Ctrl', 'C'], 'Copy the pose'),
      row(['Ctrl', 'V'], 'Paste the pose'),
      row(['M'], 'Mirror the pose left and right'),
      row(['Esc'], 'Let go of the selected part'),
      group('Editor'),
      row(['O'], 'Onion skin on or off'),
      row(['G'], 'Graph editor on or off'),
      row(['Ctrl', 'Z'], 'Undo'),
      row(['Ctrl', 'Y'], 'Redo'),
      row(['Ctrl', 'S'], 'Save'),
      row(['Ctrl', 'I'], 'Import from Blockbench'),
      row(['Double-click'], 'Reset the view (on empty space)')),
    actions: [{label: 'Close', kind: 'primary', value: true}],
  });
}

function about() {
  dialog({
    title: 'About the Lively NPCs editor',
    body: h('div', {style: {display: 'flex', flexDirection: 'column', gap: '8px'}},
      h('p', {style: {margin: 0}, text: 'Make NPCs and their animations, see them in 3D, and watch your draft play in game as you edit.'}),
      h('p', {style: {margin: 0}, text: 'Values use Blockbench\'s names and signs (Rotation X/Y/Z, Position X/Y/Z); hover a name to see what it does. Files and commands keep Lively NPCs\' own channel names.'}),
      h('p', {class: 'small muted', style: {margin: 0}, text: 'Icons: Material Icons by Google (Apache License 2.0). 3D: three.js (MIT). Lively NPCs is free to use, not open source.'})),
    actions: [{label: 'Close', kind: 'primary', value: true}],
  });
}

start();
