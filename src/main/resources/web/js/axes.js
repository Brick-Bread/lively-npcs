// Blockbench's words for Lively NPCs' channels: every value is shown as a Rotation X/Y/Z or Position
// X/Y/Z of a part, with Blockbench's sign, and converted back when it is stored. Files and the API keep
// the channel keys and signs of anim/Channel; only the page shows Blockbench's. Pure JavaScript: node
// tests it (tools/editor-tests.mjs).
//
// What the values mean on a player model, as Blockbench 5 shows them in its keyframe panel (since 5.0
// an animation turns a bone the way Edit mode does):
//   Rotation X  positive tips the part's top back: the head looks up, a hanging arm or leg swings forward.
//   Rotation Y  positive turns the part to its left.
//   Rotation Z  positive tilts the part's top to its left: a hanging right arm (or leg) swings out to its
//               right, a hanging left arm in towards the body.
//   Position X  positive moves to its right; Position Y up; Position Z positive moves back.
// Bedrock animation files (.animation.json), and Blockbench before 5.0, keep rotation X and Y and
// position X the other way round (the game's own model numbers: an arm raised in front is X -90); the
// importer (bbimport.js) converts them. Blockbench turns a part by Z, then Y, then X (the matrix
// Rz·Ry·Rx), the same order as the rig's arms (DESIGN.md: about(FRONT, out) · about(UP, across) ·
// about(ACROSS, forward)), so an arm's three values are exactly Blockbench's. The head and torso turn yaw,
// then pitch, then roll (Ry·Rx·Rz): with two or three of their values set at once, Blockbench's numbers
// and ours differ by more than a sign (the importer converts those through the rotation itself).
//
//   part        shown as     channel              sign  (shown = sign x stored)
//   Whole body  Rotation Y   root-yaw              -1   ours: spins to its right
//               Position X   root-x                +1   steps to its right
//               Position Y   root-y                +1   rises
//               Position Z   root-z                -1   ours: steps forward
//   Torso       Rotation X   torso-pitch           -1   ours: bends forward
//               Rotation Y   torso-yaw             -1   ours: twists to its right
//               Rotation Z   torso-roll            -1   ours: leans to its right
//   Head        Rotation X   head-pitch            -1   ours: looks down
//               Rotation Y   head-yaw              -1   ours: turns to its right
//               Rotation Z   head-roll             -1   ours: tilts to its right
//   Right arm   Rotation X   right-arm-forward     +1   raises it in front (90 points ahead)
//               Rotation Y   right-arm-across      +1   swings it in across the chest (to its left)
//               Rotation Z   right-arm-out         +1   swings it out to its side
//               Position Y   shoulders             +1   lifts both shoulders (shared with the left arm)
//   Left arm    Rotation X   left-arm-forward      +1
//               Rotation Y   left-arm-across       -1   ours: swings it in across the chest (to its right)
//               Rotation Z   left-arm-out          -1   ours: swings it out to its side (to its left)
//               Position Y   shoulders             +1
//   Right leg   Rotation X   right-leg-forward     +1   swings it forward
//               Rotation Z   right-leg-out         +1
//   Left leg    Rotation X   left-leg-forward      +1
//               Rotation Z   left-leg-out          -1
//
// Values the rig has no channel for (a leg's Rotation Y, the head's position, ...) are not shown.

import {about, mul, UP, ACROSS, FRONT} from './rig.js';

const RAD = Math.PI / 180;

/** [part, label, channel, sign, hint]: the table above, in the order each part lists its values. */
const TABLE = [
  ['root', 'Rotation Y', 'root-yaw', -1, 'Spin: positive turns the whole body to its left, negative to its right'],
  ['root', 'Position X', 'root-x', 1, 'Step sideways: positive steps to its right, negative to its left'],
  ['root', 'Position Y', 'root-y', 1, 'Rise: positive lifts the whole body (a hop)'],
  ['root', 'Position Z', 'root-z', -1, 'Step: negative steps forward, positive steps back'],
  ['body', 'Rotation X', 'torso-pitch', -1, 'Bend at the hips: negative bends forward, positive leans back'],
  ['body', 'Rotation Y', 'torso-yaw', -1, 'Twist: negative twists to its right, positive to its left'],
  ['body', 'Rotation Z', 'torso-roll', -1, 'Lean: positive leans to its left, negative to its right'],
  ['head', 'Rotation X', 'head-pitch', -1, 'Nod: negative looks down, positive looks up'],
  ['head', 'Rotation Y', 'head-yaw', -1, 'Turn: negative turns the head to its right, positive to its left'],
  ['head', 'Rotation Z', 'head-roll', -1, 'Tilt: positive tilts the head to its left, negative to its right'],
  ['right_arm', 'Rotation X', 'right-arm-forward', 1, 'Raise in front: 90 points the arm ahead, 180 straight up'],
  ['right_arm', 'Rotation Y', 'right-arm-across', 1, 'Across: positive swings the arm in across its chest'],
  ['right_arm', 'Rotation Z', 'right-arm-out', 1, 'Out to its side: positive swings the arm out'],
  ['right_arm', 'Position Y', 'shoulders', 1, 'Shrug: positive lifts both shoulders'],
  ['left_arm', 'Rotation X', 'left-arm-forward', 1, 'Raise in front: 90 points the arm ahead, 180 straight up'],
  ['left_arm', 'Rotation Y', 'left-arm-across', -1, 'Across: negative swings the arm in across its chest'],
  ['left_arm', 'Rotation Z', 'left-arm-out', -1, 'Out to its side: negative swings the arm out'],
  ['left_arm', 'Position Y', 'shoulders', 1, 'Shrug: positive lifts both shoulders'],
  ['right_leg', 'Rotation X', 'right-leg-forward', 1, 'Swing: positive swings the leg forward, negative back'],
  ['right_leg', 'Rotation Z', 'right-leg-out', 1, 'Out to its side: positive swings the leg out'],
  ['left_leg', 'Rotation X', 'left-leg-forward', 1, 'Swing: positive swings the leg forward, negative back'],
  ['left_leg', 'Rotation Z', 'left-leg-out', -1, 'Out to its side: negative swings the leg out'],
];

/** The parts as the Outliner lists them, with the values each one shows. */
export const PARTS = ['root', 'body', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];

/** Every row: {part, label, key, sign, hint, type ('rotation' | 'position'), axis ('x' | 'y' | 'z')}. */
export const ROWS = TABLE.map(([part, label, key, sign, hint]) => ({
  part, label, key, sign, hint,
  type: label.startsWith('Rotation') ? 'rotation' : 'position',
  axis: label.slice(-1).toLowerCase(),
}));

const BY_PART = Object.fromEntries(PARTS.map(p => [p, ROWS.filter(r => r.part === p)]));
const BY_KEY = {};
for (const r of ROWS) if (!BY_KEY[r.key]) BY_KEY[r.key] = r;

/** The rows a part shows (rotations first, then positions). */
export function rowsFor(part) {
  return BY_PART[part] || [];
}

/** The row a channel is shown in (shoulders: the right arm's, unless a part is named). */
export function rowOf(key, part = null) {
  if (part) {
    const r = (BY_PART[part] || []).find(x => x.key === key);
    if (r) return r;
  }
  return BY_KEY[key] || null;
}

/** The channels a part moves in the editor's lists (its rows' channels). */
export function channelsOf(part) {
  return rowsFor(part).map(r => r.key);
}

/** +1 or -1: a channel's value as Blockbench shows it is sign × the stored value. */
export function signOf(key) {
  const r = BY_KEY[key];
  return r ? r.sign : 1;
}

/** A stored value as shown (Blockbench's sign). */
export function toShown(key, value) {
  const v = signOf(key) * value;
  return Object.is(v, -0) ? 0 : v;
}

/** A shown value as stored (the channel's own sign). The sign is ±1, so this is its own inverse. */
export function fromShown(key, value) {
  return toShown(key, value);
}

/** A channel's slider range (stored min/max) as shown: [lo, hi]. */
export function shownRange(key, min, max) {
  return signOf(key) > 0 ? [min, max] : [-max, -min];
}

/** "Rotation X" for a channel (in that part, or its first). */
export function shownLabel(key, part = null) {
  const r = rowOf(key, part);
  return r ? r.label : key;
}

/** Whether a channel is a turn (degrees) rather than a move (pixels). */
export function isRotation(key) {
  const r = BY_KEY[key];
  return r ? r.type === 'rotation' : true;
}

/** A whole pose (stored channel values) as each part's shown {rotation: [x, y, z], position: [x, y, z]} (null where none). */
export function poseToShown(values) {
  const out = {};
  for (const part of PARTS) {
    const p = {rotation: [null, null, null], position: [null, null, null]};
    for (const r of rowsFor(part)) p[r.type][{x: 0, y: 1, z: 2}[r.axis]] = toShown(r.key, values[r.key] || 0);
    out[part] = p;
  }
  return out;
}

/** Shown values of one part ({rotation: [x, y, z], position: [...]}, null or missing = leave) as stored channel values. */
export function shownToChannels(part, shown) {
  const out = {};
  for (const r of rowsFor(part)) {
    const list = shown[r.type];
    const v = list ? list[{x: 0, y: 1, z: 2}[r.axis]] : null;
    if (typeof v === 'number' && Number.isFinite(v)) out[r.key] = fromShown(r.key, v);
  }
  return out;
}

/**
 * Blockbench's rotation [x, y, z] (degrees, as Blockbench 5's keyframe panel shows them) as a turn in the
 * rig's frame (a quaternion [x, y, z, w]): Z first, then Y, then X. Blockbench's model faces -z with its
 * right at +x, the rig's faces +z with its right at -x (half a turn about y), so X and Z turn the other way.
 */
export function bbRotation(x, y, z) {
  return mul(about(FRONT, -z), about(UP, y), about(ACROSS, -x));
}

/** A turn (quaternion) as the head's or torso's channels: yaw, then pitch, then roll (Ry(-yaw)·Rx(pitch)·Rz(roll)), in degrees. */
export function yawPitchRoll(q) {
  const [x, y, z, w] = q;
  const m13 = 2 * (x * z + y * w), m33 = 1 - 2 * (x * x + y * y);
  const m21 = 2 * (x * y + z * w), m22 = 1 - 2 * (x * x + z * z), m23 = 2 * (y * z - x * w);
  const pitch = Math.asin(Math.max(-1, Math.min(1, -m23)));
  let yaw, roll;
  if (Math.abs(m23) < 0.9999999) {
    yaw = Math.atan2(m13, m33);
    roll = Math.atan2(m21, m22);
  } else {
    // Straight up or down: yaw and roll turn about the same axis; put it all in yaw.
    const m11 = 1 - 2 * (y * y + z * z), m31 = 2 * (x * z - y * w);
    yaw = Math.atan2(-m31, m11);
    roll = 0;
  }
  const clean = v => (Math.abs(v) < 1e-9 ? 0 : v);
  return {yaw: clean(-yaw / RAD), pitch: clean(pitch / RAD), roll: clean(roll / RAD)};
}
