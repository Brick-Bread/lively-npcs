// Small DOM helpers: element builder, icons, toasts and dialogs. Text always goes in as text (never
// as HTML), so names and plate lines from npcs.yml cannot inject anything.

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

// Icons: 24x24, stroke-drawn, made for this editor.
const ICONS = {
  play: '<path d="M7 4.5v15l12.5-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<rect x="6" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none"/><rect x="14" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none"/>',
  start: '<path d="M6 5v14"/><path d="M19 5v14l-10-7z" fill="currentColor"/>',
  prev: '<path d="M15 6l-6 6 6 6"/>',
  next: '<path d="M9 6l6 6-6 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  keyplus: '<path d="M9 4.5l5 5-5 5-5-5z" fill="currentColor" stroke="none"/><path d="M17 13v7M13.5 16.5h7"/>',
  key: '<path d="M12 4l8 8-8 8-8-8z"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 12.5a1.5 1.5 0 0 0 1.5 1.5h7a1.5 1.5 0 0 0 1.5-1.5L18 7M9 7V4.5h6V7"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M15.5 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5"/>',
  paste: '<rect x="5" y="5" width="14" height="16" rx="2"/><path d="M9 5V3.5h6V5M9 11h6M9 15h4"/>',
  mirror: '<path d="M12 3v18" stroke-dasharray="2 2.5"/><path d="M9 7L4 12l5 5z" fill="currentColor"/><path d="M15 7l5 5-5 5z"/>',
  undo: '<path d="M9 14L4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>',
  redo: '<path d="M15 14l5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/>',
  save: '<path d="M5 4h11l3 3v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1z"/><path d="M8 4v5h7V4M8 20v-6h8v6"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>',
  film: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M7.5 4.5v15M16.5 4.5v15M3.5 9.5h4M3.5 14.5h4M16.5 9.5h4M16.5 14.5h4"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  warn: '<path d="M12 4l9 16H3z"/><path d="M12 10v4.5M12 17.5v.5"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.8v.4"/>',
  upload: '<path d="M12 15V4M7.5 8.5L12 4l4.5 4.5"/><path d="M4.5 15v3.5a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5V15"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  chevron: '<path d="M6 9l6 6 6-6"/>',
  target: '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2.5" fill="currentColor"/><path d="M12 2.5V5M12 19v2.5M2.5 12H5M19 12h2.5"/>',
  broadcast: '<circle cx="12" cy="12" r="2" fill="currentColor"/><path d="M8.2 15.8a5.4 5.4 0 0 1 0-7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6M5.4 18.6a9.3 9.3 0 0 1 0-13.2M18.6 5.4a9.3 9.3 0 0 1 0 13.2"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  camera: '<path d="M4 8.5A1.5 1.5 0 0 1 5.5 7h2l1.5-2.5h6L16.5 7h2A1.5 1.5 0 0 1 20 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z"/><circle cx="12" cy="13" r="3.5"/>',
  wind: '<path d="M3 9h11a3 3 0 1 0-3-3M3 15h15a3 3 0 1 1-3 3M3 12h7"/>',
  restore: '<path d="M4 12a8 8 0 1 0 2.4-5.7L4 8.5"/><path d="M4 4v4.5h4.5"/>',
  dots: '<circle cx="5.5" cy="12" r="1.3" fill="currentColor"/><circle cx="12" cy="12" r="1.3" fill="currentColor"/><circle cx="18.5" cy="12" r="1.3" fill="currentColor"/>',
  grip: '<circle cx="9" cy="7" r="1.1" fill="currentColor"/><circle cx="15" cy="7" r="1.1" fill="currentColor"/><circle cx="9" cy="12" r="1.1" fill="currentColor"/><circle cx="15" cy="12" r="1.1" fill="currentColor"/><circle cx="9" cy="17" r="1.1" fill="currentColor"/><circle cx="15" cy="17" r="1.1" fill="currentColor"/>',
  up: '<path d="M6 15l6-6 6 6"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.01M9 10h.01M12 10h.01M15 10h.01M18 10h.01M7.5 14h9"/>',
  sparkle: '<path d="M12 3.5l1.8 5.2 5.2 1.8-5.2 1.8L12 17.5l-1.8-5.2L5 10.5l5.2-1.8z"/><path d="M18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
  hand: '<path d="M8 13V6.5a1.5 1.5 0 0 1 3 0V12M11 11V4.5a1.5 1.5 0 0 1 3 0V12M14 11.5V6a1.5 1.5 0 0 1 3 0v8a6 6 0 0 1-6 6h-.5a6 6 0 0 1-5-2.7L3.8 14a1.5 1.5 0 0 1 2.5-1.7L8 14.5"/>',
  loop: '<path d="M17 2.5l3 3-3 3"/><path d="M4 11.5v-1a5 5 0 0 1 5-5h11M7 21.5l-3-3 3-3"/><path d="M20 12.5v1a5 5 0 0 1-5 5H4"/>',
  move: '<path d="M12 3v18M3 12h18M12 3l-2.5 2.5M12 3l2.5 2.5M12 21l-2.5-2.5M12 21l2.5-2.5M3 12l2.5-2.5M3 12l2.5 2.5M21 12l-2.5-2.5M21 12l-2.5 2.5"/>',
  reset: '<path d="M5 12a7 7 0 1 0 2-4.9"/><path d="M5 4.5v3.5h3.5"/>',
  logo: '<rect x="8" y="2.5" width="8" height="8" rx="1.5"/><rect x="8" y="11.5" width="8" height="10" rx="1.5"/>',
};

/** An icon as an SVG element. */
export function icon(name, size = 16, extraClass = '') {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.8');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  if (extraClass) svg.setAttribute('class', extraClass);
  // The markup is this file's own constant, never user text.
  svg.innerHTML = ICONS[name] || '';
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
