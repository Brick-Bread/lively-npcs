// Import from Blockbench, in the browser: the animations of a .bbmodel, or a Bedrock .animation.json,
// made on a player model, become Lively NPCs animations (keyframes of the rig's channels), with a short
// report of what mapped, what was approximated and what was left out. Pure JavaScript: node tests it.
//
// What it reads (from the formats' documentation, no Blockbench code):
// - .bbmodel: JSON with meta.format_version, the bones ("groups", or groups inside "outliner" before
//   5.0) and "animations": [{name, loop (once | loop | hold), length (seconds), animators: {<bone uuid>:
//   {name, type: bone, keyframes: [{channel: rotation | position | scale, time (seconds), interpolation
//   (linear | catmullrom | bezier | step), data_points: [{x, y, z}] (two for a jump: before, after)}]}}]}.
//   Since Blockbench 5.0 the values are as its keyframe panel shows them; older files keep rotation X/Y
//   and position X the other way round, as Bedrock files do.
// - .animation.json (Bedrock): {animations: {<name>: {loop (true | false | hold_on_last_frame),
//   animation_length (seconds), bones: {<bone>: {rotation | position | scale: [x, y, z] (all the time) or
//   {"<seconds>": [x, y, z] | {pre, post, lerp_mode (linear | catmullrom)}}}}}}}. Rotations in degrees,
//   applied Z, then Y, then X (the matrix Rz·Ry·Rx); values may be Molang text.
//
// Bones by name, case and separators ignored: head; body or torso (turns the torso; moves the whole
// body); waist (the same, about the hips); root (moves and spins the whole body); right arm, left arm
// (and their Position Y: the shoulders); right leg, left leg. Times: 20 ticks a second. Easing: linear
// stays linear, step stays step, catmullrom / smooth / bezier become ease in and out.

import {bbRotation, yawPitchRoll, fromShown} from './axes.js';
import * as A from './anim.js';

const TPS = A.TICKS_PER_SECOND;

/** Bone names (lower case, letters and digits only) to what they move here. */
const BONES = {
  head: 'head',
  body: 'body', torso: 'body', waist: 'waist', root: 'root',
  rightarm: 'right_arm', leftarm: 'left_arm', rightleg: 'right_leg', leftleg: 'left_leg',
  armright: 'right_arm', armleft: 'left_arm', legright: 'right_leg', legleft: 'left_leg',
};

const PART_WORDS = {head: 'Head', body: 'Torso', waist: 'Torso (from the waist)', root: 'Whole body', right_arm: 'Right arm', left_arm: 'Left arm', right_leg: 'Right leg', left_leg: 'Left leg'};

export function boneOf(name) {
  return BONES[String(name || '').toLowerCase().replace(/[^a-z0-9]/g, '')] || null;
}

// ---------------------------------------------------------------- reading files

/** A number from a value that may be text (Molang); NaN when it is an expression we cannot read. */
function num(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v === 'string' && /^\s*[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?\s*$/i.test(v)) return Number(v);
  if (v === undefined || v === null || v === '') return 0;
  return NaN;
}

/** [x, y, z] from a Bedrock value: [x, y, z], [v] or v (all three the same). */
function vec(v) {
  if (Array.isArray(v)) return v.length === 1 ? [num(v[0]), num(v[0]), num(v[0])] : [num(v[0]), num(v[1]), num(v[2])];
  if (v && typeof v === 'object' && ('x' in v || 'y' in v || 'z' in v)) return [num(v.x), num(v.y), num(v.z)];
  const n = num(v);
  return [n, n, n];
}

function compareVersion(a, b) {
  const pa = String(a || '0').split('.').map(Number), pb = String(b || '0').split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d;
  }
  return 0;
}

/**
 * Reads a file's text: {kind ('bbmodel' | 'bedrock'), flipped (true when rotation X/Y and position X are
 * the Bedrock way round), animations: [{name, loop, hold, length (seconds), bones: {<name>: {rotation,
 * position, scale: [keyframe]}}}], rest: {<bone name>: [x, y, z] rest rotation}}, where a keyframe is
 * {time, pre, post, interp}. Throws Error with a message for people when it cannot.
 */
export function readFile(name, text) {
  let data;
  try {
    data = JSON.parse(String(text).replace(/^﻿/, ''));
  } catch {
    throw new Error(`${name} is not a Blockbench file (it is not JSON).`);
  }
  if (data && Array.isArray(data.animations) && (data.meta || data.outliner || data.elements)) return readBbmodel(data);
  if (data && data.animations && typeof data.animations === 'object' && !Array.isArray(data.animations)) return readBedrock(data);
  if (data && data.meta) throw new Error(`${name} has no animations: make them in Blockbench's Animate tab and save again.`);
  throw new Error(`${name} is neither a .bbmodel nor a Bedrock .animation.json.`);
}

function readBedrock(data) {
  const animations = [];
  for (const [animName, a] of Object.entries(data.animations || {})) {
    if (!a || typeof a !== 'object') continue;
    const bones = {};
    for (const [boneName, b] of Object.entries(a.bones || {})) {
      if (!b || typeof b !== 'object') continue;
      const channels = {};
      for (const channel of ['rotation', 'position', 'scale']) {
        if (b[channel] === undefined) continue;
        channels[channel] = bedrockKeys(b[channel]);
      }
      bones[boneName] = channels;
    }
    animations.push({
      name: animName, loop: a.loop === true, hold: a.loop === 'hold_on_last_frame',
      length: num(a.animation_length) || 0, bones,
    });
  }
  if (!animations.length) throw new Error('This animation file has no animations in it.');
  return {kind: 'bedrock', flipped: true, animations, rest: {}};
}

/** A Bedrock channel: a value all the time, or keyframes by time. */
function bedrockKeys(v) {
  if (Array.isArray(v) || typeof v !== 'object' || v === null) {
    const value = vec(v);
    return [{time: 0, pre: value, post: value, interp: 'linear', always: true}];
  }
  const keys = [];
  for (const [time, k] of Object.entries(v)) {
    const t = num(time);
    if (!Number.isFinite(t)) continue;
    if (k && typeof k === 'object' && !Array.isArray(k)) {
      const post = vec(k.post !== undefined ? k.post : k.pre);
      const pre = k.pre !== undefined ? vec(k.pre) : post;
      keys.push({time: t, pre, post, interp: k.lerp_mode === 'catmullrom' ? 'catmullrom' : k.lerp_mode === 'step' ? 'step' : 'linear'});
    } else {
      const value = vec(k);
      keys.push({time: t, pre: value, post: value, interp: 'linear'});
    }
  }
  return keys.sort((a, b) => a.time - b.time);
}

function readBbmodel(data) {
  const meta = data.meta || {};
  // Blockbench 5.0 stopped turning rotation X/Y and position X round in its files.
  const flipped = compareVersion(meta.format_version, '5.0') < 0;
  const rest = {};
  const groups = [];
  if (Array.isArray(data.groups)) groups.push(...data.groups);
  const walk = list => {
    for (const node of list || []) {
      if (node && typeof node === 'object') {
        if (node.name !== undefined) groups.push(node);
        walk(node.children);
      }
    }
  };
  walk(data.outliner);
  for (const g of groups) if (g && g.name) rest[g.name] = vec(g.rotation || [0, 0, 0]);

  const animations = [];
  for (const a of data.animations || []) {
    if (!a || typeof a !== 'object') continue;
    const bones = {};
    for (const animator of Object.values(a.animators || {})) {
      if (!animator || (animator.type && animator.type !== 'bone') || !animator.name) continue;
      const channels = {};
      for (const k of animator.keyframes || []) {
        if (!['rotation', 'position', 'scale'].includes(k.channel)) continue;
        const points = (k.data_points || []).map(vec);
        if (!points.length) continue;
        (channels[k.channel] = channels[k.channel] || []).push({
          time: num(k.time) || 0, pre: points[0], post: points[1] || points[0],
          interp: ['linear', 'catmullrom', 'bezier', 'step'].includes(k.interpolation) ? k.interpolation : 'linear',
        });
      }
      for (const list of Object.values(channels)) list.sort((x, y) => x.time - y.time);
      bones[animator.name] = channels;
    }
    animations.push({name: a.name || 'animation', loop: a.loop === 'loop', hold: a.loop === 'hold', length: num(a.length) || 0, bones});
  }
  if (!animations.length) throw new Error('This .bbmodel has no animations: make them in Blockbench\'s Animate tab and save again.');
  return {kind: 'bbmodel', flipped, animations, rest};
}

// ---------------------------------------------------------------- sampling a channel

const SMOOTH = new Set(['catmullrom', 'bezier', 'smooth']);

/** A channel's value at a time (seconds), with its own keyframes' interpolation. */
export function valueAt(keys, t) {
  if (!keys.length) return [0, 0, 0];
  if (t <= keys[0].time) return keys[0].time === t ? keys[0].post : keys[0].pre;
  const last = keys[keys.length - 1];
  if (t >= last.time) return last.post;
  let i = 0;
  while (keys[i + 1].time <= t) i++;
  const a = keys[i], b = keys[i + 1];
  if (t === a.time) return a.post;
  const u = (t - a.time) / (b.time - a.time);
  if (a.interp === 'step') return a.post;
  if (SMOOTH.has(a.interp) || SMOOTH.has(b.interp)) {
    // Catmull-Rom through the keyframes either side (the ends repeat).
    const p0 = i > 0 ? keys[i - 1].post : a.post, p3 = i + 2 < keys.length ? keys[i + 2].pre : b.pre;
    return a.post.map((p1, c) => {
      const p2 = b.pre[c], q0 = p0[c], q3 = p3[c];
      return 0.5 * ((2 * p1) + (-q0 + p2) * u + (2 * q0 - 5 * p1 + 4 * p2 - q3) * u * u + (-q0 + 3 * p1 - 3 * p2 + q3) * u * u * u);
    });
  }
  return a.post.map((p, c) => p + (b.pre[c] - p) * u);
}

/** How a channel arrives at time t from time s: 'step', 'smooth', 'linear', or null when it does not move. */
function arrival(keys, s, t) {
  const from = valueAt(keys, s), to = valueAt(keys, t);
  if (from.every((v, c) => Math.abs(v - to[c]) < 1e-6)) return null;
  let i = keys.length - 1;
  while (i > 0 && keys[i].time >= t) i--;
  const a = keys[i], b = keys[Math.min(i + 1, keys.length - 1)];
  if (a.interp === 'step') return 'step';
  if (SMOOTH.has(a.interp) || SMOOTH.has(b.interp)) return 'smooth';
  return 'linear';
}

// ---------------------------------------------------------------- converting

function unwrap(prev, v) {
  if (prev === undefined) return v;
  while (v - prev > 180) v -= 360;
  while (v - prev < -180) v += 360;
  return v;
}

function round3(v) {
  const r = Math.round(v * 1000) / 1000;
  return Object.is(r, -0) ? 0 : r;
}

/**
 * Converts one read animation (from readFile) to a Lively NPCs animation map ({name, length, loop, glance,
 * keyframes: [{at, ease, values}]}) and a report: {mapped: [text], approximated: [text], ignored: [text]}.
 */
export function convert(file, anim) {
  const mapped = [], approximated = [], ignored = [];
  const once = (list, text) => { if (!list.includes(text)) list.push(text); };
  const flip = file.flipped;
  // Blockbench 5's numbers from the file's: Bedrock files turn rotation X/Y and position X the other way.
  const asShown = (channel, v) => (flip ? (channel === 'rotation' ? [-v[0], -v[1], v[2]] : channel === 'position' ? [-v[0], v[1], v[2]] : v) : v);

  // Which bones feed which part.
  const used = {};
  let molang = false;
  for (const [boneName, channels] of Object.entries(anim.bones)) {
    const part = boneOf(boneName);
    const listed = Object.entries(channels).filter(([, keys]) => keys.length);
    if (!listed.length) continue;
    for (const [, keys] of listed) if (keys.some(k => [...k.pre, ...k.post].some(Number.isNaN))) molang = true;
    if (!part) {
      once(ignored, `${boneName}: not a part of the NPC's rig`);
      continue;
    }
    used[part] = {name: boneName, channels: Object.fromEntries(listed.map(([c, keys]) => [c, keys.map(k => ({...k,
      pre: asShown(c, k.pre.map(v => (Number.isNaN(v) ? 0 : v))), post: asShown(c, k.post.map(v => (Number.isNaN(v) ? 0 : v)))}))]))};
    const rest = file.rest[boneName];
    if (rest && rest.some(v => Math.abs(v) > 1e-6)) once(approximated, `${boneName} is turned in the model itself (${rest.map(round3).join(', ')}): only its animation is used`);
  }
  if (molang) once(approximated, 'Values written as Molang expressions cannot be worked out here: they count as 0');

  // The ticks of the keyframes: every keyframe of a bone that maps, rounded to whole ticks (20 a second).
  const times = new Set([0]);
  let merged = false;
  for (const {channels} of Object.values(used)) {
    for (const [channel, keys] of Object.entries(channels)) {
      if (channel === 'scale') continue;
      for (const k of keys) {
        const at = Math.max(0, Math.round(k.time * TPS));
        if (Math.abs(at - k.time * TPS) > 1e-6) merged = true;
        times.add(at);
      }
    }
  }
  const lastKey = Math.max(...times);
  let length = Math.max(1, Math.round(anim.length * TPS), lastKey);
  if (anim.length && Math.abs(anim.length * TPS - Math.round(anim.length * TPS)) > 1e-6) merged = true;
  if (merged) once(approximated, 'Times were rounded to whole ticks (20 a second)');
  // A jump (a keyframe with different values just before and after it, or the end of a step) holds
  // until one tick before, then steps: the keyframe there gets "step", whatever else moves into it.
  const jumps = new Set();
  const differs = (p, q) => p.some((v, c) => Math.abs(v - q[c]) > 1e-6);
  for (const {channels} of Object.values(used)) {
    for (const [channel, keys] of Object.entries(channels)) {
      if (channel === 'scale') continue;
      keys.forEach((k, i) => {
        const at = Math.round(k.time * TPS);
        const stepped = i > 0 && keys[i - 1].interp === 'step' && differs(keys[i - 1].post, k.pre);
        if (at > 0 && (stepped || differs(k.pre, k.post))) {
          jumps.add(at);
          times.add(at - 1);
        }
      });
    }
  }
  const ticks = [...times].sort((a, b) => a - b);

  // Each part's channels at a tick.
  const sampleTick = tick => {
    const t = tick / TPS;
    const values = {};
    const get = (part, channel) => {
      const u = used[part];
      const keys = u && u.channels[channel];
      return keys ? valueAt(keys, t) : null;
    };
    // Arms and legs: exactly Blockbench's numbers through the table.
    for (const [part, keysOf] of [['right_arm', ['right-arm-forward', 'right-arm-across', 'right-arm-out']], ['left_arm', ['left-arm-forward', 'left-arm-across', 'left-arm-out']],
      ['right_leg', ['right-leg-forward', null, 'right-leg-out']], ['left_leg', ['left-leg-forward', null, 'left-leg-out']]]) {
      const r = get(part, 'rotation');
      if (!r) continue;
      keysOf.forEach((key, i) => { if (key) values[key] = fromShown(key, r[i]); });
    }
    // Head and torso: the same turn, as yaw, pitch and roll.
    const head = get('head', 'rotation');
    if (head) {
      const {yaw, pitch, roll} = yawPitchRoll(bbRotation(...head));
      Object.assign(values, {'head-yaw': yaw, 'head-pitch': pitch, 'head-roll': roll});
    }
    const waist = get('waist', 'rotation'), body = get('body', 'rotation');
    if (waist || body) {
      const q = waist && body ? mulQ(bbRotation(...waist), bbRotation(...body)) : bbRotation(...(waist || body));
      const {yaw, pitch, roll} = yawPitchRoll(q);
      Object.assign(values, {'torso-yaw': yaw, 'torso-pitch': pitch, 'torso-roll': roll});
    }
    // The whole body: root's spin, and every moving bone's position summed.
    const root = get('root', 'rotation');
    if (root) values['root-yaw'] = fromShown('root-yaw', root[1]);
    const pos = [0, 0, 0];
    let moves = false;
    for (const part of ['root', 'waist', 'body']) {
      const p = get(part, 'position');
      if (!p) continue;
      moves = true;
      p.forEach((v, i) => { pos[i] += v; });
    }
    if (moves) {
      values['root-x'] = fromShown('root-x', pos[0]);
      values['root-y'] = fromShown('root-y', pos[1]);
      values['root-z'] = fromShown('root-z', pos[2]);
    }
    // Shoulders: the arms' Position Y (both, or the one that moves).
    const ry = get('right_arm', 'position'), ly = get('left_arm', 'position');
    if (ry || ly) values.shoulders = ry && ly ? (ry[1] + ly[1]) / 2 : (ry || ly)[1];
    return values;
  };

  // The report: what each bone became, and what was left out or bent.
  const ROT_TO = {head: 'Head', body: 'Torso', waist: 'Torso', root: 'Whole body (spin)', right_arm: 'Right arm', left_arm: 'Left arm', right_leg: 'Right leg', left_leg: 'Left leg'};
  const POS_TO = {root: 'Whole body', waist: 'Whole body', body: 'Whole body', right_arm: 'shoulders (Position Y)', left_arm: 'shoulders (Position Y)'};
  for (const [part, {name, channels}] of Object.entries(used)) {
    const nonzero = (channel, i) => (channels[channel] || []).some(k => Math.abs(k.pre[i]) > 1e-6 || Math.abs(k.post[i]) > 1e-6);
    const targets = [];
    if (channels.rotation) targets.push(`rotation → ${ROT_TO[part]}`);
    if (channels.position && POS_TO[part]) targets.push(`position → ${POS_TO[part]}`);
    if (targets.length) once(mapped, `${name}: ${targets.join(', ')}`);
    if (channels.scale) once(ignored, `${name} scale: NPCs cannot grow or shrink`);
    if (channels.position && ['head', 'right_leg', 'left_leg'].includes(part)) once(ignored, `${name} position: the ${PART_WORDS[part].toLowerCase()} stays attached`);
    if (channels.position && part.endsWith('_arm') && (nonzero('position', 0) || nonzero('position', 2))) once(ignored, `${name} position X and Z: arms stay at the shoulders (only Y, the shrug, is kept)`);
    if (part.endsWith('_leg') && nonzero('rotation', 1)) once(ignored, `${name} rotation Y: legs cannot twist`);
    if (part === 'root' && (nonzero('rotation', 0) || nonzero('rotation', 2))) once(ignored, `${name} rotation X and Z: the whole body only spins (Y)`);
    if (part === 'body' && channels.rotation) once(approximated, `${name} turns the torso at the hips here (a player model's body bone turns at the neck)`);
    if (part === 'body' && channels.position) once(approximated, `${name} position moves the whole NPC here, legs too`);
  }
  if (used.right_arm && used.left_arm && used.right_arm.channels.position && used.left_arm.channels.position) {
    const differ = ticks.some(t => {
      const tt = t / TPS;
      return Math.abs(valueAt(used.right_arm.channels.position, tt)[1] - valueAt(used.left_arm.channels.position, tt)[1]) > 1e-6;
    });
    if (differ) once(approximated, 'The arms\' Position Y differ: both shoulders lift by their average');
  }

  // The keyframes: values at each tick, the easing they arrive with.
  const keyframes = [];
  const prevAngles = {};
  let headClamped = false, mixed = false;
  for (let i = 0; i < ticks.length; i++) {
    const tick = ticks[i];
    const raw = sampleTick(tick);
    for (const key of ['head-yaw', 'head-pitch', 'head-roll', 'torso-yaw', 'torso-pitch', 'torso-roll']) {
      if (raw[key] === undefined) continue;
      raw[key] = unwrap(prevAngles[key], raw[key]);
      prevAngles[key] = raw[key];
    }
    if ((raw['head-yaw'] !== undefined && Math.abs(A.wrapTurn(raw['head-yaw'])) > 80) || (raw['head-pitch'] !== undefined && Math.abs(raw['head-pitch']) > 60)) headClamped = true;
    let ease = 'linear';
    if (i > 0) {
      if (jumps.has(tick)) ease = 'step';
      else {
        const prev = ticks[i - 1];
        const kinds = new Set();
        for (const {channels} of Object.values(used)) {
          for (const [channel, keys] of Object.entries(channels)) {
            if (channel === 'scale') continue;
            const k = arrival(keys, prev / TPS, tick / TPS);
            if (k) kinds.add(k);
          }
        }
        if (kinds.has('smooth')) ease = 'ease-in-out';
        else if (kinds.size === 1 && kinds.has('step')) ease = 'step';
        if (kinds.size > 1) mixed = true;
      }
    }
    const values = {};
    for (const [key, v] of Object.entries(raw)) {
      const r = round3(v);
      if (r !== 0) values[key] = r;
    }
    keyframes.push({at: tick, ease, values});
  }
  if (keyframes.length > 1 && keyframes.every(k => !Object.keys(k.values).length)) once(ignored, 'Nothing it moves is a part of the NPC\'s rig');
  if (Object.values(used).some(u => Object.values(u.channels).some(keys => keys.some(k => k.interp === 'bezier')))) once(approximated, 'Bezier handles are not kept: those moves ease in and out');
  if (Object.values(used).some(u => Object.values(u.channels).some(keys => keys.some(k => SMOOTH.has(k.interp))))) once(approximated, 'Smooth (catmullrom) moves become "ease in and out" between keyframes');
  if (mixed) once(approximated, 'Where parts arrive with different easings at the same moment, the smoothest one is used for all');
  if (jumps.size) once(approximated, 'Jumps (a keyframe with a value just before and another after) hold, then step one tick before');
  if (headClamped) once(approximated, 'The game turns a head at most 80° and nods it at most 60°');
  if (anim.hold) once(approximated, '"Hold on last frame": in game a one-shot animation goes back to rest when it ends');
  length = Math.max(length, keyframes[keyframes.length - 1].at);
  const result = A.normalize({id: 'import', name: anim.name, length, loop: anim.loop, glance: false, keyframes});
  if (!result.loop && !A.endsAtRest(result)) once(approximated, 'It does not end at rest: the NPC jumps back to its own pose when it ends (the editor can add a rest keyframe)');
  return {animation: result, report: {mapped, approximated, ignored}};
}

function mulQ(a, b) {
  return [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
}

/** A good id for an imported animation: "Wave Hello" -> "wave_hello", not one of taken. */
export function suggestId(name, taken = new Set()) {
  let base = String(name || 'imported').toLowerCase().replace(/^animation\./, '').replace(/[^a-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 34) || 'imported';
  if (base === 'none') base = 'none_anim';
  if (!taken.has(base)) return base;
  for (let i = 2; ; i++) if (!taken.has(base + '_' + i)) return base + '_' + i;
}
