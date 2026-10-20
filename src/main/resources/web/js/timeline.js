// The timeline: transport, the ruler in ticks (with seconds), keyframe diamonds you can select,
// drag and delete, a lane per body part showing when it moves, easing, length / loop / glance,
// pose copy, paste and mirror, undo / redo, the "does not end at rest" warning, and Save.

import {S, on} from './store.js';
import * as St from './store.js';
import * as A from './anim.js';
import * as Act from './actions.js';
import {GROUPS, EASING_NAMES} from './labels.js';
import {h, fill, icon, button, toggle, toast, confirmDialog} from './ui.js';

const LANE_COLORS = {root: '#9aa4ff', head: '#5cc8ff', body: '#b48cff', right_arm: '#ff8a5c', left_arm: '#ffcf5c', right_leg: '#4fd1a5', left_leg: '#7ee06b'};

export function mountTimeline(root) {
  let showLanes = true;
  try {
    showLanes = localStorage.getItem('livelynpcs.editor.lanes') !== 'off';
  } catch {
    // no storage
  }
  let px = 14;
  let dragging = null;
  let renderLater = false;

  // ---- the bar ----
  const playBtn = button(null, 'play', {class: 'btn-sm', title: 'Play / pause (Space)', onClick: () => St.togglePlay()});
  const startBtn = button(null, 'start', {class: 'btn-sm btn-ghost', title: 'To the start (Home)', onClick: () => { St.pause(); St.setTick(0); }});
  const prevBtn = button(null, 'prev', {class: 'btn-sm btn-ghost', title: 'One tick back (Left)', onClick: () => step(-1)});
  const nextBtn = button(null, 'next', {class: 'btn-sm btn-ghost', title: 'One tick on (Right)', onClick: () => step(1)});
  const readout = h('div', {class: 'tl-readout', attrs: {'aria-live': 'off'}});
  const addBtn = button('Keyframe', 'keyplus', {class: 'btn-sm', title: 'Add a keyframe at the playhead from the pose you see (K)', onClick: () => addKey()});
  const delBtn = button(null, 'trash', {class: 'btn-sm btn-ghost btn-danger', title: 'Delete the selected keyframe (Delete)', onClick: () => deleteKey()});
  const easeSel = h('select', {class: 'select', title: 'How it moves into the selected keyframe', on: {change: () => St.setEase(easeSel.value)}});
  const copyBtn = button(null, 'copy', {class: 'btn-sm btn-ghost', title: 'Copy the pose (Ctrl+C)', onClick: () => copyPose()});
  const pasteBtn = button(null, 'paste', {class: 'btn-sm btn-ghost', title: 'Paste the pose here (Ctrl+V)', onClick: () => pastePose()});
  const mirrorBtn = button(null, 'mirror', {class: 'btn-sm btn-ghost', title: 'Mirror the pose left and right (M)', onClick: () => mirrorPose()});
  const undoBtn = button(null, 'undo', {class: 'btn-sm btn-ghost', title: 'Undo (Ctrl+Z)', onClick: () => St.undo()});
  const redoBtn = button(null, 'redo', {class: 'btn-sm btn-ghost', title: 'Redo (Ctrl+Y)', onClick: () => St.redo()});
  const lengthIn = h('input', {class: 'input', type: 'number', min: 1, max: 24000, step: 1, title: 'Length in ticks (20 a second)',
    on: {change: () => setLength(lengthIn.value), keydown: e => { if (e.key === 'Enter') lengthIn.blur(); }}});
  const lengthSec = h('span', {class: 'muted small'});
  const loop = toggle('Loop', false, v => St.changeAnim(d => { d.loop = v; }), 'Plays round and round (a preview, or by command until stopped)');
  const glance = toggle('Glance', false, v => St.changeAnim(d => { d.glance = v; }),
    'Only for players the NPC is not looking at (it turns its head away). Your live preview still shows it.');
  const autoKey = toggle('Auto key', S.autoKey, v => { S.autoKey = v; S.pending = null; St.emit('anim', {}); }, 'On: posing between keyframes adds a keyframe there. Off: press K to keep the pose.');
  const warn = h('button', {type: 'button', class: 'badge badge-warn warn-badge', title: '', on: {click: () => fixRest()}}, icon('warn', 12), 'Does not end at rest');
  const saveState = h('span', {class: 'save-state'});
  const saveBtn = button('Save', 'save', {class: 'btn-sm btn-primary', title: 'Save (Ctrl+S)', onClick: () => Act.saveCurrent()});
  const lanesBtn = button(null, 'dots', {class: 'btn-xs btn-ghost', title: 'Show or hide the part lanes', onClick: () => {
    showLanes = !showLanes;
    try {
      localStorage.setItem('livelynpcs.editor.lanes', showLanes ? 'on' : 'off');
    } catch {
      // no storage
    }
    render();
  }});

  // Two groups: playing and keyframes on the left; the animation's settings and Save on the right
  // (on a narrow screen the right group moves under the left one, still on the right).
  const bar = h('div', {class: 'tl-bar'},
    h('div', {class: 'tl-group'},
      startBtn, prevBtn, playBtn, nextBtn, readout, h('div', {class: 'sep'}),
      addBtn, delBtn, h('label', {class: 'tl-field', title: 'How it moves into the selected keyframe'}, 'Ease', easeSel), h('div', {class: 'sep'}),
      copyBtn, pasteBtn, mirrorBtn, h('div', {class: 'sep'}), undoBtn, redoBtn),
    h('div', {class: 'tl-group tl-group-right'},
      h('label', {class: 'tl-field'}, 'Length', lengthIn, lengthSec), loop.el, glance.el, autoKey.el, warn, h('div', {class: 'sep'}), saveState, saveBtn));

  // ---- the tracks ----
  const labels = h('div', {class: 'tl-labels'});
  const inner = h('div', {class: 'tl-inner'});
  const scroll = h('div', {class: 'tl-scroll'}, inner);
  const body = h('div', {class: 'tl-body'}, labels, scroll);
  const playhead = h('div', {class: 'playhead'});
  fill(root, bar, body);

  function step(n) {
    St.pause();
    St.setTick(Math.round(S.tick) + n);
  }

  function addKey() {
    const r = St.addKeyframe();
    if (r === 'exists') toast('There is a keyframe here already: it is selected.', 'info', 2200);
  }

  function deleteKey() {
    const r = St.deleteKeyframe();
    if (r === 'first') toast('The first keyframe always stays at 0. Change its pose instead.', 'info');
    else if (r === 'none') toast('Select a keyframe first (click a diamond).', 'info', 2200);
  }

  function copyPose() {
    S.clipboard = A.compact(St.currentValues());
    toast('Pose copied.', 'ok', 1500);
  }

  function pastePose() {
    if (!S.clipboard) {
      toast('Copy a pose first.', 'info', 2000);
      return;
    }
    St.setPose(S.clipboard);
  }

  function mirrorPose() {
    St.setPose(A.mirror(A.compact(St.currentValues())));
  }

  function setLength(v) {
    const e = St.animEntry();
    if (!e) return;
    const last = e.draft.keyframes[e.draft.keyframes.length - 1].at;
    let n = Math.round(Number(v));
    if (!Number.isFinite(n) || n < 1) n = e.draft.length;
    if (n < last) {
      toast(`It cannot be shorter than its last keyframe (tick ${last}).`, 'info');
      n = last;
    }
    St.changeAnim(d => { d.length = Math.min(24000, n); });
  }

  async function fixRest() {
    const e = St.animEntry();
    if (!e) return;
    const d = e.draft;
    const last = d.keyframes[d.keyframes.length - 1];
    const at = last.at < d.length ? d.length : d.length + 8;
    const ok = await confirmDialog({
      title: 'End at rest?',
      message: `A one-shot animation should end with every value at 0, so the NPC finishes in its own pose. Add a rest keyframe at tick ${at}?`,
      confirm: 'Add it',
    });
    if (!ok) return;
    St.changeAnim(x => {
      x.keyframes.push({at, ease: 'ease-in-out', values: {}});
      x.length = Math.max(x.length, at);
    });
    St.setTick(at);
  }

  function tickAt(clientX) {
    const r = inner.getBoundingClientRect();
    return (clientX - r.left - 12) / px;
  }

  function scrubFrom(e) {
    St.pause();
    const move = ev => St.setTick(Math.round(tickAt(ev.clientX)));
    move(e);
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function startKeyDrag(e, k) {
    e.stopPropagation();
    e.preventDefault();
    St.pause();
    S.selectedAt = k.at;
    S.pending = null;
    St.setTick(k.at);
    if (k.at === 0) {
      render();
      return;
    }
    const el = e.currentTarget;
    const gesture = 'move-key-' + performance.now();
    let at = k.at;
    dragging = {el};
    el.classList.add('dragging');
    const move = ev => {
      const want = Math.max(1, Math.round(tickAt(ev.clientX)));
      if (want === at) return;
      const got = St.moveKeyframe(at, want, gesture);
      if (got !== at) {
        at = got;
        el.style.left = (12 + at * px) + 'px';
        St.setTick(at);
      }
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      dragging = null;
      St.endGesture();
      render();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function lanesFor(d) {
    // For each part, the spans between keyframes where any of its channels changes.
    const out = {};
    for (const g of GROUPS) {
      const spans = [], marks = [];
      for (let i = 0; i < d.keyframes.length; i++) {
        const k = d.keyframes[i];
        const active = g.channels.some(c => (k.values[c] || 0) !== 0);
        if (active) marks.push(k.at);
        if (i > 0) {
          const p = d.keyframes[i - 1];
          if (g.channels.some(c => (k.values[c] || 0) !== (p.values[c] || 0))) spans.push([p.at, k.at]);
        }
      }
      if (d.loop && d.keyframes.length > 1) {
        const last = d.keyframes[d.keyframes.length - 1], first = d.keyframes[0];
        if (d.length > last.at && g.channels.some(c => (last.values[c] || 0) !== (first.values[c] || 0))) spans.push([last.at, d.length]);
      }
      out[g.part] = {spans, marks};
    }
    return out;
  }

  function render() {
    if (dragging) {
      renderLater = true;
      return;
    }
    renderLater = false;
    const e = St.animEntry();
    root.hidden = S.mode !== 'animations';
    const has = !!e;
    for (const b of [playBtn, startBtn, prevBtn, nextBtn, addBtn, delBtn, copyBtn, pasteBtn, mirrorBtn, saveBtn]) b.disabled = !has;
    easeSel.disabled = lengthIn.disabled = loop.input.disabled = glance.input.disabled = !has;
    undoBtn.disabled = !St.canUndo();
    redoBtn.disabled = !St.canRedo();
    if (!e) {
      fill(labels);
      fill(inner, h('div', {class: 'side-empty', text: 'Pick an animation on the left, or make a new one.'}));
      warn.hidden = true;
      saveState.textContent = '';
      updateHead();
      return;
    }
    const d = e.draft;
    lengthIn.value = d.length;
    lengthSec.textContent = A.seconds(d.length);
    loop.input.checked = d.loop;
    glance.input.checked = d.glance;
    autoKey.input.checked = S.autoKey;
    const rest = d.loop || A.endsAtRest(d, S.channelKeys);
    warn.hidden = rest;
    warn.title = 'A one-shot animation should end with every value at 0 so the NPC finishes in its own pose. Click to add a rest keyframe at the end.';
    saveState.textContent = e.isNew ? 'Not saved yet' : e.dirty ? 'Unsaved changes' : 'Saved';
    saveBtn.disabled = !e.dirty && !e.isNew;
    delBtn.disabled = S.selectedAt === null || S.selectedAt === 0;
    pasteBtn.disabled = !S.clipboard;

    const k = S.selectedAt === null ? null : d.keyframes.find(x => x.at === S.selectedAt);
    fill(easeSel, ...S.easings.map(key => h('option', {value: key, text: EASING_NAMES[key] || key})));
    easeSel.value = k ? k.ease : 'linear';
    easeSel.disabled = !k || k.at === 0;
    easeSel.title = !k ? 'Select a keyframe to set how it moves into it' : k.at === 0 ? 'The first keyframe has nothing before it' : 'How it moves into this keyframe';

    // Scale: fit the length into the width, but never closer than 5 px a tick.
    const width = Math.max(200, scroll.clientWidth || 600);
    const span = d.length + 6;
    px = Math.max(5, Math.min(40, (width - 24) / span));
    const total = Math.max(width, 24 + span * px);
    inner.style.width = total + 'px';
    inner.style.setProperty('--px', px + 'px');

    const ruler = h('div', {class: 'tl-ruler', on: {pointerdown: scrubFrom}});
    const every = px >= 9 ? 1 : px >= 4 ? 2 : 5;
    const labelEvery = px * 5 >= 34 ? 5 : px * 10 >= 34 ? 10 : 20;
    const maxTick = Math.floor((total - 12) / px);
    for (let t = 0; t <= maxTick; t += every) {
      const x = 12 + t * px;
      const major = t % 5 === 0;
      ruler.append(h('div', {class: ['tl-tick', major && 'major'], style: {left: x + 'px'}}));
      if (t % 20 === 0) ruler.append(h('div', {class: 'tl-tick-label sec', style: {left: x + 'px'}, text: t / 20 + ' s'}));
      else if (t % labelEvery === 0) ruler.append(h('div', {class: 'tl-tick-label', style: {left: x + 'px'}, text: String(t)}));
    }

    const track = h('div', {
      class: 'tl-track',
      on: {
        pointerdown: ev => {
          if (ev.target !== track) return;
          S.selectedAt = null;
          scrubFrom(ev);
        },
        dblclick: ev => {
          if (ev.target !== track) return;
          St.setTick(Math.round(tickAt(ev.clientX)));
          addKey();
        },
      },
    });
    for (const key of d.keyframes) {
      const restKey = Object.values(key.values).every(v => !v);
      const el = h('div', {
        class: ['key', key.at === S.selectedAt && 'selected', key.at === 0 && 'first', restKey && 'rest'],
        style: {left: (12 + key.at * px) + 'px'},
        dataset: {at: String(key.at)},
        title: `Tick ${key.at} (${A.seconds(key.at)})${key.at ? ' · ' + (EASING_NAMES[key.ease] || key.ease) : ''}${restKey ? ' · at rest' : ''}`,
        on: {pointerdown: ev => startKeyDrag(ev, key)},
      });
      track.append(el);
    }

    const laneEls = [];
    const labelEls = [h('div', {class: 'tl-label ruler-label'}, 'Ticks', h('div', {class: 'spacer'}), lanesBtn), h('div', {class: 'tl-label main', text: 'Keyframes'})];
    if (showLanes) {
      const lanes = lanesFor(d);
      for (const g of GROUPS) {
        const color = LANE_COLORS[g.part];
        const lane = h('div', {class: 'tl-lane'});
        for (const [a, b] of lanes[g.part].spans) {
          lane.append(h('div', {class: 'tl-span', style: {left: (12 + a * px) + 'px', width: Math.max(2, (b - a) * px) + 'px', background: color}}));
        }
        for (const at of lanes[g.part].marks) {
          lane.append(h('div', {class: 'tl-mark', style: {left: (12 + at * px) + 'px', background: color}}));
        }
        laneEls.push(lane);
        labelEls.push(h('div', {
          class: 'tl-label', style: {cursor: 'pointer', color: S.part === g.part ? 'var(--text)' : ''}, title: 'Select ' + g.name.toLowerCase(),
          on: {click: () => St.selectPart(S.part === g.part ? null : g.part)},
        }, h('span', {class: 'sw', style: {background: color}}), g.name));
      }
    }
    const end = h('div', {class: 'tl-end', style: {left: (12 + d.length * px) + 'px', right: 0}});
    fill(inner, ruler, track, ...laneEls, end, playhead);
    fill(labels, labelEls);
    updateHead();
  }

  function updateHead() {
    const e = St.animEntry();
    playBtn.replaceChildren(icon(S.playing ? 'pause' : 'play', 15));
    playBtn.classList.toggle('on', S.playing);
    if (!e) {
      readout.textContent = '';
      playhead.hidden = true;
      return;
    }
    playhead.hidden = false;
    playhead.style.left = (12 + S.tick * px) + 'px';
    const t = S.playing ? S.tick.toFixed(1) : String(Math.round(S.tick));
    fill(readout, 'tick ', h('b', {text: t}), ` / ${e.draft.length}  ·  ${(S.tick / 20).toFixed(2)} s`);
    if (S.playing) {
      const x = 12 + S.tick * px;
      if (x < scroll.scrollLeft || x > scroll.scrollLeft + scroll.clientWidth - 20) scroll.scrollLeft = Math.max(0, x - 40);
    }
  }

  on('select', render);
  on('anim', render);
  on('lists', render);
  on('part', render);
  on('tick', () => {
    const e = St.animEntry();
    if (!S.playing && e && !dragging) {
      // Keep the diamonds' selection in step with the playhead.
      inner.querySelectorAll('.key').forEach(el => el.classList.toggle('selected', Number(el.dataset.at) === S.selectedAt));
      delBtn.disabled = S.selectedAt === null || S.selectedAt === 0;
      const key = S.selectedAt === null ? null : e.draft.keyframes.find(x => x.at === S.selectedAt);
      easeSel.value = key ? key.ease : 'linear';
      easeSel.disabled = !key || key.at === 0;
    }
    updateHead();
  });
  on('play', updateHead);
  new ResizeObserver(() => render()).observe(scroll);
  render();
  return {render, copyPose, pastePose, mirrorPose, addKey, deleteKey, step};
}
