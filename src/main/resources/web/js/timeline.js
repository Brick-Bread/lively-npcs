// The timeline, laid out like a model animator's: a toolbar (play, time, keyframes, snapping, onion
// skin, graph editor, zoom), a ruler in seconds and ticks, a column of parts (in step with the Outliner)
// that open into a row per value, keyframe diamonds you can click, drag and box-select, and the playhead.
//
// A keyframe here holds the whole pose (every value), so a diamond on any row stands for that keyframe:
// selecting or moving it selects or moves the keyframe. On a part's or value's row a diamond is bright
// where that part or value changes, and faint where it only holds.

import {S, on} from './store.js';
import * as St from './store.js';
import * as A from './anim.js';
import {GROUPS, PART_NAMES, PART_COLORS, AXIS_COLORS} from './labels.js';
import {rowsFor} from './axes.js';
import {snapTick, rulerSteps, TICK_STEPS, ANGLE_STEPS} from './snap.js';
import {h, fill, icon, button, toast} from './ui.js';

const NAME_W = 200;
const PAD = 12;
const ROW_H = 22;
const HEAD_H = 24;

export function mountTimeline(root, hooks = {}) {
  let px = 10;
  let drag = null;
  let trackW = 600;
  let rowsNow = [];

  // ---- the toolbar ----
  const startBtn = button(null, 'start', {class: 'btn-xs btn-ghost', title: 'To the start (Home)', onClick: () => { St.pause(); St.setTick(0); }});
  const prevBtn = button(null, 'prev', {class: 'btn-xs btn-ghost', title: 'One tick back (Left; Shift: 5)', onClick: () => step(-1)});
  const playBtn = button(null, 'play', {class: 'btn-xs btn-ghost', title: 'Play / pause (Space)', onClick: () => St.togglePlay()});
  const nextBtn = button(null, 'next', {class: 'btn-xs btn-ghost', title: 'One tick on (Right; Shift: 5)', onClick: () => step(1)});
  const endBtn = button(null, 'end', {class: 'btn-xs btn-ghost', title: 'To the end (End)', onClick: () => {
    const e = St.animEntry();
    St.pause();
    if (e) St.setTick(e.draft.length);
  }});
  const readout = h('div', {class: 'tl-readout', attrs: {'aria-live': 'off'}});
  const addBtn = button(null, 'keyplus', {class: 'btn-xs btn-ghost', title: 'Add a keyframe at the playhead from the pose you see (K)', onClick: () => addKey()});
  const delBtn = button(null, 'trash', {class: 'btn-xs btn-ghost', title: 'Delete the selected keyframes (Delete)', onClick: () => deleteKeys()});
  const snapSel = h('select', {class: 'select', title: 'Snap keyframes and the playhead to this many ticks (hold Shift while dragging to move freely)',
    on: {change: () => St.setView({snapTicks: Number(snapSel.value)})}},
  TICK_STEPS.map(n => h('option', {value: n, text: n === 1 ? 'Snap: 1 tick' : `Snap: ${n} ticks`})));
  const angleSel = h('select', {class: 'select', title: 'Snap angles when dragging rings, sliders, numbers or graph points (Shift: free)',
    on: {change: () => St.setView({snapAngle: Number(angleSel.value)})}},
  ANGLE_STEPS.map(n => h('option', {value: n, text: n ? `Angles: ${n}°` : 'Angles: free'})));
  const onionBtn = button(null, 'layers', {class: 'btn-xs btn-ghost', title: 'Onion skin: faint poses before and after the playhead (O)', onClick: () => St.setView({onion: !S.view.onion})});
  const onionSel = h('select', {class: 'select', title: 'Which ghost poses the onion skin shows',
    on: {change: () => St.setView({onionMode: onionSel.value})}},
  h('option', {value: 'keys', text: 'Keyframes before / after'}),
  ...[2, 4, 5, 10].map(n => h('option', {value: 'ticks:' + n, text: `Every ${n} ticks`})));
  const graphBtn = button('Graph', 'graph', {class: 'btn-xs btn-ghost', title: 'Graph editor: the selected value\'s curve (G)', onClick: () => St.setView({graph: !S.view.graph})});
  const zoomOut = button(null, 'zoomout', {class: 'btn-xs btn-ghost', title: 'Zoom out (Ctrl + wheel)', onClick: () => zoomBy(1 / 1.4)});
  const zoomIn = button(null, 'zoomin', {class: 'btn-xs btn-ghost', title: 'Zoom in (Ctrl + wheel)', onClick: () => zoomBy(1.4)});
  const fitBtn = button(null, 'focus', {class: 'btn-xs btn-ghost', title: 'Fit the whole animation', onClick: () => St.setView({zoom: 0})});
  const toolbar = h('div', {class: 'tl-toolbar'},
    startBtn, prevBtn, playBtn, nextBtn, endBtn, readout, h('div', {class: 'sep'}),
    addBtn, delBtn, h('div', {class: 'sep'}), snapSel, angleSel, h('div', {class: 'sep'}),
    onionBtn, onionSel, graphBtn, h('div', {class: 'spacer'}), zoomOut, fitBtn, zoomIn);

  // ---- the rows ----
  const content = h('div', {class: 'tl-content'});
  const scroller = h('div', {class: 'tl-scroller'}, content);
  const resize = h('div', {class: 'tl-resize', title: 'Drag to make the timeline taller or shorter'});
  fill(root, resize, toolbar, scroller);
  const graphHost = h('div', {class: 'tl-graph-host'});

  function step(n) {
    St.pause();
    St.setTick(Math.round(S.tick) + n);
  }

  function addKey() {
    if (St.addKeyframe() === 'exists') toast('There is a keyframe here already: it is selected.', 'info', 2000);
  }

  function deleteKeys() {
    const r = St.deleteKeyframe();
    if (r === 'first') toast('The first keyframe always stays at 0. Change its pose instead.', 'info');
    else if (r === 'none') toast('Select a keyframe first (click a diamond).', 'info', 2000);
  }

  function zoomBy(f) {
    St.setView({zoom: Math.max(1, Math.min(48, px * f))});
  }

  /** The tick under a pointer x (fractional). */
  function tickAt(clientX) {
    const r = content.getBoundingClientRect();
    return (clientX - r.left - NAME_W - PAD) / px;
  }

  function xOf(tick) {
    return PAD + tick * px;
  }

  function rowList() {
    const out = [{kind: 'all'}];
    for (const g of GROUPS) {
      out.push({kind: 'part', part: g.part, keys: g.channels});
      if (S.expanded.has(g.part)) for (const r of rowsFor(g.part)) out.push({kind: 'value', part: g.part, row: r, keys: [r.key]});
    }
    return out;
  }

  /** For each keyframe: whether any of these channels changes into or out of it. */
  function changes(d, keys) {
    const ks = d.keyframes;
    const val = (k, c) => k.values[c] || 0;
    return ks.map((k, i) => keys.some(c => {
      const v = val(k, c);
      const prev = i > 0 ? val(ks[i - 1], c) : d.loop && ks.length > 1 ? val(ks[ks.length - 1], c) : v;
      const next = i < ks.length - 1 ? val(ks[i + 1], c) : d.loop ? val(ks[0], c) : v;
      return v !== prev || v !== next || (i === 0 && v !== 0);
    }));
  }

  /** The stretches between keyframes where any of these channels moves. */
  function spans(d, keys) {
    const ks = d.keyframes;
    const out = [];
    const differ = (a, b) => keys.some(c => (a.values[c] || 0) !== (b.values[c] || 0));
    for (let i = 1; i < ks.length; i++) if (differ(ks[i - 1], ks[i])) out.push([ks[i - 1].at, ks[i].at]);
    if (d.loop && ks.length > 1 && d.length > ks[ks.length - 1].at && differ(ks[ks.length - 1], ks[0])) out.push([ks[ks.length - 1].at, d.length]);
    return out;
  }

  function nameCell(row) {
    if (row.kind === 'all') {
      return h('div', {class: 'tl-name', title: 'Every keyframe: each holds the whole pose'}, icon('diamond', 12), 'Keyframes');
    }
    if (row.kind === 'part') {
      const open = S.expanded.has(row.part);
      const caret = h('span', {class: ['caret', open && 'open'], on: {click: ev => { ev.stopPropagation(); St.toggleExpanded(row.part); }}}, icon('caret', 14));
      return h('div', {class: 'tl-name', title: `${PART_NAMES[row.part]}: click to select, the arrow to show its values`,
        on: {click: () => {
          if (S.part === row.part) St.toggleExpanded(row.part);
          else St.selectPart(row.part);
        }}},
      caret, h('span', {class: 'sw', style: {background: PART_COLORS[row.part]}}), PART_NAMES[row.part]);
    }
    const r = row.row;
    return h('div', {class: 'tl-name', title: r.hint, on: {click: () => St.selectChannel(row.part, r.key)}},
      h('span', {class: 'axis-chip', style: {background: AXIS_COLORS[r.axis]}, text: r.axis.toUpperCase()}), r.label);
  }

  function trackCell(row, d) {
    const track = h('div', {class: 'tl-track', dataset: {kind: row.kind}});
    const ks = d.keyframes;
    const lit = row.kind === 'all' ? ks.map(() => true) : changes(d, row.keys);
    const color = row.kind === 'part' ? PART_COLORS[row.part] : row.kind === 'value' ? AXIS_COLORS[row.row.axis] : null;
    if (row.kind !== 'all') {
      for (const [a, b] of spans(d, row.keys)) {
        track.append(h('div', {class: 'tl-span', style: {left: xOf(a) + 'px', width: Math.max(2, (b - a) * px) + 'px', background: color}}));
      }
    }
    ks.forEach((k, i) => {
      const rest = row.kind === 'all' && Object.values(k.values).every(v => !v);
      const el = h('div', {
        class: ['kf', !lit[i] && 'faint', S.keySel.has(k.at) && 'sel', rest && 'rest'],
        style: {left: xOf(k.at) + 'px', background: color && lit[i] ? color : undefined},
        dataset: {at: String(k.at)},
        title: `Tick ${k.at} (${A.seconds(k.at)})${k.at ? ' · ' + k.ease : ''}${rest ? ' · at rest' : ''}`,
      });
      track.append(el);
    });
    return track;
  }

  function ruler(d) {
    const el = h('div', {class: 'tl-ruler'});
    const {minor, mid, label} = rulerSteps(px);
    const maxTick = Math.floor((trackW - PAD) / px);
    for (let t = 0; t <= maxTick; t += minor) {
      const sec = t % 20 === 0;
      el.append(h('div', {class: ['tl-tick', sec ? 'sec' : t % mid === 0 && 'mid'], style: {left: xOf(t) + 'px'}}));
      if (sec) el.append(h('div', {class: 'tl-tick-label sec', style: {left: xOf(t) + 'px'}, text: t / 20 + ' s'}));
      else if (label && t % label === 0) el.append(h('div', {class: 'tl-tick-label', style: {left: xOf(t) + 'px'}, text: String(t)}));
    }
    el.append(handle);
    void d;
    return el;
  }

  const playhead = h('div', {class: 'tl-playhead'});
  const handle = h('div', {class: 'tl-playhead-handle'});
  const box = h('div', {class: 'tl-box', hidden: true});

  function render() {
    const e = St.animEntry();
    root.hidden = S.mode !== 'animations';
    for (const b of [playBtn, startBtn, prevBtn, nextBtn, endBtn, addBtn]) b.disabled = !e;
    delBtn.disabled = !(S.keySel.size && [...S.keySel].some(x => x !== 0));
    snapSel.value = String(S.view.snapTicks);
    angleSel.value = String(S.view.snapAngle);
    onionBtn.classList.toggle('on', !!S.view.onion);
    onionSel.hidden = !S.view.onion;
    onionSel.value = S.view.onionMode;
    graphBtn.classList.toggle('on', !!S.view.graph);
    if (!e) {
      fill(content, h('div', {class: 'tl-empty', text: 'Pick an animation on the left, or make a new one.'}));
      content.style.width = '';
      updateHead();
      return;
    }
    const d = e.draft;
    const avail = Math.max(240, scroller.clientWidth - NAME_W - 14);
    px = S.view.zoom > 0 ? S.view.zoom : Math.max(1, Math.min(40, (avail - PAD * 2) / (d.length + 4)));
    trackW = Math.max(avail, PAD * 2 + (d.length + 24) * px);
    content.style.width = (NAME_W + trackW) + 'px';
    rowsNow = rowList();

    const head = h('div', {class: 'tl-row tl-head'}, h('div', {class: 'tl-name', text: S.view.graph ? 'Graph' : 'Animators'}), ruler(d));
    const out = [head];
    if (S.view.graph && hooks.graph) {
      // The graph editor takes the rows' place; the names still pick what it draws.
      const names = h('div', {class: 'tl-graph-names'}, rowsNow.filter(r => r.kind !== 'all').map(r => {
        const picked = r.kind === 'value' && (S.channel || (S.part && rowsFor(S.part)[0] && rowsFor(S.part)[0].key)) === r.row.key && S.part === r.part;
        return h('div', {class: ['tl-row', r.kind, S.part === r.part && 'active', picked && 'picked'], style: {height: ROW_H + 'px'}}, nameCell(r));
      }));
      out.push(h('div', {class: 'tl-graph-row'}, names, graphHost));
      fill(content, out, playhead);
      hooks.graph.render(graphHost, {px, pad: PAD, width: trackW});
    } else {
      const channel = S.channel || (S.part && rowsFor(S.part)[0] ? rowsFor(S.part)[0].key : null);
      for (const r of rowsNow) {
        const active = r.kind !== 'all' && S.part === r.part;
        const picked = r.kind === 'value' && r.row.key === channel && S.part === r.part;
        out.push(h('div', {class: ['tl-row', r.kind, active && 'active', picked && 'picked']}, nameCell(r), trackCell(r, d)));
      }
      const grid = [];
      for (let t = 0; t <= (trackW - PAD) / px; t += 20) grid.push(h('div', {class: 'tl-grid', style: {left: (NAME_W + xOf(t)) + 'px', top: HEAD_H + 'px', bottom: 0}}));
      const end = h('div', {class: 'tl-end', style: {left: (NAME_W + xOf(d.length)) + 'px', right: 0, top: HEAD_H + 'px', bottom: 0}});
      fill(content, out, grid, end, playhead, box);
    }
    updateHead();
  }

  function updateHead() {
    const e = St.animEntry();
    playBtn.replaceChildren(icon(S.playing ? 'pause' : 'play', 15));
    playBtn.classList.toggle('on', S.playing);
    if (!e) {
      readout.textContent = '';
      playhead.hidden = handle.hidden = true;
      return;
    }
    playhead.hidden = handle.hidden = false;
    const x = xOf(S.tick);
    playhead.style.left = (NAME_W + x) + 'px';
    playhead.style.top = HEAD_H + 'px';
    playhead.style.height = Math.max(0, content.scrollHeight - HEAD_H) + 'px';
    handle.style.left = x + 'px';
    const t = S.playing ? S.tick.toFixed(1) : String(Math.round(S.tick));
    handle.dataset.t = t;
    fill(readout, h('b', {text: (S.tick / 20).toFixed(2) + ' s'}), `  ·  tick ${t} / ${e.draft.length}`);
    if (S.playing) {
      const left = scroller.scrollLeft, w = scroller.clientWidth - NAME_W;
      if (x < left || x > left + w - 30) scroller.scrollLeft = Math.max(0, x - 40);
    }
  }

  // ---- pointer gestures: scrub on the ruler, drag and box-select keyframes on the rows ----

  function scrub(e) {
    St.pause();
    const len = St.animEntry() ? St.animEntry().draft.length : 0;
    const go = ev => St.setTick(Math.max(0, Math.min(len, snapTick(tickAt(ev.clientX), S.view.snapTicks, ev.shiftKey))));
    go(e);
    drag = {kind: 'scrub', move: go};
  }

  function startKeys(e, at) {
    const ed = St.animEntry();
    if (!ed) return;
    const add = e.shiftKey || e.ctrlKey || e.metaKey;
    if (add) St.selectKey(at, {toggle: true});
    else if (!S.keySel.has(at)) St.selectKey(at);
    else {
      S.selectedAt = at;
      St.emit('keys');
    }
    St.pause();
    St.setTick(at, false);
    if (at === 0 || !S.keySel.has(at)) return;
    const base = ed.draft.keyframes.map(k => ({at: k.at, ease: k.ease, values: {...k.values}}));
    const moving = new Set(S.keySel);
    const gesture = 'drag:keys:' + performance.now();
    let delta = 0;
    drag = {
      kind: 'keys',
      move: ev => {
        const want = snapTick(tickAt(ev.clientX), S.view.snapTicks, ev.shiftKey);
        const dt = Math.max(1, want) - at;
        if (dt === delta) return;
        if (St.moveKeyframes(base, moving, dt, gesture)) {
          delta = dt;
          S.keySel = new Set([...moving].map(t => (t === 0 ? 0 : t + delta)));
          S.selectedAt = at + delta;
          St.setTick(at + delta, false);
        }
      },
      up: () => {
        St.endGesture();
        St.emit('keys');
      },
    };
  }

  function startBox(e) {
    const r = content.getBoundingClientRect();
    const x0 = e.clientX - r.left, y0 = e.clientY - r.top;
    const add = e.shiftKey || e.ctrlKey || e.metaKey;
    const before = new Set(S.keySel);
    let moved = false;
    drag = {
      kind: 'box',
      move: ev => {
        const rr = content.getBoundingClientRect();
        const x1 = ev.clientX - rr.left, y1 = ev.clientY - rr.top;
        if (!moved && Math.hypot(x1 - x0, y1 - y0) < 4) return;
        moved = true;
        const left = Math.max(NAME_W, Math.min(x0, x1)), right = Math.max(x0, x1);
        const top = Math.max(HEAD_H, Math.min(y0, y1)), bottom = Math.max(y0, y1);
        Object.assign(box.style, {left: left + 'px', top: top + 'px', width: Math.max(0, right - left) + 'px', height: Math.max(0, bottom - top) + 'px'});
        box.hidden = false;
        const ed = St.animEntry();
        if (!ed) return;
        const t0 = (left - NAME_W - PAD - 5) / px, t1 = (right - NAME_W - PAD + 5) / px;
        const ticks = ed.draft.keyframes.map(k => k.at).filter(t => t >= t0 && t <= t1);
        St.setKeySelection(add ? [...before, ...ticks] : ticks);
      },
      up: ev => {
        box.hidden = true;
        if (moved) return;
        // A click: the playhead goes there, and the selection is let go (Shift keeps it).
        const ed = St.animEntry();
        if (!ed) return;
        if (!add) St.setKeySelection([]);
        St.pause();
        St.setTick(Math.max(0, Math.min(ed.draft.length, snapTick(tickAt(ev.clientX), S.view.snapTicks, ev.shiftKey))));
      },
    };
  }

  scroller.addEventListener('pointerdown', e => {
    if (e.button !== 0 || !St.animEntry()) return;
    if (e.target.closest('.tl-name')) return;
    if (e.target.closest('.tl-ruler')) {
      e.preventDefault();
      scrub(e);
    } else if (e.target.classList.contains('kf')) {
      e.preventDefault();
      startKeys(e, Number(e.target.dataset.at));
    } else if (e.target.closest('.tl-track') || e.target === content || e.target.classList.contains('tl-end') || e.target.classList.contains('tl-grid')) {
      e.preventDefault();
      startBox(e);
    } else {
      return;
    }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp, {once: true});
  });
  function onMove(ev) {
    if (drag && drag.move) drag.move(ev);
  }
  function onUp(ev) {
    window.removeEventListener('pointermove', onMove);
    const d = drag;
    drag = null;
    if (d && d.up) d.up(ev);
  }
  scroller.addEventListener('dblclick', e => {
    if (!e.target.closest('.tl-track') || e.target.classList.contains('kf')) return;
    const ed = St.animEntry();
    if (!ed) return;
    St.setTick(Math.max(0, snapTick(tickAt(e.clientX), S.view.snapTicks, e.shiftKey)));
    addKey();
  });
  scroller.addEventListener('wheel', e => {
    if (!(e.ctrlKey || e.metaKey)) return;
    e.preventDefault();
    const before = tickAt(e.clientX);
    zoomBy(e.deltaY < 0 ? 1.25 : 1 / 1.25);
    // Keep the tick under the pointer where it was.
    const r = scroller.getBoundingClientRect();
    scroller.scrollLeft = Math.max(0, PAD + before * px - (e.clientX - r.left - NAME_W));
  }, {passive: false});

  // Resizing the timeline from its top edge.
  resize.addEventListener('pointerdown', e => {
    e.preventDefault();
    const y0 = e.clientY;
    const h0 = root.getBoundingClientRect().height;
    const move = ev => {
      const want = Math.max(140, Math.min(window.innerHeight - 220, h0 + (y0 - ev.clientY)));
      document.documentElement.style.setProperty('--tl-h', want + 'px');
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      try {
        localStorage.setItem('livelynpcs.editor.timeline', getComputedStyle(document.documentElement).getPropertyValue('--tl-h').trim());
      } catch {
        // no storage
      }
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up, {once: true});
  });
  try {
    const saved = localStorage.getItem('livelynpcs.editor.timeline');
    if (saved && /^\d+px$/.test(saved)) document.documentElement.style.setProperty('--tl-h', saved);
  } catch {
    // no storage
  }

  let queued = false;
  const later = () => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => {
      queued = false;
      render();
    });
  };
  for (const ev of ['select', 'anim', 'lists', 'part', 'keys', 'viewset']) on(ev, later);
  on('tick', () => {
    if (S.view.graph && hooks.graph) hooks.graph.tick();
    updateHead();
  });
  on('play', updateHead);
  new ResizeObserver(() => later()).observe(scroller);
  render();
  return {render, addKey, deleteKeys, step};
}
