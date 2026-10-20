// The menu bar: File, Edit, View and Help menus, what is being edited (in the middle), who is logged
// in and how long the link lasts, and the mode tabs (NPC, Animate) at the right.

import {S, on} from './store.js';
import * as St from './store.js';
import {h, fill, icon, duration} from './ui.js';

/**
 * commands: {id: {label, icon, keys, run(), enabled?(), checked?()}}; menus: [[title, [ids or '-']]].
 * onExpire() is called when the link's time runs out.
 */
export function mountMenubar(root, commands, menus, onExpire) {
  let openMenu = null;
  let pop = null;
  const buttons = [];

  function close() {
    if (pop) pop.remove();
    pop = null;
    openMenu = null;
    for (const b of buttons) b.classList.remove('open');
    document.removeEventListener('pointerdown', outside, true);
    document.removeEventListener('keydown', keys, true);
  }

  function outside(e) {
    if (pop && !pop.contains(e.target) && !buttons.includes(e.target.closest('.menu-btn'))) close();
  }

  let hi = -1;
  function keys(e) {
    if (!pop) return;
    const items = [...pop.querySelectorAll('.menu-item:not(.disabled)')];
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      close();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      e.stopPropagation();
      hi = (hi + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      items.forEach((it, i) => it.classList.toggle('hi', i === hi));
    } else if (e.key === 'Enter' && hi >= 0 && items[hi]) {
      e.preventDefault();
      e.stopPropagation();
      items[hi].click();
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      e.preventDefault();
      e.stopPropagation();
      const i = buttons.indexOf(openMenu);
      open(buttons[(i + (e.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length]);
    }
  }

  function open(btn) {
    close();
    openMenu = btn;
    btn.classList.add('open');
    hi = -1;
    const list = menus[buttons.indexOf(btn)][1];
    pop = h('div', {class: 'menu-pop', role: 'menu'}, list.map(id => {
      if (id === '-') return h('div', {class: 'menu-sep'});
      const c = commands[id];
      const enabled = !c.enabled || c.enabled();
      const checked = c.checked ? c.checked() : null;
      return h('div', {
        class: ['menu-item', !enabled && 'disabled'], role: 'menuitem', title: c.hint || '',
        on: {click: () => { close(); c.run(); }},
      }, h('span', {class: 'mi-icon'}, checked === null ? (c.icon ? icon(c.icon, 15) : null) : checked ? icon('check', 15) : null),
      h('span', {class: 'mi-label', text: c.label}),
      c.keys ? h('span', {class: 'mi-keys', text: c.keys}) : null);
    }));
    const r = btn.getBoundingClientRect();
    pop.style.left = r.left + 'px';
    pop.style.top = r.bottom + 'px';
    document.body.append(pop);
    document.addEventListener('pointerdown', outside, true);
    document.addEventListener('keydown', keys, true);
  }

  for (const [title] of menus) {
    const b = h('button', {type: 'button', class: 'menu-btn', text: title,
      on: {
        click: () => (openMenu === b ? close() : open(b)),
        pointerenter: () => { if (openMenu && openMenu !== b) open(b); },
      }});
    buttons.push(b);
  }

  // The middle: what is open, and whether it is saved.
  const mid = h('div', {class: 'menubar-mid'});
  const drawMid = () => {
    const e = S.mode === 'npcs' ? St.npcEntry() : St.animEntry();
    if (!e) {
      fill(mid);
      return;
    }
    const name = S.mode === 'npcs' ? e.draft.id : (e.draft.name || e.id);
    fill(mid, h('span', {text: S.mode === 'npcs' ? 'NPC' : 'Animation'}), h('b', {text: name}),
      e.dirty ? h('span', {class: 'badge badge-unsaved', text: e.isNew ? 'not saved yet' : 'unsaved'}) : null);
  };

  // The right: who, how long the link lasts, the mode tabs.
  const who = h('div', {class: 'who'});
  const timer = h('div', {class: 'timer', title: 'How long this editor link still works'});
  const tabs = h('div', {class: 'modes', role: 'tablist'});
  const drawWho = () => {
    const p = S.player;
    const console = !p.online && p.name === 'Console';
    fill(who, h('div', {class: 'who-avatar', text: (p.name || '?').slice(0, 1).toUpperCase()}),
      h('span', {class: 'who-name', text: p.name || 'Unknown player'}),
      h('span', {class: 'who-status', title: p.online ? '' : console ? 'A link from the server console: live preview needs someone in game' : 'Live preview needs you in game'},
        h('span', {class: ['dot', p.online ? 'dot-ok' : 'dot-off']}), p.online ? 'in game' + (p.world ? ' · ' + p.world : '') : 'not in game'));
  };
  let expiredOnce = false;
  const drawTimer = () => {
    if (!S.expiresAt) return;
    const left = S.expiresAt - Date.now();
    timer.classList.toggle('soon', left < 10 * 60_000);
    timer.classList.toggle('very-soon', left < 2 * 60_000);
    fill(timer, icon('clock', 13), left > 0 ? duration(left) + ' left' : 'expired');
    if (left <= 0 && !expiredOnce) {
      expiredOnce = true;
      onExpire();
    }
  };
  const drawTabs = () => {
    const tab = (mode, label, iconName, title) => h('button', {
      type: 'button', class: ['mode-tab', S.mode === mode && 'active'], role: 'tab', title,
      attrs: {'aria-selected': String(S.mode === mode)}, on: {click: () => St.setMode(mode)},
    }, icon(iconName, 15), label);
    fill(tabs, tab('npcs', 'NPC', 'user', 'NPC mode: the NPCs and their settings'),
      tab('animations', 'Animate', 'film', 'Animate mode: pose and time the animations'));
  };

  fill(root, h('div', {class: 'brand'}, h('span', {class: 'brand-mark', attrs: {'aria-hidden': 'true'}}), 'Lively NPCs'),
    h('div', {class: 'menus'}, buttons), mid, h('div', {class: 'menubar-right'}, timer, who), tabs);

  on('server', () => { drawWho(); drawTimer(); });
  for (const ev of ['select', 'lists', 'anim', 'npc']) on(ev, drawMid);
  on('select', drawTabs);
  setInterval(drawTimer, 1000);
  drawWho();
  drawTimer();
  drawMid();
  drawTabs();
  return {close};
}
