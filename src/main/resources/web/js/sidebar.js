// The left side. Animate mode: the Animations panel (new, import, rename, duplicate, delete or restore
// preset, with preset / edited badges) above the Outliner. NPC mode: the NPC list.

import {S, on, selectAnim, selectNpc} from './store.js';
import * as Act from './actions.js';
import {h, fill, icon, button} from './ui.js';
import {faceThumb} from './skins.js';
import {plain} from './minimessage.js';
import {seconds} from './anim.js';
import {mountOutliner} from './outliner.js';

export function mountSidebar(root, {onImport} = {}) {
  let query = '';
  const search = h('input', {
    class: 'input', type: 'search', placeholder: 'Search…', attrs: {spellcheck: 'false', 'aria-label': 'Search'},
    on: {input: () => { query = search.value.trim().toLowerCase(); renderList(); }},
  });
  const list = h('div', {class: 'panel-body', role: 'listbox'});
  const title = h('span', {class: 'title'});
  const headButtons = h('div', {class: 'row', style: {gap: '1px'}});
  const listPanel = h('div', {class: 'panel grow-panel'},
    h('div', {class: 'panel-head'}, title, headButtons),
    h('div', {class: 'panel-search'}, h('div', {class: 'search'}, icon('search', 14), search)),
    list);
  const outlinerBody = h('div', {class: 'panel-body'});
  const outlinerPanel = h('div', {class: 'panel', style: {flex: 'none', maxHeight: '46%'}},
    h('div', {class: 'panel-head'}, h('span', {class: 'title', text: 'Outliner'})), outlinerBody);
  fill(root, listPanel, outlinerPanel);
  mountOutliner(outlinerBody);

  function renderHead() {
    const anim = S.mode === 'animations';
    title.textContent = anim ? 'Animations' : 'NPCs';
    outlinerPanel.hidden = !anim;
    fill(headButtons, anim
      ? [button(null, 'plus', {class: 'btn-xs btn-ghost', title: 'New animation', onClick: () => Act.newAnim()}),
        button(null, 'upload', {class: 'btn-xs btn-ghost', title: 'Import from Blockbench (.bbmodel or .animation.json)', onClick: () => onImport && onImport()})]
      : [button(null, 'userplus', {class: 'btn-xs btn-ghost', title: 'New NPC (where you stand in game)', onClick: () => Act.newNpc()})]);
  }

  function matches(...texts) {
    return !query || texts.some(t => String(t || '').toLowerCase().includes(query));
  }

  function npcRow(e) {
    const n = e.draft;
    const active = S.npcId === e.id;
    const mob = n.entity !== 'player';
    const sub = n.plate && n.plate.length ? plain(n.plate[0], {npc: n.id}) : 'No name plate';
    return h('div', {
      class: ['item', 'tall', active && 'active'], role: 'option', tabIndex: 0, attrs: {'aria-selected': String(active)},
      on: {click: () => selectNpc(e.id), keydown: ev => { if (ev.key === 'Enter') selectNpc(e.id); }},
    },
    mob ? h('div', {class: 'item-face'}, icon('user', 16)) : faceThumb(n.skin, 'item-face'),
    h('div', {class: 'item-main'},
      h('div', {class: 'item-title'}, h('span', {class: 'name', text: n.id}), e.dirty ? h('span', {class: 'unsaved-dot', title: 'Unsaved changes'}) : null,
        mob ? h('span', {class: 'badge badge-mob', text: n.entity.replace(/_/g, ' ')}) : null,
        e.remote ? h('span', {class: 'badge badge-warn', text: e.remote === 'deleted' ? 'deleted in game' : 'changed in game'}) : null),
      h('div', {class: 'item-sub', text: sub})),
    h('div', {class: 'item-actions'},
      button(null, 'copy', {class: 'btn-xs btn-ghost', title: 'Duplicate', onClick: ev => { ev.stopPropagation(); Act.duplicateNpc(e.id); }}),
      button(null, 'trash', {class: 'btn-xs btn-ghost btn-danger', title: 'Delete', onClick: ev => { ev.stopPropagation(); Act.deleteNpc(e.id); }})));
  }

  function animRow(e) {
    const a = e.draft;
    const active = S.animId === e.id;
    const preset = a.builtin || (e.base && e.base.builtin);
    const edited = e.base && e.base.edited;
    const badges = [];
    if (preset) badges.push(h('span', {class: 'badge badge-preset', text: 'preset', title: 'Comes with Lively NPCs'}));
    if (edited) badges.push(h('span', {class: 'badge badge-edited', text: 'edited', title: 'Your saved version replaces the preset on this server'}));
    if (e.isNew) badges.push(h('span', {class: 'badge badge-unsaved', text: 'new', title: 'Not saved yet'}));
    if (e.remote) badges.push(h('span', {class: 'badge badge-warn', text: e.remote === 'deleted' ? 'deleted in game' : 'changed in game'}));
    const actions = [
      button(null, 'edit', {class: 'btn-xs btn-ghost', title: 'Rename', onClick: ev => { ev.stopPropagation(); Act.renameAnim(e.id); }}),
      button(null, 'copy', {class: 'btn-xs btn-ghost', title: 'Duplicate', onClick: ev => { ev.stopPropagation(); Act.duplicateAnim(e.id); }}),
    ];
    if (preset) {
      if (edited) actions.push(button(null, 'restore', {class: 'btn-xs btn-ghost', title: 'Restore the preset', onClick: ev => { ev.stopPropagation(); Act.deleteAnim(e.id); }}));
    } else {
      actions.push(button(null, 'trash', {class: 'btn-xs btn-ghost btn-danger', title: e.isNew ? 'Discard' : 'Delete', onClick: ev => { ev.stopPropagation(); Act.deleteAnim(e.id); }}));
    }
    const facts = [a.id, seconds(a.length)];
    if (a.loop) facts.push('loops');
    if (a.glance) facts.push('glance');
    return h('div', {
      class: ['item', active && 'active'], role: 'option', tabIndex: 0, title: facts.join(' · '), attrs: {'aria-selected': String(active)},
      on: {
        click: () => selectAnim(e.id),
        dblclick: () => Act.renameAnim(e.id),
        keydown: ev => { if (ev.key === 'Enter') selectAnim(e.id); if (ev.key === 'F2') Act.renameAnim(e.id); },
      },
    },
    h('div', {class: 'item-icon'}, icon(a.loop ? 'loop' : 'film', 15)),
    h('div', {class: 'item-main'},
      h('div', {class: 'item-title'}, h('span', {class: 'name', text: a.name || a.id}), e.dirty ? h('span', {class: 'unsaved-dot', title: 'Unsaved changes'}) : null, ...badges)),
    h('div', {class: 'item-actions'}, actions));
  }

  function renderList() {
    const rows = [];
    if (S.mode === 'npcs') {
      for (const e of S.npcs.values()) {
        const n = e.draft;
        if (matches(n.id, n.entity, ...(n.plate || []))) rows.push(npcRow(e));
      }
      if (!rows.length) rows.push(h('div', {class: 'side-empty'}, S.npcs.size ? 'No NPC matches.' : 'No NPCs yet. Press + to make one where you stand in game.'));
    } else {
      const presets = [], own = [];
      for (const e of S.anims.values()) {
        const a = e.draft;
        if (!matches(a.id, a.name)) continue;
        ((a.builtin || (e.base && e.base.builtin)) ? presets : own).push(animRow(e));
      }
      if (own.length) rows.push(h('div', {class: 'side-group', text: 'Your animations'}), ...own);
      if (presets.length) rows.push(h('div', {class: 'side-group', text: 'Presets'}), ...presets);
      if (!rows.length) rows.push(h('div', {class: 'side-empty', text: S.anims.size ? 'No animation matches.' : 'No animations yet.'}));
    }
    fill(list, rows);
    // Keep the selected row in view, scrolling only the list (never the page).
    const active = list.querySelector('.item.active');
    if (active) {
      const top = active.offsetTop - list.offsetTop, bottom = top + active.offsetHeight;
      if (top < list.scrollTop) list.scrollTop = top - 8;
      else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight + 8;
    }
  }

  const all = () => {
    renderHead();
    renderList();
  };
  let queued = false;
  const later = () => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => {
      queued = false;
      renderList();
    });
  };
  on('lists', all);
  on('select', all);
  on('anim', later);
  on('npc', later);
  all();
  return {focusSearch: () => search.focus()};
}
