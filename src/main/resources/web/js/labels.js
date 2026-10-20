// Words for people: what each part is called, the Outliner's tree, which values belong to which part
// (axes.js: Blockbench's names and signs, with today's friendly words as hints), and the colours of
// the axes (the gizmo rings and value rows use the same).

import {PARTS, rowsFor, rowOf, channelsOf} from './axes.js';

export const PART_NAMES = {
  root: 'Whole body', body: 'Torso', head: 'Head', right_arm: 'Right arm', left_arm: 'Left arm',
  right_leg: 'Right leg', left_leg: 'Left leg', held: 'Held item',
};

/** The Outliner: the rig's parts as a tree (the held item hangs off the left arm, shown when it holds one). */
export const TREE = [
  {part: 'root', depth: 0, icon: 'body'},
  {part: 'body', depth: 1, icon: 'folder'},
  {part: 'head', depth: 2, icon: 'folder'},
  {part: 'right_arm', depth: 2, icon: 'folder'},
  {part: 'left_arm', depth: 2, icon: 'folder'},
  {part: 'held', depth: 3, icon: 'hand', item: true},
  {part: 'right_leg', depth: 1, icon: 'folder'},
  {part: 'left_leg', depth: 1, icon: 'folder'},
];

/** Selectable parts, in the Outliner's order, with the channels each one shows. 'root' is the whole body. */
export const GROUPS = PARTS.map(part => ({part, name: PART_NAMES[part], channels: channelsOf(part)}));

/** A colour per part (timeline swatches and diamonds). */
export const PART_COLORS = {root: '#8f9bff', body: '#b58cff', head: '#4fc3f7', right_arm: '#ff8a65', left_arm: '#ffd54f', right_leg: '#4dd0a1', left_leg: '#9ccc65'};

export const AXIS_COLORS = {x: '#f2556b', y: '#5fd36f', z: '#3f9bff'};

/** "Rotation X" (Blockbench's name) for a channel. */
export function channelLabel(key, part = null) {
  const r = rowOf(key, part);
  return r ? r.label : key.replace(/-/g, ' ');
}

/** Today's friendly words for a channel, for hover hints. */
export function channelHint(key, part = null) {
  const r = rowOf(key, part);
  return r ? `${r.label}: ${r.hint}` : '';
}

/** The axis a channel's row is about ('x', 'y', 'z'). */
export function channelAxis(key) {
  const r = rowOf(key);
  return r ? r.axis : null;
}

/** The group a channel is in (shoulders: the right arm's). */
export function groupOf(key) {
  return GROUPS.find(g => g.channels.includes(key)) || null;
}

/** Long name, e.g. "Right arm · Rotation X". */
export function channelTitle(key, part = null) {
  const r = rowOf(key, part);
  return r ? `${PART_NAMES[r.part]} · ${r.label}` : key;
}

export {rowsFor};

export const EASING_NAMES = {
  'linear': 'Linear',
  'ease-in': 'Ease in',
  'ease-out': 'Ease out',
  'ease-in-out': 'Ease in and out',
  'step': 'Step',
};

export const EASING_HINTS = {
  'linear': 'Linear: an even speed into this keyframe',
  'ease-in': 'Ease in: starts slow, speeds up into this keyframe',
  'ease-out': 'Ease out: starts fast, slows down into this keyframe',
  'ease-in-out': 'Ease in and out: slow, fast, slow (Blockbench\'s smooth)',
  'step': 'Step: holds the last pose, then jumps at this keyframe',
};
