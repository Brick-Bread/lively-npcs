// Small DOM helpers: element builder, icons, toasts and dialogs. Text always goes in as text (never
// as HTML), so names and plate lines from npcs.yml cannot inject anything.

import {MATERIAL_ICONS} from '../vendor/material-icons/material-icons.js';

/**
 * h('div', {class: 'x', on: {click}, attrs: {title}}, child, 'text', [more]) builds an element.
 * Props: class, text, style (object), dataset, on (events), attrs (attributes), and any element
 * property (value, checked, disabled, type, placeholder, ...).
 */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null) continue;
      if (k === 'class') el.className = Array.isArray(v) ? v.filter(Boolean).join(' ') : v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'style') Object.assign(el.style, v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k === 'on') for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (k === 'attrs') for (const [a, av] of Object.entries(v)) { if (av !== false && av !== null && av !== undefined) el.setAttribute(a, av === true ? '' : av); }
      else el[k] = v;
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

/** Replaces an element's children. */
export function fill(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// Icons: Google's Material Icons (filled, vendor/material-icons, Apache-2.0) under the editor's own
// names, plus a few shapes of our own (keyframe diamonds), stroke-drawn.
const NAMES = {
  play: 'play_arrow', pause: 'pause', stop: 'stop', start: 'skip_previous', end: 'skip_next', prev: 'chevron_left', next: 'chevron_right',
  plus: 'add', trash: 'delete', copy: 'content_copy', paste: 'content_paste', mirror: 'flip', undo: 'undo', redo: 'redo',
  save: 'save', search: 'search', user: 'person', userplus: 'person_add', film: 'movie', x: 'close', check: 'check', warn: 'warning',
  info: 'info', upload: 'file_upload', download: 'file_download', link: 'link', chevron: 'expand_more', chevronup: 'expand_less',
  caret: 'arrow_right', target: 'my_location', broadcast: 'wifi_tethering', eye: 'visibility', camera: 'videocam', wind: 'air',
  restore: 'settings_backup_restore', revert: 'history', dots: 'more_horiz', grip: 'drag_indicator', up: 'keyboard_arrow_up',
  down: 'keyboard_arrow_down', clock: 'schedule', keyboard: 'keyboard', sparkle: 'auto_awesome', hand: 'pan_tool', loop: 'loop',
  repeat: 'repeat', move: 'open_with', reset: 'refresh', body: 'accessibility', folder: 'folder', folderopen: 'folder_open',
  grid: 'grid_on', help: 'help_outline', edit: 'edit', layers: 'layers', graph: 'show_chart', ruler: 'straighten', tune: 'tune',
  focus: 'filter_center_focus', zoomin: 'zoom_in', zoomout: 'zoom_out', expandall: 'unfold_more', collapseall: 'unfold_less',
  addcircle: 'add_circle_outline', remove: 'remove',
};
const OWN = {
  keyplus: '<path d="M9 4.5l5 5-5 5-5-5z" fill="currentColor" stroke="none"/><path d="M17 13v7M13.5 16.5h7"/>',
  key: '<path d="M12 4l8 8-8 8-8-8z"/>',
  diamond: '<path d="M12 3.5l8.5 8.5-8.5 8.5L3.5 12z" fill="currentColor" stroke="none"/>',
  logo: '<rect x="8" y="2.5" width="8" height="8" rx="1.5"/><rect x="8" y="11.5" width="8" height="10" rx="1.5"/>',
};

/** An icon as an SVG element. */
export function icon(name, size = 16, extraClass = '') {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('aria-hidden', 'true');
  if (extraClass) svg.setAttribute('class', extraClass);
  const material = MATERIAL_ICONS[NAMES[name] || name];
  if (material) {
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', material);
    path.setAttribute('fill', 'currentColor');
    svg.append(path);
    return svg;
  }
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.8');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  // The markup is this file's own constant, never user text.
  svg.innerHTML = OWN[name] || '';
  return svg;
}

/** A button: label (text or null), icon name, options {class, title, onClick, disabled, kbd}. */
export function button(label, iconName, opts = {}) {
  const b = h('button', {
    type: 'button',
    class: ['btn', opts.class, !label && 'btn-icon'],
    title: opts.title,
    disabled: !!opts.disabled,
    attrs: {'aria-label': opts.title || label},
    on: opts.onClick ? {click: opts.onClick} : undefined,
  }, iconName ? icon(iconName, opts.iconSize || 15) : null, label ? h('span', {text: label}) : null,
  opts.kbd ? h('span', {class: 'kbd', text: opts.kbd}) : null);
  return b;
}

/** A labelled switch; returns {el, input}. */
export function toggle(label, checked, onChange, title) {
  const input = h('input', {type: 'checkbox', checked, on: {change: () => onChange(input.checked)}});
  const el = h('label', {class: 'switch', title}, input, h('span', {class: 'track'}), label ? h('span', {text: label}) : null);
  return {el, input};
}

/** A labelled checkbox; returns {el, input}. */
export function checkbox(label, checked, onChange, title) {
  const input = h('input', {type: 'checkbox', checked, on: {change: () => onChange(input.checked)}});
  const el = h('label', {class: 'check', title}, input, h('span', {class: 'box'}), label ? h('span', {text: label}) : null);
  return {el, input};
}

// ---- toasts ----

export function toast(message, kind = 'info', ms = 4200) {
  const root = document.getElementById('toasts');
  const icons = {ok: 'check', error: 'warn', warn: 'warn', info: 'info'};
  const el = h('div', {class: ['toast', kind], role: kind === 'error' ? 'alert' : 'status'}, icon(icons[kind] || 'info', 16), h('div', {text: message}));
  root.append(el);
  const close = () => {
    el.classList.add('out');
    setTimeout(() => el.remove(), 220);
  };
  el.addEventListener('click', close);
  setTimeout(close, kind === 'error' ? Math.max(ms, 7000) : ms);
  while (root.children.length > 4) root.firstChild.remove();
}

// ---- dialogs ----

/**
 * A dialog. actions: [{label, kind ('primary' | 'danger' | ''), value, disabled}]; resolves with the
 * chosen action's value (undefined when closed). body may be a Node or text. onOpen(el) after showing.
 */
export function dialog({title, body, actions = [{label: 'OK', kind: 'primary', value: true}], onOpen, enterValue}) {
  return new Promise(resolve => {
    const root = document.getElementById('modal-root');
    const previous = document.activeElement;
    let done = false;
    const finish = value => {
      if (done) return;
      done = true;
      back.remove();
      document.removeEventListener('keydown', onKey, true);
      if (previous && previous.focus) previous.focus();
      resolve(value);
    };
    const buttons = actions.map(a => h('button', {
      type: 'button',
      class: ['btn', a.kind === 'primary' && 'btn-primary', a.kind === 'danger' && 'btn-danger-solid'],
      text: a.label,
      disabled: !!a.disabled,
      on: {click: () => finish(typeof a.value === 'function' ? a.value() : a.value)},
    }));
    const modal = h('div', {class: 'modal', role: 'dialog', attrs: {'aria-modal': 'true', 'aria-label': title}},
      h('div', {class: 'modal-head', text: title}),
      h('div', {class: 'modal-body'}, typeof body === 'string' ? h('p', {text: body, style: {margin: 0}}) : body),
      h('div', {class: 'modal-foot'}, buttons));
    const back = h('div', {class: 'modal-back', on: {mousedown: e => { if (e.target === back) finish(undefined); }}}, modal);
    const onKey = e => {
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        finish(undefined);
      } else if (e.key === 'Enter' && enterValue !== undefined && !(e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault();
        e.stopPropagation();
        const v = typeof enterValue === 'function' ? enterValue() : enterValue;
        if (v !== undefined) finish(v);
      } else {
        e.stopPropagation();
      }
    };
    document.addEventListener('keydown', onKey, true);
    root.append(back);
    dialog.buttons = buttons;
    if (onOpen) onOpen(modal, buttons, finish);
    else (buttons.find(b => b.classList.contains('btn-primary')) || buttons[0])?.focus();
  });
}

/** Asks yes or no; resolves true for yes. */
export async function confirmDialog({title, message, confirm = 'OK', danger = false}) {
  return (await dialog({
    title,
    body: message,
    actions: [{label: 'Cancel', value: false}, {label: confirm, kind: danger ? 'danger' : 'primary', value: true}],
  })) === true;
}

/**
 * Asks for a line of text. validate(value) returns an error message or ''. Resolves with the text,
 * or null when cancelled.
 */
export async function promptDialog({title, label, value = '', placeholder = '', hint = '', confirm = 'OK', validate = () => '', mono = false}) {
  const input = h('input', {class: ['input', mono && 'mono-input'], value, placeholder, attrs: {spellcheck: 'false', autocomplete: 'off'}});
  const error = h('div', {class: 'error-text'});
  const body = h('div', {class: 'field'}, label ? h('label', {text: label}) : null, input, hint ? h('div', {class: 'hint', text: hint}) : null, error);
  const check = () => {
    const msg = validate(input.value);
    error.textContent = input.value ? msg : '';
    input.classList.toggle('invalid', !!(input.value && msg));
    if (dialog.buttons) dialog.buttons[1].disabled = !!msg;
    return !msg;
  };
  input.addEventListener('input', check);
  const result = await dialog({
    title,
    body,
    actions: [{label: 'Cancel', value: null}, {label: confirm, kind: 'primary', value: () => input.value}],
    enterValue: () => (check() ? input.value : undefined),
    onOpen: () => {
      check();
      input.focus();
      input.select();
    },
  });
  return result === undefined ? null : result;
}

// ---- little helpers ----

export function debounce(fn, ms) {
  let t = 0;
  const d = (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
  d.cancel = () => clearTimeout(t);
  d.flush = (...args) => {
    clearTimeout(t);
    fn(...args);
  };
  return d;
}

/** "1 h 52 min", "4 min", "38 s". */
export function duration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min';
  const hours = Math.floor(m / 60);
  return hours + ' h ' + (m % 60) + ' min';
}

/** "cow_spawn_egg" -> "Cow Spawn Egg". */
export function itemName(id) {
  if (!id) return '';
  const name = id.includes(':') ? id.slice(id.indexOf(':') + 1) : id;
  return name.split('_').map(w => w ? w[0].toUpperCase() + w.slice(1) : w).join(' ');
}

/** A colour from text, the same every time (for item swatches). */
export function hueOf(text) {
  let n = 0;
  for (const ch of text) n = (n * 31 + ch.charCodeAt(0)) >>> 0;
  return n % 360;
}

/** Draws an RGBA image ({width, height, data}) into a new canvas. */
export function imageCanvas(img, cls) {
  const c = document.createElement('canvas');
  c.width = img.width;
  c.height = img.height;
  if (cls) c.className = cls;
  const ctx = c.getContext('2d');
  ctx.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0);
  return c;
}

export function isTyping(e) {
  const t = e.target;
  if (!(t instanceof HTMLElement)) return false;
  if (t.isContentEditable) return true;
  if (t instanceof HTMLTextAreaElement || t instanceof HTMLSelectElement) return true;
  if (t instanceof HTMLInputElement) return !['checkbox', 'radio', 'range', 'button'].includes(t.type);
  return false;
}
