// The rig maths of DESIGN.md ("Coordinates and the rig maths"), exactly, for the editor's 3D view.
// Pure JavaScript (no three.js, no page): tools/check-editor-pose.mjs runs it under node against the
// runtime's pose fixtures, so the editor draws the same picture the game shows.
//
// Units are rig pixels: origin at the NPC's feet, +y up; in the NPC's own frame it faces +z and its
// right is -x. Angles in degrees. Quaternions are [x, y, z, w] as JOML; mul(a, b) turns by b first,
// then a.

export const UP = [0, 1, 0];
export const ACROSS = [1, 0, 0];
export const FRONT = [0, 0, 1];

/** The six body parts the pack has models for, then the held item. */
export const BODY_PARTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
export const PARTS = [...BODY_PARTS, 'held'];

const RAD = Math.PI / 180;

/** A right-handed rotation of deg degrees about a unit axis. */
export function about(axis, deg) {
  const half = deg * RAD / 2;
  const s = Math.sin(half);
  return [axis[0] * s, axis[1] * s, axis[2] * s, Math.cos(half)];
}

/** a·b (Hamilton product, as JOML's a.mul(b)): turns by b first, then a. More arguments chain left to right. */
export function mul(a, b, ...more) {
  const r = [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
  return more.length ? mul(r, ...more) : r;
}

/** The vector v turned by quaternion q. */
export function rotate(q, v) {
  const [x, y, z, w] = q;
  // t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
  const tx = 2 * (y * v[2] - z * v[1]);
  const ty = 2 * (z * v[0] - x * v[2]);
  const tz = 2 * (x * v[1] - y * v[0]);
  return [
    v[0] + w * tx + (y * tz - z * ty),
    v[1] + w * ty + (z * tx - x * tz),
    v[2] + w * tz + (x * ty - y * tx),
  ];
}

export function add(a, b) {
  return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
}

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

/**
 * Where every part goes for one moment.
 *
 * @param values channel values by key ("right-arm-forward": 90, ...); a missing key is 0 (rest).
 * @param live   the live layers: yaw (the NPC's, Minecraft degrees), gazeBody, gazeYaw, gazePitch
 *               (degrees), breath (0..1), shift (weight shift, pixels, hips towards its left), slim.
 * @returns {{psi, Rb, o, k, parts}} parts[name] = {origin, rotation} in the body frame (step 3);
 *          k is the body's height stretch.
 */
export function pose(values = {}, live = {}) {
  const v = key => {
    const n = Number(values[key]);
    return Number.isFinite(n) ? n : 0;
  };
  const Y = live.yaw || 0, gb = live.gazeBody || 0, gy = live.gazeYaw || 0, gp = live.gazePitch || 0;
  const b = live.breath || 0, lam = live.shift || 0, e = live.slim ? 0.5 : 0;

  // 1-2: the body's yaw and the root offset (to its right, up, forward).
  const psi = Y + gb + v('root-yaw');
  const Rb = about(UP, -psi);
  const o = rotate(Rb, [-v('root-x'), v('root-y'), v('root-z')]);

  // 3: in the body frame. Lean (degrees) and hip drop from the weight shift.
  const tau = Math.asin(clamp(-lam / 12, -1, 1)) / RAD;
  const d = -12 * (1 - Math.cos(tau * RAD));
  const H = [lam, 12 + d, 0];
  const rho = 0.3 * b;
  const Rt = mul(about(UP, -v('torso-yaw')), about(ACROSS, v('torso-pitch')), about(FRONT, v('torso-roll')));
  const N = add(H, rotate(Rt, [0, 12 + rho, 0]));
  const k = 1 + rho / 12;
  const shoulders = v('shoulders');

  const parts = {};
  parts.body = {origin: N, rotation: Rt};
  const hy = clamp(gy + v('head-yaw'), -80, 80);
  const hp = clamp(gp + v('head-pitch') - 1.5 * b, -60, 60);
  parts.head = {origin: N, rotation: mul(Rt, about(UP, -hy), about(ACROSS, hp), about(FRONT, v('head-roll')))};

  const arm = (s, side) => {
    const Ra = mul(about(FRONT, s * (v(side + '-arm-out') + 2.5 * b)), about(UP, -s * v(side + '-arm-across')),
      about(ACROSS, -v(side + '-arm-forward')));
    const P = add(H, rotate(Rt, [5 * s, 10 + rho + shoulders - e, 0]));
    const R = mul(Rt, Ra);
    return {origin: add(P, rotate(R, [s * (1 - e), 2, 0])), rotation: R, Ra};
  };
  const right = arm(-1, 'right');
  const left = arm(1, 'left');
  parts.right_arm = {origin: right.origin, rotation: right.rotation};
  parts.left_arm = {origin: left.origin, rotation: left.rotation};

  const leg = (s, side) => ({
    origin: [2 * s + lam, 12 + d, 0],
    rotation: mul(about(FRONT, tau + s * v(side + '-leg-out')), about(ACROSS, -v(side + '-leg-forward'))),
  });
  parts.right_leg = leg(-1, 'right');
  parts.left_leg = leg(1, 'left');

  // The held item, in the left hand, where the game draws an off-hand item on a player.
  const G = add(H, rotate(Rt, [5 - e, 10 + rho + shoulders - e, 0]));
  parts.held = {
    origin: add(G, rotate(mul(Rt, left.Ra), [1, -10, 2])),
    rotation: mul(Rt, left.Ra, about(ACROSS, 90)),
  };

  // The joints, for the editor's rotate gizmo: where each part turns, and for each channel turning
  // it, the axis it turns about now (rig frame, after the body yaw) and the sign: a channel change of
  // dv turns the part by sign·dv degrees about that axis. Same maths as above, step by step.
  const ID = [0, 0, 0, 1];
  const toRig = p => add(o, rotate(Rb, p));
  const axis = (q, a) => rotate(mul(Rb, q), a);
  const ty = v('torso-yaw'), tp = v('torso-pitch');
  const joints = {
    root: {pivot: [0, 0, 0], rings: [{key: 'root-yaw', sign: -1, axis: UP}]},
    body: {pivot: toRig(H), rings: [
      {key: 'torso-yaw', sign: -1, axis: axis(ID, UP)},
      {key: 'torso-pitch', sign: 1, axis: axis(about(UP, -ty), ACROSS)},
      {key: 'torso-roll', sign: 1, axis: axis(mul(about(UP, -ty), about(ACROSS, tp)), FRONT)},
    ]},
    head: {pivot: toRig(N), rings: [
      {key: 'head-yaw', sign: -1, axis: axis(Rt, UP)},
      {key: 'head-pitch', sign: 1, axis: axis(mul(Rt, about(UP, -hy)), ACROSS)},
      {key: 'head-roll', sign: 1, axis: axis(mul(Rt, about(UP, -hy), about(ACROSS, hp)), FRONT)},
    ]},
  };
  for (const [s, side] of [[-1, 'right'], [1, 'left']]) {
    const outQ = about(FRONT, s * (v(side + '-arm-out') + 2.5 * b));
    const acrossQ = about(UP, -s * v(side + '-arm-across'));
    joints[side + '_arm'] = {pivot: toRig(add(H, rotate(Rt, [5 * s, 10 + rho + shoulders - e, 0]))), rings: [
      {key: side + '-arm-out', sign: s, axis: axis(Rt, FRONT)},
      {key: side + '-arm-across', sign: -s, axis: axis(mul(Rt, outQ), UP)},
      {key: side + '-arm-forward', sign: -1, axis: axis(mul(Rt, outQ, acrossQ), ACROSS)},
    ]};
    joints[side + '_leg'] = {pivot: toRig(parts[side + '_leg'].origin), rings: [
      {key: side + '-leg-out', sign: s, axis: axis(ID, FRONT)},
      {key: side + '-leg-forward', sign: -1, axis: axis(about(FRONT, tau + s * v(side + '-leg-out')), ACROSS)},
    ]};
  }

  return {psi, Rb, o, k, parts, joints};
}

/**
 * Step 4: each part's item display, from pose(): translation in blocks, left rotation, scale and
 * right rotation (half a turn about y for the six body parts, none for the held item).
 */
export function display(posed, scale = 0.9375) {
  const out = {};
  for (const name of PARTS) {
    const {origin, rotation} = posed.parts[name];
    const at = add(posed.o, rotate(posed.Rb, origin));
    out[name] = {
      translation: at.map(c => c * scale / 16),
      leftRotation: mul(posed.Rb, rotation),
      scale: [scale, name === 'body' ? scale * posed.k : scale, scale],
      rightRotation: name === 'held' ? [0, 0, 0, 1] : [0, 1, 0, 0],
    };
  }
  return out;
}

/** Each part placed in the rig (after the body yaw and root offset), in pixels: o + Rb·p and Rb·R. */
export function placed(posed) {
  const out = {};
  for (const name of PARTS) {
    const {origin, rotation} = posed.parts[name];
    out[name] = {origin: add(posed.o, rotate(posed.Rb, origin)), rotation: mul(posed.Rb, rotation)};
  }
  return out;
}
