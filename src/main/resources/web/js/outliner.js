// The Outliner: the rig's parts as a tree (Whole body > Torso > Head, arms (+ the held item); legs).
// Picking a part here, in the 3D view or in the timeline is the same selection. A dot marks the parts
// that move in the open animation.

import {S, on} from './store.js';
import * as St from './store.js';
import {TREE, PART_NAMES, PART_COLORS, GROUPS} from './labels.js';
import {h, fill, icon, itemName} from './ui.js';

export function mountOutliner(root) {
  const closed = new Set();
  const box = h('div', {class: 'outliner', role: 'tree'});
  fill(root, box);
  root.addEventListener('click', e => {
    if (e.target === root || e.target === box) St.selectPart(null);
  });

  function moves(part) {
    const e = St.animEntry();
    const g = GROUPS.find(x => x.part === part);
    return !!(e && g && e.draft.keyframes.some(k => g.channels.some(c => k.values[c])));
  }

  function hiddenByParent(i) {
    // A row is hidden when any row above it with a smaller depth (its parent chain) is closed.
    let depth = TREE[i].depth;
    for (let j = i - 1; j >= 0 && depth > 0; j--) {
      if (TREE[j].depth < depth) {
        if (closed.has(TREE[j].part)) return true;
        depth = TREE[j].depth;
      }
    }
    return false;
  }

  function render() {
    const npc = St.shownNpc();
    const held = npc && npc.entity === 'player' ? npc.held : '';
    const rows = [];
    TREE.forEach((node, i) => {
      if (node.item && !held) return;
      if (hiddenByParent(i)) return;
      const hasKids = i + 1 < TREE.length && TREE[i + 1].depth > node.depth && !(TREE[i + 1].item && !held);
      const select = node.item ? 'left_arm' : node.part;
      const active = !node.item && S.part === node.part;
      const caret = h('span', {class: ['ol-caret', !hasKids && 'none', hasKids && !closed.has(node.part) && 'open'],
        on: {click: ev => {
          ev.stopPropagation();
          if (closed.has(node.part)) closed.delete(node.part);
          else closed.add(node.part);
          render();
        }}}, icon('caret', 14));
      const name = node.item ? `${PART_NAMES.held}: ${itemName(held)}` : PART_NAMES[node.part];
      const moving = !node.item && moves(node.part);
      rows.push(h('div', {
        class: ['ol-row', node.part === 'root' && 'root', node.item && 'item-row', active && 'active'], role: 'treeitem',
        attrs: {'aria-selected': String(active)}, title: node.item ? 'In its left hand: moves with the left arm' : '',
        style: {paddingLeft: (4 + node.depth * 14) + 'px'},
        on: {click: ev => { ev.stopPropagation(); St.selectPart(select); }},
      }, caret, h('span', {class: 'ol-icon'}, icon(active || node.part === 'root' ? (node.icon === 'folder' ? 'folderopen' : node.icon) : node.icon, 15)),
      h('span', {class: 'ol-name', text: name}),
      moving ? h('span', {class: 'ol-moves', title: 'Moves in this animation', style: {background: PART_COLORS[node.part]}}) : null));
    });
    fill(box, rows);
  }

  for (const ev of ['part', 'select', 'anim', 'npc', 'view', 'lists']) on(ev, render);
  render();
}
