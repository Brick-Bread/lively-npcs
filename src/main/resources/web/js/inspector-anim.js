// The inspector in animation mode: the animation's name and badges, what the playhead is on, the
// part chips, and every channel of the selected part (or all, by part) as a slider and a number.

import {S, on} from './store.js';
import * as St from './store.js';
import * as Act from './actions.js';
import {GROUPS, channelLabel, channelAxis, AXIS_COLORS} from './labels.js';
import {h, fill, icon, button, toggle} from './ui.js';
import {DEFAULT_CHANNELS} from './anim.js';

const FALLBACK = {pixels: false, min: -180, max: 180};

export function mountAnimInspector(root) {
  const rows = new Map();
  let builtFor = null;
  let activeKey = null;
  let nameInput = null, badgesBox = null, remoteBox = null, chipsBox = null;

  function info(key) {
    return S.channelInfo[key] || {key, ...FALLBACK, pixels: key === 'root-x' || key === 'root-y' || key === 'root-z' || key === 'shoulders'};
  }

  function fmt(v, pixels) {
    const r = Math.round(v * (pixels ? 100 : 10)) / (pixels ? 100 : 10);
    return String(Object.is(r, -0) ? 0 : r);
  }

  function setFill(slider, v, c) {
    const span = c.max - c.min || 1;
    const zero = Math.max(0, Math.min(100, (0 - c.min) / span * 100));
    const at = Math.max(0, Math.min(100, (v - c.min) / span * 100));
    slider.style.setProperty('--zero', zero + '%');
    slider.style.setProperty('--fill', at + '%');
    slider.classList.toggle('neg', v < 0);
  }

  function channelRow(key) {
    const c = info(key);
    const axis = channelAxis(key);
    const step = c.pixels ? 0.25 : 1;
    const slider = h('input', {class: 'slider', type: 'range', min: c.min, max: c.max, step, attrs: {'aria-label': channelLabel(key)}});
    const num = h('input', {class: 'input', type: 'number', step: c.pixels ? 0.25 : 1, attrs: {'aria-label': channelLabel(key) + ' value'}});
    const reset = button(null, 'reset', {class: 'btn-xs btn-ghost chan-reset', title: 'Back to 0 (rest)', onClick: () => St.setValue(key, 0, null)});
    const row = h('div', {class: 'chan', dataset: {key}},
      h('div', {class: 'chan-label'}, h('span', {class: 'axis', style: {background: axis ? AXIS_COLORS[axis] : 'var(--text-3)'}}),
        h('span', {class: 'ellipsis', text: channelLabel(key)}), h('span', {class: 'unit', text: c.pixels ? 'px' : '°'})),
      h('div', {class: 'chan-num'}, num),
      h('div', {class: 'chan-reset'}, reset),
      h('div', {class: 'chan-slider'}, slider));
    slider.addEventListener('pointerdown', () => { activeKey = key; });
    slider.addEventListener('input', () => {
      activeKey = key;
      const v = Number(slider.value);
      setFill(slider, v, c);
      num.value = fmt(v, c.pixels);
      St.setValue(key, v, 'slider:' + key);
    });
    slider.addEventListener('change', () => {
      activeKey = null;
      St.endGesture();
    });
    slider.addEventListener('dblclick', () => St.setValue(key, 0, null));
    num.addEventListener('change', () => {
      const v = Number(num.value);
      if (Number.isFinite(v)) St.setValue(key, v, null);
      else num.value = fmt(St.currentValues()[key] || 0, c.pixels);
    });
    num.addEventListener('keydown', e => {
      if (e.key === 'Enter') num.blur();
      if (e.key === 'Escape') {
        num.value = fmt(St.currentValues()[key] || 0, c.pixels);
        num.blur();
      }
    });
    rows.set(key, {row, slider, num, c});
    return row;
  }

  function build() {
    rows.clear();
    const e = St.animEntry();
    builtFor = e ? e.id + '|' + S.part : null;
    if (!e) {
      fill(root, h('div', {class: 'insp-empty'}, icon('film', 40), h('div', {text: 'Pick an animation on the left to pose it.'})));
      return;
    }
    const d = e.draft;
    nameInput = h('input', {class: 'input', value: d.name, attrs: {'aria-label': 'Name', spellcheck: 'false'},
      on: {input: () => St.changeAnim(x => { x.name = nameInput.value; }, 'name'), change: () => St.endGesture()}});
    badgesBox = h('div', {class: 'row row-wrap'});
    remoteBox = h('div');
    const status = h('div', {class: 'pose-status', id: 'pose-status'});
    const name = nameInput, badges = badgesBox, remote = remoteBox;

    chipsBox = h('div', {class: 'part-chips'});
    const chips = chipsBox;

    const groups = [];
    for (const g of GROUPS) {
      if (S.part && S.part !== g.part) continue;
      const keys = g.channels.filter(k => S.channelKeys.includes(k) || DEFAULT_CHANNELS.includes(k));
      groups.push(h('div', {class: 'group-title'}, h('span', {text: g.name}), h('div', {class: 'line'})));
      for (const key of keys) groups.push(channelRow(key));
    }

    const layers = toggle('Breathing and weight shift', S.layers, v => { S.layers = v; St.emit('view'); },
      'Show the breathing and weight shift every NPC adds on top of its animation');
    fill(root,
      h('div', {class: 'insp-head'}, h('div', {class: 'insp-title'}, name), badges, remote, status),
      h('div', {class: 'section'}, h('div', {class: 'section-body', style: {paddingTop: '12px'}}, chips, ...groups)),
      h('div', {class: 'section'}, h('div', {class: 'section-body', style: {paddingTop: '12px'}}, layers.el,
        h('div', {class: 'hint', text: 'Only in this view: in game every NPC breathes and shifts its weight on its own.'}))));
    update();
  }

  function chip(part, label) {
    const moved = part && St.animEntry() && St.animEntry().draft.keyframes.some(k => (GROUPS.find(g => g.part === part).channels.some(c => k.values[c])));
    return h('button', {type: 'button', class: ['chip', S.part === part && 'active'], on: {click: () => St.selectPart(part)}},
      label, moved ? h('span', {class: 'moved', title: 'Moves in this animation'}) : null);
  }

  function updateHead(e) {
    const d = e.draft;
    if (nameInput && document.activeElement !== nameInput && nameInput.value !== d.name) nameInput.value = d.name;
    const preset = e.base && e.base.builtin;
    const edited = e.base && e.base.edited;
    if (badgesBox) {
      fill(badgesBox,
        h('span', {class: 'insp-id', text: d.id}),
        preset ? h('span', {class: 'badge badge-preset', text: 'preset', title: 'Comes with Lively NPCs'}) : null,
        edited ? h('span', {class: 'badge badge-edited', text: 'edited', title: 'Your saved version replaces the preset on this server'}) : null,
        e.isNew ? h('span', {class: 'badge badge-unsaved', text: 'new'}) : e.dirty ? h('span', {class: 'badge badge-unsaved', text: 'unsaved'}) : null,
        h('div', {class: 'spacer'}),
        preset && edited ? button('Restore preset', 'restore', {class: 'btn-xs', title: 'Bring back the preset as it ships', onClick: () => Act.deleteAnim(e.id)}) : null);
    }
    if (chipsBox) fill(chipsBox, chip(null, 'All parts'), ...GROUPS.map(g => chip(g.part, g.name)));
    if (remoteBox) {
      fill(remoteBox, e.remote ? h('div', {class: 'banner banner-warn'}, icon('warn', 15), h('div', {},
        h('div', {text: e.remote === 'deleted' ? 'This animation was deleted in game. Saving brings it back.'
          : e.remote === 'exists' ? 'An animation with this id was made in game meanwhile. Saving replaces it with yours.'
            : 'This animation was changed in game since you started. Saving replaces that version with yours.'}),
        h('div', {class: 'actions'}, button(e.remote === 'deleted' ? 'Drop mine' : 'Use theirs', null, {class: 'btn-xs', onClick: () => St.takeRemote('anim', e.id)})))) : null);
    }
  }

  let lastStatus = '';

  /** The sliders, numbers and the status line (cheap: runs every frame while playing). */
  function updateValues() {
    const e = St.animEntry();
    if (!e) return;
    const values = St.currentValues();
    for (const [key, r] of rows) {
      const v = values[key] || 0;
      r.row.classList.toggle('nonzero', Math.abs(v) > 1e-6);
      if (activeKey !== key) {
        r.slider.value = v;
        setFill(r.slider, v, r.c);
      }
      if (document.activeElement !== r.num) r.num.value = fmt(v, r.c.pixels);
    }
    const status = root.querySelector('#pose-status');
    if (status) {
      const t = Math.round(S.tick);
      const onKey = !S.playing && St.keyUnderPlayhead() >= 0;
      const cls = 'pose-status' + (S.pending ? ' pending' : onKey ? ' on-key' : '');
      let text;
      if (S.playing) text = 'Playing… pause to pose.';
      else if (S.pending) text = `Tick ${t}: this pose is not kept yet. Press K (Keyframe) to keep it.`;
      else if (onKey) text = t === 0 ? 'On the first keyframe (tick 0): changes go into it.' : `On the keyframe at tick ${t}: changes go into it.`;
      else if (S.autoKey) text = `Tick ${t} is between keyframes: posing adds a keyframe here.`;
      else text = `Tick ${t} is between keyframes: pose, then press K to keep it.`;
      if (cls + text !== lastStatus) {
        lastStatus = cls + text;
        status.className = cls;
        fill(status, icon(S.pending ? 'info' : onKey ? 'key' : 'clock', 15), h('span', {text}));
      }
    }
  }

  /** Everything but the structure: header, chips, values. */
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
  on('part', () => { builtFor = '?'; rebuild(); });
  on('lists', () => { if (S.mode === 'animations') rebuild(); });
  on('anim', ev => {
    if (S.mode !== 'animations') return;
    if (ev && ev.remote) builtFor = '?';
    rebuild();
  });
  on('tick', () => { if (S.mode === 'animations') updateValues(); });
  on('play', () => { if (S.mode === 'animations') updateValues(); });
  return {build: () => { builtFor = '?'; rebuild(); }};
}
