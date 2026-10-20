// The right side in Animate mode. Animation: name, id and badges, length, loop, glance, save and
// revert. Keyframe: what the playhead is on, the keyframe's interpolation, and the selected part's values
// as Blockbench names them (Rotation X/Y/Z, Position X/Y/Z, with Blockbench's signs; hover a name for
// what it does), each a number you can type or drag sideways, and a slider.

import {S, on} from './store.js';
import * as St from './store.js';
import * as Act from './actions.js';
import * as A from './anim.js';
import {GROUPS, PART_NAMES, AXIS_COLORS, EASING_NAMES, EASING_HINTS} from './labels.js';
import {rowsFor, toShown, fromShown, shownRange} from './axes.js';
import {snapAngle, snapPixels} from './snap.js';
import {h, fill, icon, button, toggle, checkbox, toast, confirmDialog} from './ui.js';

const FALLBACK = {pixels: false, min: -180, max: 180};

export function mountAnimInspector(root) {
  const rows = new Map();
  let builtFor = null;
  let activeKey = null;

  // ---- the Animation panel ----
  const nameInput = h('input', {class: 'input', attrs: {'aria-label': 'Name', spellcheck: 'false'},
    on: {input: () => St.changeAnim(x => { x.name = nameInput.value; }, 'name'), change: () => St.endGesture()}});
  const idLine = h('div', {class: 'row row-wrap'});
  const remoteBox = h('div');
  const lengthIn = h('input', {class: 'input', type: 'number', min: 1, max: 24000, step: 1, title: 'Length in ticks (20 a second)',
    on: {change: () => setLength(lengthIn.value), keydown: e => { if (e.key === 'Enter') lengthIn.blur(); }}});
  const lengthSec = h('span', {class: 'muted small'});
  const loopSel = h('select', {class: 'select', title: 'Loop: plays round and round (a preview, or by command until stopped)',
    on: {change: () => St.changeAnim(d => { d.loop = loopSel.value === 'loop'; })}},
  h('option', {value: 'once', text: 'Play once'}), h('option', {value: 'loop', text: 'Loop'}));
  const glance = checkbox('Glance', false, v => St.changeAnim(d => { d.glance = v; }),
    'Only for players the NPC is not looking at (it turns its head away). Your live preview still shows it.');
  const warn = h('button', {type: 'button', class: 'badge badge-warn warn-badge', on: {click: () => fixRest()},
    title: 'A one-shot animation should end with every value at 0 so the NPC finishes in its own pose. Click to add a rest keyframe at the end.'},
  icon('warn', 11), 'Does not end at rest');
  const saveState = h('span', {class: 'save-state'});
  const saveBtn = button('Save', 'save', {class: 'btn-sm btn-primary', title: 'Save (Ctrl+S)', onClick: () => Act.saveCurrent()});
  const revertBtn = button(null, 'revert', {class: 'btn-sm btn-ghost', title: 'Revert: drop the unsaved changes', onClick: () => Act.revertCurrent()});
  const animPanel = h('div', {class: 'panel', style: {flex: 'none'}},
    h('div', {class: 'panel-head'}, h('span', {class: 'title', text: 'Animation'})),
    h('div', {class: 'panel-pad'},
      h('div', {class: 'prop-grid'},
        h('label', {text: 'Name'}), nameInput,
        h('label', {text: 'Id'}), idLine,
        h('label', {text: 'Length'}), h('div', {class: 'prop-row'}, lengthIn, h('span', {class: 'muted small', text: 'ticks'}), lengthSec),
        h('label', {text: 'Loop mode'}), h('div', {class: 'prop-row'}, loopSel, glance.el)),
      remoteBox,
      h('div', {class: 'row'}, warn, h('div', {class: 'spacer'}), saveState, revertBtn, saveBtn)));

  // ---- the Keyframe panel ----
  const status = h('div', {class: 'pose-status'});
  const easeSel = h('select', {class: 'select', on: {change: () => St.setEase(easeSel.value)}});
  const addBtn = button(null, 'keyplus', {class: 'btn-xs btn-ghost', title: 'Add a keyframe at the playhead from the pose you see (K)', onClick: () => {
    if (St.addKeyframe() === 'exists') toast('There is a keyframe here already: it is selected.', 'info', 1800);
  }});
  const delBtn = button(null, 'trash', {class: 'btn-xs btn-ghost', title: 'Delete the selected keyframes (Delete)', onClick: () => {
    const r = St.deleteKeyframe();
    if (r === 'first') toast('The first keyframe always stays at 0. Change its pose instead.', 'info');
  }});
  const values = h('div', {class: 'panel-pad', style: {gap: '7px'}});
  const keyPanel = h('div', {class: 'panel grow-panel'},
    h('div', {class: 'panel-head'}, h('span', {class: 'title', text: 'Keyframe'}), addBtn, delBtn),
    h('div', {class: 'panel-body'},
      h('div', {class: 'panel-pad', style: {paddingBottom: '2px'}}, status,
        h('div', {class: 'prop-grid'}, h('label', {text: 'Interpolation', title: 'How the values move into this keyframe from the one before'}), easeSel)),
      values));
  const empty = h('div', {class: 'insp-empty'}, icon('film', 36), h('div', {text: 'Pick an animation on the left to pose it.'}));
  fill(root, animPanel, keyPanel, empty);

  function info(key) {
    return S.channelInfo[key] || {key, ...FALLBACK, pixels: key === 'root-x' || key === 'root-y' || key === 'root-z' || key === 'shoulders'};
  }

  function fmt(v, pixels) {
    const r = Math.round(v * (pixels ? 100 : 10)) / (pixels ? 100 : 10);
    return String(Object.is(r, -0) ? 0 : r);
  }

  function setFill(slider, v, lo, hi) {
    const span = hi - lo || 1;
    const zero = Math.max(0, Math.min(100, (0 - lo) / span * 100));
    const at = Math.max(0, Math.min(100, (v - lo) / span * 100));
    slider.style.setProperty('--zero', zero + '%');
    slider.style.setProperty('--fill', at + '%');
    slider.classList.toggle('neg', v < 0);
  }

  function snapped(v, pixels, free) {
    return pixels ? snapPixels(v, free) : snapAngle(v, S.view.snapAngle, free);
  }

  /** One value: shown with Blockbench's sign, stored with the channel's. */
  function valueRow(row) {
    const key = row.key;
    const c = info(key);
    const [lo, hi] = shownRange(key, c.min, c.max);
    const slider = h('input', {class: 'slider', type: 'range', min: lo, max: hi, step: c.pixels ? 0.05 : 0.1, attrs: {'aria-label': row.label}});
    const num = h('input', {class: 'input', type: 'text', attrs: {'aria-label': row.label + ' value', inputmode: 'decimal', spellcheck: 'false'},
      title: 'Type a value, or drag sideways (Shift: no snapping)'});
    const reset = button(null, 'reset', {class: 'btn-xs btn-ghost', title: 'Back to 0 (rest)', onClick: () => St.setValue(key, 0, null)});
    const el = h('div', {class: 'chan', dataset: {key}},
      h('div', {class: 'chan-axis', style: {background: AXIS_COLORS[row.axis]}, text: row.axis.toUpperCase()}),
      h('div', {class: 'chan-label', title: row.hint + (c.pixels ? ' (pixels)' : ' (degrees)')}, row.label, h('span', {class: 'unit', text: c.pixels ? 'px' : '°'})),
      h('div', {class: 'chan-num'}, num),
      h('div', {class: 'chan-reset'}, reset),
      h('div', {class: 'chan-slider'}, slider));
    const store = (shown, gesture) => St.setValue(key, fromShown(key, shown), gesture);
    slider.addEventListener('pointerdown', () => { activeKey = key; });
    let sliderShift = false;
    slider.addEventListener('pointermove', e => { sliderShift = e.shiftKey; });
    slider.addEventListener('input', () => {
      activeKey = key;
      const v = snapped(Number(slider.value), c.pixels, sliderShift);
      setFill(slider, v, lo, hi);
      num.value = fmt(v, c.pixels);
      store(v, 'slider:' + key);
    });
    slider.addEventListener('change', () => {
      activeKey = null;
      St.endGesture();
    });
    slider.addEventListener('dblclick', () => St.setValue(key, 0, null));
    const commit = () => {
      const v = Number(String(num.value).replace(',', '.'));
      if (Number.isFinite(v)) store(v, null);
      else num.value = fmt(toShown(key, St.currentValues()[key] || 0), c.pixels);
    };
    num.addEventListener('change', commit);
    num.addEventListener('keydown', e => {
      if (e.key === 'Enter') num.blur();
      if (e.key === 'Escape') {
        num.value = fmt(toShown(key, St.currentValues()[key] || 0), c.pixels);
        num.blur();
      }
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        e.preventDefault();
        const step = (c.pixels ? 0.25 : 1) * (e.shiftKey ? 10 : 1) * (e.key === 'ArrowUp' ? 1 : -1);
        store(toShown(key, St.currentValues()[key] || 0) + step, 'arrows:' + key);
      }
    });
    // Drag sideways on the number to change it (a click without moving types instead).
    num.addEventListener('pointerdown', e => {
      if (document.activeElement === num || e.button !== 0) return;
      e.preventDefault();
      const x0 = e.clientX;
      const start = toShown(key, St.currentValues()[key] || 0);
      const gesture = 'drag:num:' + key + ':' + performance.now();
      let moved = false;
      num.setPointerCapture(e.pointerId);
      const move = ev => {
        const dx = ev.clientX - x0;
        if (!moved && Math.abs(dx) < 3) return;
        moved = true;
        activeKey = key;
        const v = snapped(Math.max(lo, Math.min(hi, start + dx * (c.pixels ? 0.05 : 0.5))), c.pixels, ev.shiftKey);
        store(v, gesture);
      };
      const up = () => {
        num.removeEventListener('pointermove', move);
        num.removeEventListener('pointerup', up);
        num.removeEventListener('pointercancel', up);
        activeKey = null;
        if (moved) St.endGesture();
        else {
          num.focus();
          num.select();
        }
      };
      num.addEventListener('pointermove', move);
      num.addEventListener('pointerup', up);
      num.addEventListener('pointercancel', up);
    });
    rows.set(row.part + '|' + key, {el, slider, num, c, key, lo, hi});
    return el;
  }

  function build() {
    rows.clear();
    const e = St.animEntry();
    builtFor = e ? e.id + '|' + S.part : null;
    animPanel.hidden = keyPanel.hidden = !e;
    empty.hidden = !!e;
    if (!e) return;
    const parts = S.part ? [S.part] : GROUPS.map(g => g.part);
    const out = [];
    for (const part of parts) {
      out.push(h('div', {class: 'group-title'}, h('span', {text: PART_NAMES[part]}), h('div', {class: 'line'})));
      const list = rowsFor(part).filter(r => !S.channelKeys.length || S.channelKeys.includes(r.key));
      // Rotations, then positions, each under a small heading as Blockbench groups them.
      let type = null;
      for (const r of list) {
        if (r.type !== type && list.some(x => x.type !== r.type)) {
          type = r.type;
        }
        out.push(valueRow(r));
      }
    }
    if (!S.part) out.unshift(h('div', {class: 'hint', text: 'Pick a part in the Outliner, the 3D view or the timeline to see only its values.'}));
    fill(values, out);
    update();
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

  function updateHead(e) {
    const d = e.draft;
    if (document.activeElement !== nameInput && nameInput.value !== d.name) nameInput.value = d.name;
    const preset = e.base && e.base.builtin;
    const edited = e.base && e.base.edited;
    fill(idLine,
      h('span', {class: 'insp-id', text: d.id}),
      preset ? h('span', {class: 'badge badge-preset', text: 'preset', title: 'Comes with Lively NPCs'}) : null,
      edited ? h('span', {class: 'badge badge-edited', text: 'edited', title: 'Your saved version replaces the preset on this server'}) : null,
      e.isNew ? h('span', {class: 'badge badge-unsaved', text: 'new'}) : null,
      preset && edited ? button('Restore preset', 'restore', {class: 'btn-xs btn-ghost', title: 'Bring back the preset as it ships', onClick: () => Act.deleteAnim(e.id)}) : null);
    if (document.activeElement !== lengthIn) lengthIn.value = d.length;
    lengthSec.textContent = '(' + A.seconds(d.length) + ')';
    loopSel.value = d.loop ? 'loop' : 'once';
    glance.input.checked = d.glance;
    warn.hidden = d.loop || A.endsAtRest(d, S.channelKeys);
    saveState.textContent = e.isNew ? 'Not saved yet' : e.dirty ? 'Unsaved' : 'Saved';
    saveBtn.disabled = !e.dirty && !e.isNew;
    revertBtn.disabled = !e.dirty;
    fill(remoteBox, e.remote ? h('div', {class: 'banner banner-warn'}, icon('warn', 15), h('div', {},
      h('div', {text: e.remote === 'deleted' ? 'This animation was deleted in game. Saving brings it back.'
        : e.remote === 'exists' ? 'An animation with this id was made in game meanwhile. Saving replaces it with yours.'
          : 'This animation was changed in game since you started. Saving replaces that version with yours.'}),
      h('div', {class: 'actions'}, button(e.remote === 'deleted' ? 'Drop mine' : 'Use theirs', null, {class: 'btn-xs', onClick: () => St.takeRemote('anim', e.id)})))) : null);
  }

  let lastStatus = '';

  /** The values, the status line and the interpolation (cheap: runs every frame while playing). */
  function updateValues() {
    const e = St.animEntry();
    if (!e) return;
    const now = St.currentValues();
    for (const r of rows.values()) {
      const v = toShown(r.key, now[r.key] || 0);
      r.el.classList.toggle('nonzero', Math.abs(v) > 1e-6);
      if (activeKey !== r.key) {
        r.slider.value = v;
        setFill(r.slider, v, r.lo, r.hi);
      }
      if (document.activeElement !== r.num) r.num.value = fmt(v, r.c.pixels);
    }
    const t = Math.round(S.tick);
    const onKey = !S.playing && St.keyUnderPlayhead() >= 0;
    const cls = 'pose-status' + (S.pending ? ' pending' : onKey ? ' on-key' : '');
    let text;
    if (S.playing) text = 'Playing… pause to pose.';
    else if (S.pending) text = `Tick ${t}: this pose is not kept yet. Press K to keep it.`;
    else if (onKey) text = t === 0 ? 'On the first keyframe (tick 0): changes go into it.' : `On the keyframe at tick ${t} (${A.seconds(t)}): changes go into it.`;
    else if (S.autoKey) text = `Tick ${t} (${A.seconds(t)}) is between keyframes: posing adds one here.`;
    else text = `Tick ${t} is between keyframes: pose, then press K to keep it.`;
    if (cls + text !== lastStatus) {
      lastStatus = cls + text;
      status.className = cls;
      fill(status, icon(S.pending ? 'info' : onKey ? 'diamond' : 'clock', 14), h('span', {text}));
    }
    // The interpolation of the selected keyframes (or the one under the playhead).
    const ticks = S.keySel.size ? [...S.keySel] : onKey ? [t] : [];
    const keys = e.draft.keyframes.filter(k => ticks.includes(k.at) && (k.at !== 0 || e.draft.loop));
    if (document.activeElement !== easeSel) {
      if (easeSel.options.length !== S.easings.length) {
        fill(easeSel, ...S.easings.map(k => h('option', {value: k, text: EASING_NAMES[k] || k, title: EASING_HINTS[k] || ''})));
      }
      const eases = new Set(keys.map(k => k.ease));
      easeSel.value = eases.size === 1 ? [...eases][0] : 'linear';
      easeSel.disabled = !keys.length;
      easeSel.title = !keys.length ? (ticks.includes(0) ? 'The first keyframe has nothing before it (in a loop it sets the glide back to the start)' : 'Select a keyframe to set how it is reached')
        : (EASING_HINTS[easeSel.value] || '') + (keys.length > 1 ? ` (${keys.length} keyframes)` : '');
    }
    delBtn.disabled = !(S.keySel.size && [...S.keySel].some(x => x !== 0));
  }

  function update() {
    const e = St.animEntry();
    if (!e) return;
    updateHead(e);
    updateValues();
  }

  const rebuild = () => {
    if (S.mode !== 'animations') return;
    const e = St.animEntry();
    const want = e ? e.id + '|' + S.part : null;
    if (want !== builtFor) {
      lastStatus = '';
      build();
    } else {
      update();
    }
  };
  on('select', () => { builtFor = '?'; rebuild(); });
  on('part', () => rebuild());
  on('lists', () => rebuild());
  on('anim', ev => {
    if (ev && ev.remote) builtFor = '?';
    rebuild();
  });
  on('keys', () => { if (S.mode === 'animations') updateValues(); });
  on('tick', () => { if (S.mode === 'animations') updateValues(); });
  on('play', () => { if (S.mode === 'animations') updateValues(); });
  void toggle;
  return {build: () => { builtFor = '?'; rebuild(); }};
}
