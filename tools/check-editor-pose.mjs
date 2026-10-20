#!/usr/bin/env node
// Checks that the web editor draws NPCs exactly as the game does:
//
// 1. Poses: the editor's rig.js against the pose fixtures, within 1e-4. It reads the runtime's
//    src/test/resources/pose-fixtures.json (when it exists) and the editor's own
//    src/test/resources/editor-pose-fixtures.json (made with JOML by PoseReference.java, from
//    DESIGN.md's maths). Other files can be named on the command line.
// 2. Skin mapping: model.js's boxes and UVs, drawn face by face the way the game reads a model's uv
//    (a port of the pack builder's npc_models.py check), show exactly their part of the skin, for
//    classic and slim arms, and the outer layer is mapped like the base.
//
//   node tools/check-editor-pose.mjs [fixture.json ...]
//
// Exit code 0 when everything matches, 1 otherwise. Needs only node.

import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import * as Rig from '../src/main/resources/web/js/rig.js';
import {PART_SHAPES, shape, regions, partElements, faceQuads, CORNERS} from '../src/main/resources/web/js/model.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TOLERANCE = 1e-4;
const RUNTIME = path.join(ROOT, 'src', 'test', 'resources', 'pose-fixtures.json');
const EDITOR = path.join(ROOT, 'src', 'test', 'resources', 'editor-pose-fixtures.json');

let failures = 0;
const fail = message => {
  failures++;
  if (failures <= 40) console.log('  FAIL ' + message);
};

// ---------------------------------------------------------------- reading fixtures loosely

const PARTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg', 'held'];

function partName(key) {
  const k = String(key).replace(/([a-z])([A-Z])/g, '$1_$2').toLowerCase().replace(/-/g, '_');
  return PARTS.includes(k) ? k : null;
}

function channelKey(key) {
  return String(key).replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase().replace(/_/g, '-');
}

function vec(v) {
  if (Array.isArray(v) && v.length >= 3) return v.slice(0, 3).map(Number);
  if (v && typeof v === 'object' && 'x' in v) return [Number(v.x), Number(v.y), Number(v.z)];
  return null;
}

function quat(q) {
  if (Array.isArray(q) && q.length === 4) return q.map(Number);
  if (q && typeof q === 'object' && 'w' in q) return [Number(q.x), Number(q.y), Number(q.z), Number(q.w)];
  return null;
}

function pick(obj, ...names) {
  if (!obj || typeof obj !== 'object') return undefined;
  for (const n of names) {
    if (n.includes('.')) {
      const [a, b] = n.split('.');
      if (obj[a] && typeof obj[a] === 'object' && obj[a][b] !== undefined) return obj[a][b];
    } else if (obj[n] !== undefined) {
      return obj[n];
    }
  }
  return undefined;
}

function poseList(data) {
  if (Array.isArray(data)) return data;
  return pick(data, 'poses', 'fixtures', 'cases', 'tests') || [];
}

function inputs(pose, file) {
  const src = pick(pose, 'channels', 'values', 'animation', 'input.channels', 'input.values') || pose.pose || {};
  const channels = {};
  for (const [k, v] of Object.entries(src)) if (typeof v === 'number') channels[channelKey(k)] = v;
  const liveObj = pick(pose, 'live', 'layers', 'input') || {};
  const get = (...names) => {
    const v = pick(liveObj, ...names);
    return v !== undefined ? v : pick(pose, ...names);
  };
  const num = v => (typeof v === 'number' ? v : 0);
  return {
    channels,
    live: {
      yaw: num(get('yaw', 'Y', 'npcYaw', 'npc_yaw')),
      gazeBody: num(get('gazeBody', 'gb', 'gaze_body', 'bodyTurn', 'gaze.body')),
      gazeYaw: num(get('gazeYaw', 'gy', 'gaze_yaw', 'gaze.yaw', 'gaze.headYaw')),
      gazePitch: num(get('gazePitch', 'gp', 'gaze_pitch', 'gaze.pitch', 'gaze.headPitch')),
      breath: num(get('breath', 'b')),
      shift: num(get('shift', 'lambda', 'λ', 'weightShift', 'weight_shift', 'lean')),
      slim: !!get('slim'),
    },
    scale: num(pick(pose, 'scale', 'input.scale', 'live.scale')) || num(pick(file, 'scale')) || 0.9375,
  };
}

// ---------------------------------------------------------------- comparing

function closeVec(a, b) {
  return a && b && a.every((x, i) => Math.abs(x - b[i]) <= TOLERANCE);
}

function closeQuat(a, b) {
  if (!a || !b) return false;
  const same = a.every((x, i) => Math.abs(x - b[i]) <= TOLERANCE);
  const flipped = a.every((x, i) => Math.abs(x + b[i]) <= TOLERANCE);
  return same || flipped;
}

const fmt = v => '[' + v.map(x => (Math.abs(x) < 5e-13 ? 0 : x).toFixed(6)).join(', ') + ']';

function checkFile(file) {
  const rel = path.relative(ROOT, file);
  let data;
  try {
    data = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    fail(`${rel}: cannot read it (${e.message})`);
    return;
  }
  const poses = poseList(data);
  if (!poses.length) {
    fail(`${rel}: no poses found (expected a list, or an object with "poses")`);
    return;
  }
  const before = failures;
  let fields = 0;
  const frames = new Map();
  poses.forEach((pose, n) => {
    const name = pick(pose, 'name', 'id', 'title') || `pose ${n + 1}`;
    const {channels, live, scale} = inputs(pose, data);
    const posed = Rig.pose(channels, live);
    const shown = Rig.display(posed, scale);
    const rigFrame = Rig.placed(posed);
    let expected = pick(pose, 'parts', 'expected', 'expect', 'result', 'output') || {};
    // The whole body: its turn Rb, the root offset o and the body's stretch k (the runtime's "expect").
    const whole = expected;
    if (expected.parts && typeof expected.parts === 'object' && !Array.isArray(expected.parts)) expected = expected.parts;
    const turn = quat(pick(whole, 'bodyTurn', 'Rb'));
    if (turn) {
      fields++;
      if (!closeQuat(posed.Rb, turn)) fail(`${rel} / ${name} body turn: rig.js ${fmt(posed.Rb)}, fixture ${fmt(turn)}`);
    }
    const root = vec(pick(whole, 'root', 'o'));
    if (root) {
      fields++;
      if (!closeVec(posed.o, root)) fail(`${rel} / ${name} root: rig.js ${fmt(posed.o)}, fixture ${fmt(root)}`);
    }
    const stretch = pick(whole, 'stretch', 'k');
    if (typeof stretch === 'number') {
      fields++;
      if (Math.abs(posed.k - stretch) > TOLERANCE) fail(`${rel} / ${name} stretch: rig.js ${posed.k}, fixture ${stretch}`);
    }
    for (const [key, want] of Object.entries(expected)) {
      const part = partName(key);
      if (!part) {
        fail(`${rel} / ${name}: unknown part '${key}'`);
        continue;
      }
      const body = posed.parts[part];
      const where = `${rel} / ${name} / ${part}`;
      // origin and rotation: in the body frame (step 3); some files may mean the rig frame (after yaw).
      const origin = vec(pick(want, 'origin', 'position', 'p'));
      const rotation = quat(pick(want, 'rotation', 'quaternion', 'r'));
      if (origin || rotation) {
        const asBody = (!origin || closeVec(body.origin, origin)) && (!rotation || closeQuat(body.rotation, rotation));
        const asRig = (!origin || closeVec(rigFrame[part].origin, origin)) && (!rotation || closeQuat(rigFrame[part].rotation, rotation));
        if (asBody) frames.set('body frame', (frames.get('body frame') || 0) + 1);
        else if (asRig) frames.set('rig frame', (frames.get('rig frame') || 0) + 1);
        else {
          if (origin && !closeVec(body.origin, origin)) fail(`${where} origin: rig.js ${fmt(body.origin)}, fixture ${fmt(origin)}`);
          if (rotation && !closeQuat(body.rotation, rotation)) fail(`${where} rotation: rig.js ${fmt(body.rotation)}, fixture ${fmt(rotation)}`);
        }
        fields += (origin ? 1 : 0) + (rotation ? 1 : 0);
      }
      const d = shown[part];
      const checks = [
        ['translation', vec(pick(want, 'translation')), d.translation, closeVec],
        ['left rotation', quat(pick(want, 'leftRotation', 'left_rotation', 'left')), d.leftRotation, closeQuat],
        ['scale', vec(pick(want, 'scale')), d.scale, closeVec],
        ['right rotation', quat(pick(want, 'rightRotation', 'right_rotation', 'right')), d.rightRotation, closeQuat],
      ];
      for (const [what, wantValue, got, same] of checks) {
        if (!wantValue) continue;
        fields++;
        if (!same(got, wantValue)) fail(`${where} ${what}: rig.js ${fmt(got)}, fixture ${fmt(wantValue)}`);
      }
    }
  });
  const framesNote = frames.size ? ` (origins and rotations matched in the ${[...frames.keys()].join(' / ')})` : '';
  console.log(`${failures === before ? 'ok  ' : 'BAD '} ${rel}: ${poses.length} poses, ${fields} values${framesNote}`);
}

// ---------------------------------------------------------------- the skin mapping

/** How a face is looked at, as the picture's right and down in model space (npc_models.py VIEWS). */
const VIEWS = {
  north: [[-1, 0, 0], [0, -1, 0]],
  east: [[0, 0, -1], [0, -1, 0]],
  south: [[1, 0, 0], [0, -1, 0]],
  west: [[0, 0, 1], [0, -1, 0]],
  up: [[1, 0, 0], [0, 0, 1]],
  down: [[1, 0, 0], [0, 0, 1]],
};
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

/** For each pixel of a face drawn the game's way, the skin pixel it shows: [[x, y]] row by row. */
function facePixels(element, side) {
  const quad = faceQuads(element).find(q => q.side === side);
  const [right, down] = VIEWS[side];
  const points = quad.corners.map(c => [dot(c, right), dot(c, down)]);
  const [[ax, ay], [bx, by], , [dx, dy]] = points;
  const left = Math.min(...points.map(p => p[0])), top = Math.min(...points.map(p => p[1]));
  const width = Math.round(Math.max(...points.map(p => p[0])) - left);
  const height = Math.round(Math.max(...points.map(p => p[1])) - top);
  // corner 0 has (u1, v1), corner 1 (u1, v2), corner 3 (u2, v1): see CORNERS.
  const [u1, v1] = quad.uvs[0], v2 = quad.uvs[1][1], u2 = quad.uvs[3][0];
  const out = [];
  for (let j = 0; j < height; j++) {
    for (let i = 0; i < width; i++) {
      const px = left + i + 0.5, py = top + j + 0.5;
      const s = ((px - ax) * (dx - ax) + (py - ay) * (dy - ay)) / ((dx - ax) ** 2 + (dy - ay) ** 2);
      const t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2);
      out.push([Math.trunc(u1 + s * (u2 - u1)), Math.trunc(v1 + t * (v2 - v1))]);
    }
  }
  return {width, height, pixels: out};
}

function checkSkinMapping() {
  const before = failures;
  let faces = 0;
  if (Object.keys(CORNERS).length !== 6) fail('model.js: CORNERS must have six faces');
  for (const slim of [false, true]) {
    for (const part of Object.keys(PART_SHAPES)) {
      const {w, h, d, base, outer} = shape(part, slim);
      const [baseEl, outerEl] = partElements(part, slim);
      const where = `model.js ${part}${slim ? ' (slim)' : ''}`;
      // Boxes: as DESIGN.md lists them, joint at the origin.
      const wantFrom = [-w / 2, part === 'head' ? 0 : -h, -d / 2];
      const wantTo = [w / 2, part === 'head' ? h : 0, d / 2];
      if (!closeVec(baseEl.from, wantFrom) || !closeVec(baseEl.to, wantTo)) {
        fail(`${where}: base box ${fmt(baseEl.from)}..${fmt(baseEl.to)}, want ${fmt(wantFrom)}..${fmt(wantTo)}`);
      }
      const grow = PART_SHAPES[part].grow;
      if (!closeVec(outerEl.from, wantFrom.map(c => c - grow)) || !closeVec(outerEl.to, wantTo.map(c => c + grow))) {
        fail(`${where}: the outer box is not ${grow} px bigger on every side`);
      }
      for (const [side, [left, top, right, bottom]] of Object.entries(regions(base[0], base[1], w, h, d))) {
        faces++;
        const pic = facePixels(baseEl, side);
        if (pic.width !== right - left || pic.height !== bottom - top) {
          fail(`${where} ${side}: the face is ${pic.width}x${pic.height}, its skin region ${right - left}x${bottom - top}`);
          continue;
        }
        let bad = 0;
        pic.pixels.forEach(([x, y], n) => {
          const i = n % pic.width, j = Math.floor(n / pic.width);
          if (x !== left + i || y !== top + j) bad++;
        });
        if (bad) fail(`${where} ${side}: ${bad} pixels do not show skin [${left}, ${top}, ${right}, ${bottom}]`);
        // The outer layer: the same mapping, moved to its own picture.
        const shift = [outer[0] - base[0], outer[1] - base[1]];
        const want = baseEl.faces[side].uv.map((c, k) => c + shift[k % 2]);
        if (!outerEl.faces[side].uv.every((c, k) => c === want[k])) fail(`${where} ${side}: the outer layer is not mapped like the base`);
      }
      for (const el of [baseEl, outerEl]) {
        for (const [side, {uv}] of Object.entries(el.faces)) {
          if (!uv.every(c => c >= 0 && c <= 64)) fail(`${where} ${side}: a uv is outside the 64-pixel skin`);
        }
      }
    }
  }
  console.log(`${failures === before ? 'ok  ' : 'BAD '} model.js skin mapping: ${faces} base faces drawn pixel by pixel, classic and slim, with their outer layers`);
}

// ---------------------------------------------------------------- the gizmo's rings

/** Each joint ring of the rotate gizmo turns its part exactly as a change of its channel does. */
function checkGizmoRings() {
  const before = failures;
  let rings = 0;
  const cases = [
    {channels: {}, live: {}},
    {channels: {'torso-yaw': 15, 'torso-pitch': 20, 'torso-roll': -8, 'head-yaw': 30, 'head-pitch': -12, 'right-arm-forward': 120,
      'right-arm-out': 20, 'right-arm-across': 15, 'left-arm-forward': -30, 'left-arm-out': 60, 'left-leg-out': 10, 'right-leg-forward': 25,
      'root-yaw': 35, 'root-x': 3, 'root-z': -2, shoulders: 1}, live: {breath: 0.6, shift: 1.2, slim: true}},
  ];
  const moved = {root: 'body', body: 'body', head: 'head', right_arm: 'right_arm', left_arm: 'left_arm', right_leg: 'right_leg', left_leg: 'left_leg'};
  for (const {channels, live} of cases) {
    const before1 = Rig.pose(channels, live);
    const at1 = Rig.placed(before1);
    for (const [joint, {pivot, rings: list}] of Object.entries(before1.joints)) {
      for (const ring of list) {
        rings++;
        const dv = 7;
        const after = Rig.placed(Rig.pose({...channels, [ring.key]: (channels[ring.key] || 0) + dv}, live));
        const turn = Rig.about(ring.axis, ring.sign * dv);
        const part = moved[joint];
        const wantRot = Rig.mul(turn, at1[part].rotation);
        const rel = at1[part].origin.map((c, i) => c - pivot[i]);
        const wantOrigin = Rig.add(pivot, Rig.rotate(turn, rel));
        if (!closeQuat(after[part].rotation, wantRot) || !closeVec(after[part].origin, wantOrigin)) {
          fail(`rig.js joints.${joint} ring ${ring.key}: turning the ring does not match changing the channel`);
        }
      }
    }
  }
  console.log(`${failures === before ? 'ok  ' : 'BAD '} rig.js gizmo rings: ${rings} rings turn their part exactly as their channel does`);
}

// ---------------------------------------------------------------- run

const named = process.argv.slice(2).map(f => path.resolve(f));
const files = named.length ? named : [RUNTIME, EDITOR].filter(f => fs.existsSync(f));
if (!named.length && !fs.existsSync(RUNTIME)) {
  console.log('note src/test/resources/pose-fixtures.json (the runtime\'s) is not here yet: checking the editor\'s own fixture only');
}
for (const f of files) checkFile(f);
checkSkinMapping();
checkGizmoRings();
if (failures > 40) console.log(`  … and ${failures - 40} more`);
console.log(failures ? `${failures} problem${failures === 1 ? '' : 's'}` : 'The editor draws poses and skins as the game does.');
process.exit(failures ? 1 : 0);
