// The graph editor, inside the timeline (its Graph button, or G): the selected value's curve between
// keyframes, drawn with each segment's easing exactly as the game plays it, with the part's other
// values faint behind it. Drag a keyframe's point up or down to change the value, sideways to move the
// keyframe in time (both snap; Shift: free). Click a segment to pick it and choose its easing.

import {S} from './store.js';
import * as St from './store.js';
import {curve, segments, segmentAt, range, gridStep} from './graph.js';
import {rowsFor, rowOf, toShown, fromShown} from './axes.js';
import {AXIS_COLORS, PART_NAMES, EASING_NAMES, EASING_HINTS} from './labels.js';
import {snapTick, snapAngle, snapPixels} from './snap.js';
import {h, fill} from './ui.js';

const HIT = 7;

export function graphView({headH = 24, nameW = 200} = {}) {
  const canvas = h('canvas', {class: 'tl-graph-canvas'});
  const easeBar = h('div', {class: 'tl-graph-ease'});
  const note = h('div', {class: 'tl-graph-note'});
  let host = null, scroller = null;
  let geo = {px: 10, pad: 12, width: 600};
  let frozen = null;
  let drag = null;
  let pickedSeg = null;

  /** The value drawn: the picked one, else the selected part's first. */
  function channel() {
    if (!S.part) return null;
    const rows = rowsFor(S.part);
    if (!rows.length) return null;
    return rows.find(r => r.key === S.channel) || rows[0];
  }

  function shownCurve(d, key) {
    return curve(d, key, Math.max(0.25, 2 / geo.px)).map(([t, v]) => [t, toShown(key, v)]);
  }

  /** The value range drawn (frozen while dragging, so nothing jumps under the pointer). */
  function yRange(d, row) {
    if (frozen) return frozen;
    let [lo, hi] = range(d, row.key).map(v => toShown(row.key, v)).sort((a, b) => a - b);
    const min = row.type === 'rotation' ? 20 : 2;
    if (hi - lo < min) {
      const mid = (hi + lo) / 2;
      lo = mid - min / 2;
      hi = mid + min / 2;
    }
    const padV = (hi - lo) * 0.14;
    return [lo - padV, hi + padV];
  }

  function size() {
    const visible = scroller ? scroller.clientHeight - headH : 200;
    return {w: geo.width, h: Math.max(120, visible - 2)};
  }

  const xOf = t => geo.pad + t * geo.px;

  function draw() {
    if (!host) return;
    const e = St.animEntry();
    const row = channel();
    const {w, h: hgt} = size();
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(hgt * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(hgt * dpr);
      canvas.style.width = w + 'px';
      canvas.style.height = hgt + 'px';
    }
    const g = canvas.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, hgt);
    g.fillStyle = '#1d1f24';
    g.fillRect(0, 0, w, hgt);
    if (!e || !row) {
      note.textContent = e ? 'Pick a value on the left (a part, then Rotation X, Y, Z or Position…) to see its curve.' : '';
      note.hidden = !e;
      easeBar.hidden = true;
      return;
    }
    note.hidden = true;
    const d = e.draft;
    const [lo, hi] = yRange(d, row);
    const yOf = v => hgt - 10 - (v - lo) / (hi - lo) * (hgt - 20);
    const left = scroller ? scroller.scrollLeft : 0;

    // The grid: a line every second, value lines at a round step, zero brighter.
    g.lineWidth = 1;
    for (let t = 0; xOf(t) <= w; t += 20) {
      g.strokeStyle = 'rgba(255,255,255,0.06)';
      g.beginPath();
      g.moveTo(Math.round(xOf(t)) + 0.5, 0);
      g.lineTo(Math.round(xOf(t)) + 0.5, hgt);
      g.stroke();
    }
    const stepV = gridStep(hi - lo, Math.max(3, Math.floor(hgt / 34)));
    g.font = '10px ui-monospace, Consolas, monospace';
    g.textBaseline = 'middle';
    for (let v = Math.ceil(lo / stepV) * stepV; v <= hi; v += stepV) {
      const y = Math.round(yOf(v)) + 0.5;
      g.strokeStyle = Math.abs(v) < stepV / 1000 ? 'rgba(255,255,255,0.22)' : 'rgba(255,255,255,0.06)';
      g.beginPath();
      g.moveTo(0, y);
      g.lineTo(w, y);
      g.stroke();
      g.fillStyle = 'rgba(200,204,214,0.55)';
      g.fillText((Math.round(v * 100) / 100) + (row.type === 'rotation' ? '°' : ''), left + 4, y - 7);
    }
    // Past the end.
    g.fillStyle = 'rgba(0,0,0,0.28)';
    g.fillRect(xOf(d.length), 0, w - xOf(d.length), hgt);

    // The part's other values, faint; then the picked one.
    for (const other of rowsFor(S.part)) {
      if (other.key === row.key || other.type !== row.type) continue;
      const pts = shownCurve(d, other.key);
      g.strokeStyle = AXIS_COLORS[other.axis];
      g.globalAlpha = 0.28;
      g.lineWidth = 1.2;
      g.beginPath();
      pts.forEach(([t, v], i) => (i ? g.lineTo(xOf(t), yOf(v)) : g.moveTo(xOf(t), yOf(v))));
      g.stroke();
      g.globalAlpha = 1;
    }
    const color = AXIS_COLORS[row.axis];
    // The picked segment, under the curve as a glow.
    const seg = pickedSegment(d);
    if (seg) {
      const pts = shownCurve(d, row.key).filter(([t]) => t >= seg.from - 1e-6 && t <= seg.to + 1e-6);
      g.strokeStyle = 'rgba(255,255,255,0.35)';
      g.lineWidth = 6;
      g.lineJoin = 'round';
      g.beginPath();
      pts.forEach(([t, v], i) => (i ? g.lineTo(xOf(t), yOf(v)) : g.moveTo(xOf(t), yOf(v))));
      g.stroke();
    }
    g.strokeStyle = color;
    g.lineWidth = 2;
    g.lineJoin = 'round';
    g.beginPath();
    shownCurve(d, row.key).forEach(([t, v], i) => (i ? g.lineTo(xOf(t), yOf(v)) : g.moveTo(xOf(t), yOf(v))));
    g.stroke();

    // The keyframes' points on the picked value.
    for (const k of d.keyframes) {
      const v = toShown(row.key, k.values[row.key] || 0);
      const x = xOf(k.at), y = yOf(v);
      const sel = S.keySel.has(k.at);
      g.save();
      g.translate(x, y);
      g.rotate(Math.PI / 4);
      g.fillStyle = sel ? '#ffffff' : color;
      g.strokeStyle = sel ? '#3d8ef0' : 'rgba(0,0,0,0.6)';
      g.lineWidth = sel ? 2 : 1;
      g.fillRect(-4.5, -4.5, 9, 9);
      g.strokeRect(-4.5, -4.5, 9, 9);
      g.restore();
    }
    // Which value this is, top left of the visible part.
    g.font = '600 11px "Segoe UI", system-ui, sans-serif';
    g.textBaseline = 'top';
    g.fillStyle = color;
    g.fillText(`${PART_NAMES[S.part]} · ${row.label}`, left + 4, 4);
    drawEaseBar(d, seg);
  }

  function pickedSegment(d) {
    if (!pickedSeg) return null;
    return segments(d).find(s => s.to === pickedSeg.to && s.from === pickedSeg.from) || null;
  }

  function drawEaseBar(d, seg) {
    easeBar.hidden = !seg;
    if (!seg) return;
    const k = d.keyframes[seg.b];
    const into = seg.b === 0 ? 'the loop back to the start' : `the keyframe at tick ${k.at}`;
    fill(easeBar, h('span', {class: 'label', text: `Easing into ${into}:`}),
      ...S.easings.map(key => h('button', {type: 'button', class: k.ease === key ? 'active' : '', text: EASING_NAMES[key] || key, title: EASING_HINTS[key] || '',
        on: {click: () => St.setEase(key, [k.at])}})));
  }

  // ---- pointer ----

  function local(ev) {
    const r = canvas.getBoundingClientRect();
    return {x: ev.clientX - r.left, y: ev.clientY - r.top};
  }

  function hitKey(d, row, p, yOf) {
    let best = null, bestD = HIT;
    for (const k of d.keyframes) {
      const dist = Math.hypot(xOf(k.at) - p.x, yOf(toShown(row.key, k.values[row.key] || 0)) - p.y);
      if (dist <= bestD) {
        best = k;
        bestD = dist;
      }
    }
    return best;
  }

  canvas.addEventListener('pointerdown', ev => {
    if (ev.button !== 0) return;
    const e = St.animEntry();
    const row = channel();
    if (!e || !row) return;
    ev.preventDefault();
    ev.stopPropagation();
    const d = e.draft;
    const {h: hgt} = size();
    const [lo, hi] = yRange(d, row);
    const yOf = v => hgt - 10 - (v - lo) / (hi - lo) * (hgt - 20);
    const vOf = y => lo + (hgt - 10 - y) / (hgt - 20) * (hi - lo);
    const p = local(ev);
    const k = hitKey(d, row, p, yOf);
    if (k) {
      St.pause();
      if (ev.shiftKey || ev.ctrlKey || ev.metaKey) St.selectKey(k.at, {toggle: true});
      else St.selectKey(k.at);
      St.setTick(k.at, false);
      pickedSeg = null;
      const base = d.keyframes.map(x => ({at: x.at, ease: x.ease, values: {...x.values}}));
      const startAt = k.at;
      const startV = toShown(row.key, k.values[row.key] || 0);
      frozen = [lo, hi];
      const gesture = 'drag:graph:' + performance.now();
      let lastAt = startAt;
      drag = {
        move: mv => {
          const q = local(mv);
          const free = mv.shiftKey;
          let v = startV + (vOf(q.y) - vOf(p.y));
          v = row.type === 'rotation' ? snapAngle(v, S.view.snapAngle, free) : snapPixels(v, free);
          let at = startAt;
          if (startAt !== 0) {
            at = Math.max(1, snapTick(startAt + (q.x - p.x) / geo.px, S.view.snapTicks, free));
            if (at !== startAt && base.some(x => x.at === at)) at = lastAt;
          }
          lastAt = at;
          const stored = fromShown(row.key, v);
          St.changeAnim(dd => {
            dd.keyframes = base.map(x => {
              if (x.at !== startAt) return {at: x.at, ease: x.ease, values: {...x.values}};
              const values = {...x.values};
              if (Math.abs(stored) > 1e-9) values[row.key] = stored;
              else delete values[row.key];
              return {at, ease: x.ease, values};
            });
            if (at > dd.length) dd.length = at;
          }, gesture);
          S.keySel = new Set([at]);
          S.selectedAt = at;
          St.setTick(at, false);
          draw();
        },
        up: () => {
          frozen = null;
          St.endGesture();
          St.emit('keys');
          draw();
        },
      };
    } else {
      const t = (p.x - geo.pad) / geo.px;
      const seg = segmentAt(d, t);
      // Near the curve: pick the segment (and its keyframe). Elsewhere: move the playhead.
      const near = seg && Math.abs(yOf(toShown(row.key, sampleAt(d, row.key, t))) - p.y) < 10;
      if (near) {
        pickedSeg = {from: seg.from, to: seg.to};
        if (seg.b !== 0) St.selectKey(d.keyframes[seg.b].at);
        draw();
        return;
      }
      pickedSeg = null;
      St.pause();
      St.setTick(Math.max(0, Math.min(d.length, snapTick(t, S.view.snapTicks, ev.shiftKey))));
      draw();
      return;
    }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp, {once: true});
  });

  function sampleAt(d, key, t) {
    const pts = curve(d, key, 0.25);
    let best = pts[0];
    for (const pt of pts) if (Math.abs(pt[0] - t) < Math.abs(best[0] - t)) best = pt;
    return best[1];
  }

  function onMove(ev) {
    if (drag) drag.move(ev);
  }

  function onUp(ev) {
    window.removeEventListener('pointermove', onMove);
    const d = drag;
    drag = null;
    if (d) d.up(ev);
  }

  canvas.addEventListener('pointermove', ev => {
    if (drag) return;
    const e = St.animEntry();
    const row = channel();
    if (!e || !row) return;
    const d = e.draft;
    const {h: hgt} = size();
    const [lo, hi] = yRange(d, row);
    const yOf = v => hgt - 10 - (v - lo) / (hi - lo) * (hgt - 20);
    const k = hitKey(d, row, local(ev), yOf);
    canvas.style.cursor = k ? (k.at === 0 ? 'ns-resize' : 'move') : 'crosshair';
    canvas.title = k ? `Tick ${k.at}: ${row.label} ${Math.round(toShown(row.key, k.values[row.key] || 0) * 100) / 100} (drag; Shift: no snapping)` : 'Click the curve to pick a segment and set its easing; elsewhere to move the playhead';
  });

  return {
    /** Draws into host (the timeline's graph cell) at the timeline's scale. */
    render(into, {px, pad, width, scrollEl, overlay}) {
      geo = {px, pad, width};
      scroller = scrollEl || scroller;
      if (host !== into) {
        host = into;
        fill(host, canvas);
      }
      if (overlay && easeBar.parentNode !== overlay) overlay.append(easeBar, note);
      if (pickedSeg) {
        const e = St.animEntry();
        if (!e || !pickedSegment(e.draft)) pickedSeg = null;
      }
      draw();
    },
    tick() {},
    redraw: draw,
    /** Hides the overlays (the graph is off). */
    hide() {
      easeBar.hidden = true;
      note.hidden = true;
    },
    rowOf,
  };
}
