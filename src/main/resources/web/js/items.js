// A searchable item picker: a button showing the chosen item, opening a list you can type in to
// filter, walk with the arrow keys and pick with Enter (or a click). "None" is always first.

import {S} from './store.js';
import {h, fill, icon, itemName, hueOf} from './ui.js';

const SHOWN = 150;

function swatch(id) {
  if (!id) return h('span', {class: 'item-swatch', style: {background: 'var(--bg-5)'}});
  const hue = hueOf(id);
  return h('span', {class: 'item-swatch', style: {background: `hsl(${hue} 55% 62%)`}, text: itemName(id).slice(0, 1)});
}

/**
 * An item picker. value: an item id or ''. onChange(id) after a pick. Returns {el, set(value)}.
 */
export function itemPicker(value, onChange, {noneLabel = 'None', title = 'Pick an item'} = {}) {
  let current = value || '';
  const label = h('span', {class: 'val'});
  const idText = h('span', {class: 'id'});
  const btn = h('button', {type: 'button', class: 'combo-btn', title, attrs: {'aria-haspopup': 'listbox'}, on: {click: () => open()}},
    h('span', {class: 'swatch-holder'}), label, idText, icon('chevron', 14));
  const el = h('div', {class: 'combo'}, btn);

  function show() {
    btn.querySelector('.swatch-holder').replaceChildren(swatch(current));
    label.textContent = current ? itemName(current) : noneLabel;
    label.style.color = current ? '' : 'var(--text-3)';
    idText.textContent = current && !S.itemSet.has(current) && S.items.length ? 'not an item!' : current;
    idText.style.color = current && !S.itemSet.has(current) && S.items.length ? 'var(--danger)' : '';
  }

  function open() {
    const r = btn.getBoundingClientRect();
    const input = h('input', {class: 'input', placeholder: 'Search items…', attrs: {spellcheck: 'false', autocomplete: 'off', 'aria-label': 'Search items'}});
    const list = h('div', {class: 'combo-list', role: 'listbox'});
    const pop = h('div', {class: 'combo-pop'}, input, list);
    const below = window.innerHeight - r.bottom > 320 || r.top < 320;
    Object.assign(pop.style, {left: r.left + 'px', width: Math.max(r.width, 260) + 'px'});
    if (below) pop.style.top = (r.bottom + 4) + 'px';
    else pop.style.bottom = (window.innerHeight - r.top + 4) + 'px';
    const back = h('div', {style: {position: 'fixed', inset: 0, zIndex: 59}, on: {mousedown: () => close()}});
    document.body.append(back, pop);
    let options = [];
    let hi = 0;

    function render() {
      const q = input.value.trim().toLowerCase().replace(/\s+/g, '_');
      const found = [];
      if (!q || noneLabel.toLowerCase().includes(q)) found.push('');
      let more = 0;
      for (const id of S.items) {
        if (q && !id.includes(q) && !itemName(id).toLowerCase().includes(input.value.trim().toLowerCase())) continue;
        if (found.length >= SHOWN) {
          more++;
          continue;
        }
        found.push(id);
      }
      options = found;
      hi = Math.max(0, Math.min(hi, options.length - 1));
      fill(list, ...options.map((id, i) => h('div', {
        class: ['combo-opt', i === hi && 'hi'], role: 'option', attrs: {'aria-selected': String(id === current)},
        on: {mousedown: ev => { ev.preventDefault(); pick(id); }, mousemove: () => { if (hi !== i) { hi = i; mark(); } }},
      }, swatch(id), h('span', {text: id ? itemName(id) : noneLabel}), id ? h('span', {class: 'id', text: id}) : null)),
      more ? h('div', {class: 'combo-more', text: `${more} more: type to narrow down`}) : null,
      !options.length ? h('div', {class: 'combo-more', text: 'No item matches.'}) : null);
    }

    function mark() {
      [...list.querySelectorAll('.combo-opt')].forEach((o, i) => o.classList.toggle('hi', i === hi));
      const el2 = list.querySelectorAll('.combo-opt')[hi];
      if (el2) {
        // Scroll only the list (scrollIntoView would move the page too).
        const top = el2.offsetTop - list.offsetTop, bottom = top + el2.offsetHeight;
        if (top < list.scrollTop) list.scrollTop = top - 4;
        else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight + 4;
      }
    }

    function pick(id) {
      current = id;
      show();
      close();
      onChange(id);
    }

    function close() {
      back.remove();
      pop.remove();
      btn.focus();
    }

    input.addEventListener('input', () => {
      hi = 0;
      render();
    });
    input.addEventListener('keydown', e => {
      e.stopPropagation();
      if (e.key === 'ArrowDown') {
        hi = Math.min(options.length - 1, hi + 1);
        mark();
        e.preventDefault();
      } else if (e.key === 'ArrowUp') {
        hi = Math.max(0, hi - 1);
        mark();
        e.preventDefault();
      } else if (e.key === 'Enter') {
        if (options.length) pick(options[hi]);
        e.preventDefault();
      } else if (e.key === 'Escape') {
        close();
        e.preventDefault();
      }
    });
    hi = Math.max(0, S.items.indexOf(current) + 1);
    render();
    if (hi >= options.length) hi = 0;
    mark();
    input.focus();
  }

  show();
  return {el, set: v => { current = v || ''; show(); }};
}
