#!/usr/bin/env node
// Tests of the web editor's own logic, under node: Blockbench's axes and signs (axes.js), snapping
// (snap.js), the graph editor's curves (graph.js) and the Blockbench importer (bbimport.js).
// tools/check-editor-pose.mjs runs these too.
//
//   node tools/editor-tests.mjs
//
// Exit code 0 when everything passes, 1 otherwise. Needs only node.

import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..', 'src', 'main', 'resources', 'web', 'js');
const SAMPLES = path.resolve(HERE, '..', 'src', 'test', 'resources', 'blockbench');
const load = name => import(new URL('file:///' + path.join(WEB, name).replace(/\\/g, '/')).href);

let passed = 0, failed = 0;
const fails = [];

function check(ok, what) {
  if (ok) passed++;
  else {
    failed++;
    fails.push(what);
  }
}

const near = (a, b, eps = 1e-6) => Math.abs(a - b) <= eps;
const nearVec = (a, b, eps = 1e-6) => a.length === b.length && a.every((x, i) => near(x, b[i], eps));
const sameTurn = (p, q, eps = 1e-6) => nearVec(p, q, eps) || nearVec(p, q.map(x => -x), eps);

// ---------------------------------------------------------------- axes.js

async function testAxes() {
  const Ax = await load('axes.js');
  const Rig = await load('rig.js');
  const An = await load('anim.js');
  const before = failed;

  // Every channel is shown somewhere, signs are ±1, labels are Blockbench's.
  for (const key of An.DEFAULT_CHANNELS) check(Ax.ROWS.some(r => r.key === key), `axes: ${key} is shown in some part`);
  for (const r of Ax.ROWS) {
    check(r.sign === 1 || r.sign === -1, `axes: ${r.part} ${r.label} has a sign of ±1`);
    check(/^(Rotation|Position) [XYZ]$/.test(r.label), `axes: ${r.label} is a Blockbench name`);
    check(r.hint && r.hint.length > 10, `axes: ${r.part} ${r.label} has a hint`);
    for (const v of [0, 1, -37.5, 220]) check(Ax.fromShown(r.key, Ax.toShown(r.key, v)) === v, `axes: ${r.key} ${v} goes there and back`);
  }
  check(Ax.rowsFor('right_arm').some(r => r.key === 'shoulders') && Ax.rowsFor('left_arm').some(r => r.key === 'shoulders'), 'axes: both arms show shoulders as Position Y');
  check(nearVec(Ax.shownRange('torso-pitch', -45, 90), [-90, 45]) && nearVec(Ax.shownRange('right-arm-forward', -90, 220), [-90, 220]),
    'axes: a flipped channel\'s slider range flips, a kept one stays');

  // The table matches what Blockbench 5's values mean on a player model, checked on the rig itself.
  // In the rig's frame the NPC faces +z and its right is -x.
  const pose = shown => {
    const values = {};
    for (const [part, label, v] of shown) {
      const r = Ax.rowsFor(part).find(x => x.label === label);
      values[r.key] = (values[r.key] || 0) + Ax.fromShown(r.key, v);
    }
    return Rig.pose(values, {});
  };
  const tip = (posed, part, v) => Rig.rotate(posed.parts[part].rotation, v);
  const facts = [
    // [what (Blockbench 5's keyframe panel), shown values, test]
    ['head Rotation X + looks up', [['head', 'Rotation X', 30]], p => tip(p, 'head', [0, 0, 1])[1] > 0.4],
    ['head Rotation X - looks down', [['head', 'Rotation X', -30]], p => tip(p, 'head', [0, 0, 1])[1] < -0.4],
    ['head Rotation Y + turns to its left', [['head', 'Rotation Y', 30]], p => tip(p, 'head', [0, 0, 1])[0] > 0.4],
    ['head Rotation Z + tilts its top to its left', [['head', 'Rotation Z', 20]], p => tip(p, 'head', [0, 1, 0])[0] > 0.3],
    ['torso Rotation X + leans back', [['body', 'Rotation X', 30]], p => tip(p, 'body', [0, 1, 0])[2] < -0.4],
    ['torso Rotation Y + twists to its left', [['body', 'Rotation Y', 30]], p => tip(p, 'body', [0, 0, 1])[0] > 0.4],
    ['torso Rotation Z + leans to its left', [['body', 'Rotation Z', 20]], p => tip(p, 'body', [0, 1, 0])[0] > 0.3],
    ['right arm Rotation X 90 points ahead', [['right_arm', 'Rotation X', 90]], p => tip(p, 'right_arm', [0, -12, 0])[2] > 11.9],
    ['left arm Rotation X 90 points ahead', [['left_arm', 'Rotation X', 90]], p => tip(p, 'left_arm', [0, -12, 0])[2] > 11.9],
    ['right arm Rotation X - swings back', [['right_arm', 'Rotation X', -30]], p => tip(p, 'right_arm', [0, -12, 0])[2] < -5],
    ['right arm Rotation Z + swings out to its right', [['right_arm', 'Rotation Z', 40]], p => tip(p, 'right_arm', [0, -12, 0])[0] < -7],
    ['left arm Rotation Z - swings out to its left', [['left_arm', 'Rotation Z', -40]], p => tip(p, 'left_arm', [0, -12, 0])[0] > 7],
    ['right arm Rotation Y + (raised) turns to its left, across', [['right_arm', 'Rotation X', 90], ['right_arm', 'Rotation Y', 30]], p => tip(p, 'right_arm', [0, -12, 0])[0] > 5],
    ['left arm Rotation Y + (raised) turns to its left, out', [['left_arm', 'Rotation X', 90], ['left_arm', 'Rotation Y', 30]], p => tip(p, 'left_arm', [0, -12, 0])[0] > 5],
    ['right leg Rotation X + swings forward', [['right_leg', 'Rotation X', 30]], p => tip(p, 'right_leg', [0, -12, 0])[2] > 5],
    ['right leg Rotation Z + swings out to its right', [['right_leg', 'Rotation Z', 30]], p => tip(p, 'right_leg', [0, -12, 0])[0] < -5],
    ['left leg Rotation Z - swings out to its left', [['left_leg', 'Rotation Z', -30]], p => tip(p, 'left_leg', [0, -12, 0])[0] > 5],
    ['whole body Rotation Y + spins to its left', [['root', 'Rotation Y', 90]], p => Rig.rotate(p.Rb, [0, 0, 1])[0] > 0.99],
    ['whole body Position X + moves to its right', [['root', 'Position X', 3]], p => near(p.o[0], -3) && near(p.o[2], 0)],
    ['whole body Position Y + rises', [['root', 'Position Y', 3]], p => near(p.o[1], 3)],
    ['whole body Position Z + moves back', [['root', 'Position Z', 3]], p => near(p.o[2], -3)],
    ['arm Position Y + lifts the shoulders', [['right_arm', 'Position Y', 1]], p => near(p.parts.right_arm.origin[1], 25) && near(p.parts.left_arm.origin[1], 25)],
  ];
  for (const [what, shown, test] of facts) check(test(pose(shown)), 'axes: ' + what);

  // Blockbench turns Z, then Y, then X: an arm's three values are exactly Blockbench's rotation.
  const rnd = (i, span) => ((Math.sin(i * 12.9898) * 43758.5453) % 1) * span;
  for (let i = 1; i <= 40; i++) {
    const x = rnd(i, 170), y = rnd(i + 100, 80), z = rnd(i + 200, 120);
    for (const part of ['right_arm', 'left_arm']) {
      const p = pose([[part, 'Rotation X', x], [part, 'Rotation Y', y], [part, 'Rotation Z', z]]);
      check(sameTurn(p.parts[part].rotation, Ax.bbRotation(x, y, z)), `axes: ${part} (${x.toFixed(1)}, ${y.toFixed(1)}, ${z.toFixed(1)}) is Blockbench's rotation`);
    }
    // Head and torso turn yaw, pitch, roll: the importer's conversion gives the same turn.
    const q = Ax.bbRotation(x / 3, y, z / 3);
    const {yaw, pitch, roll} = Ax.yawPitchRoll(q);
    const head = Rig.pose({'head-yaw': yaw, 'head-pitch': pitch, 'head-roll': roll}, {});
    if (Math.abs(yaw) <= 80 && Math.abs(pitch) <= 60) check(sameTurn(head.parts.head.rotation, q), `axes: head (${x.toFixed(1)}, ${y.toFixed(1)}, ${z.toFixed(1)}) converts to the same turn`);
  }
  // One value at a time, head and torso match Blockbench's rotation too (only their order differs).
  for (const part of ['head', 'body']) {
    for (const [label, idx] of [['Rotation X', 0], ['Rotation Y', 1], ['Rotation Z', 2]]) {
      const xyz = [0, 0, 0];
      xyz[idx] = 25;
      check(sameTurn(pose([[part, label, 25]]).parts[part].rotation, Ax.bbRotation(...xyz)), `axes: ${part} ${label} alone is Blockbench's rotation`);
    }
  }
  console.log(`${failed === before ? 'ok  ' : 'BAD '} axes.js: Blockbench's names and signs for ${Ax.ROWS.length} values, checked on the rig`);
}

// ---------------------------------------------------------------- snap.js

async function testSnap() {
  const Sn = await load('snap.js');
  const before = failed;
  const cases = [
    [Sn.snapTick(7, 5), 5], [Sn.snapTick(8, 5), 10], [Sn.snapTick(8, 5, true), 8], [Sn.snapTick(13.6, 1), 14], [Sn.snapTick(26, 10), 30],
    [Sn.snapTick(24, 10), 20], [Sn.snapTick(3, 2), 4], [Sn.snapTick(-0.2, 1), 0],
    [Sn.snapAngle(37, 15), 30], [Sn.snapAngle(38, 15), 45], [Sn.snapAngle(-37, 15), -30], [Sn.snapAngle(-38, 15), -45],
    [Sn.snapAngle(37.26, 0), 37.3], [Sn.snapAngle(37.26, 5, true), 37.3], [Sn.snapAngle(12.4, 5), 10], [Sn.snapAngle(12.6, 5), 15],
    [Sn.snapPixels(1.13), 1.25], [Sn.snapPixels(1.13, true), 1.13], [Sn.snapPixels(-0.1), 0],
  ];
  cases.forEach(([got, want], i) => check(Object.is(got, want), `snap: case ${i + 1} gives ${got}, want ${want}`));
  check(Sn.TICK_STEPS.join() === '1,2,5,10' && Sn.ANGLE_STEPS.join() === '0,5,15', 'snap: the choices are 1/2/5/10 ticks and off/5/15 degrees');
  for (const px of [0.5, 1, 2, 4, 8, 20, 40]) {
    const r = Sn.rulerSteps(px);
    check(r.minor * px >= 4 || r.minor === 10, `snap: ruler marks at ${px} px a tick are at least 4 px apart`);
    check(20 % r.minor === 0 && r.mid % r.minor === 0, `snap: ruler marks at ${px} px a tick fall on every second`);
  }
  console.log(`${failed === before ? 'ok  ' : 'BAD '} snap.js: keyframe times, angles, positions and the ruler`);
}

// ---------------------------------------------------------------- onion.js

async function testOnion() {
  const On = await load('onion.js');
  const before = failed;
  const keys = [0, 10, 20, 40].map(at => ({at, ease: 'linear', values: {}}));
  const once = {length: 40, loop: false, keyframes: keys};
  const loop = {length: 48, loop: true, keyframes: keys};
  const ticks = (anim, t, mode) => On.onionTicks(anim, t, mode).map(g => `${g.side[0]}${g.tick}${g.near ? '' : '~'}`).join(' ');
  const cases = [
    [ticks(once, 15, 'keys'), 'b10 a20'], [ticks(once, 10, 'keys'), 'b0 a20'], [ticks(once, 0, 'keys'), 'a10'],
    [ticks(once, 40, 'keys'), 'b20'], [ticks(loop, 0, 'keys'), 'b40 a10'], [ticks(loop, 44, 'keys'), 'b40 a0'],
    [ticks(once, 12, 'ticks:5'), 'b7 b2~ a17 a22~'], [ticks(once, 2, 'ticks:5'), 'a7 a12~'], [ticks(once, 38, 'ticks:4'), 'b34 b30~'],
    [ticks(loop, 2, 'ticks:5'), 'b45 b40~ a7 a12~'], [ticks(null, 3, 'keys'), ''],
  ];
  cases.forEach(([got, want], i) => check(got === want, `onion: case ${i + 1} gives '${got}', want '${want}'`));
  console.log(`${failed === before ? 'ok  ' : 'BAD '} onion.js: which poses the onion skin shows`);
}

// ---------------------------------------------------------------- run

export async function run({quiet = false} = {}) {
  const tests = [testAxes, testSnap, testOnion];
  for (const extra of ['graph', 'import']) {
    const file = extra === 'graph' ? 'graph.js' : 'bbimport.js';
    if (fs.existsSync(path.join(WEB, file))) tests.push(extra === 'graph' ? testGraph : testImport);
  }
  for (const t of tests) await t();
  for (const f of fails.slice(0, 40)) console.log('  FAIL ' + f);
  if (fails.length > 40) console.log(`  … and ${fails.length - 40} more`);
  if (!quiet) console.log(failed ? `${failed} of ${passed + failed} checks failed` : `All ${passed} editor checks pass.`);
  return {passed, failed};
}

async function testGraph() {
  const G = await load('graph.js');
  const An = await load('anim.js');
  const before = failed;
  // Easing.apply, written out again here from anim/Easing.java.
  const easing = {
    'linear': f => f, 'ease-in': f => f * f, 'ease-out': f => 1 - (1 - f) * (1 - f),
    'ease-in-out': f => (f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2), 'step': f => (f >= 1 ? 1 : 0),
  };
  for (const ease of Object.keys(easing)) {
    const anim = An.normalize({id: 't', length: 30, keyframes: [{at: 0, values: {'head-yaw': 10}}, {at: 20, ease, values: {'head-yaw': 50}}]});
    const pts = G.curve(anim, 'head-yaw', 0.5);
    // Every point is exactly what the game plays at that tick.
    check(pts.every(([t, v]) => near(v, An.sample(anim, t, ['head-yaw'])['head-yaw'], 1e-4)), `graph: ${ease} curve points are Animation.sample`);
    for (const f of [0.25, 0.5, 0.75]) {
      const t = 20 * f;
      const p = pts.find(([x]) => near(x, t, 1e-9));
      check(p && near(p[1], 10 + 40 * easing[ease](f), 1e-4), `graph: ${ease} at ${f} of the way is ${10 + 40 * easing[ease](f)} (got ${p && p[1]})`);
    }
    check(near(pts[0][1], 10) && near(pts[pts.length - 1][0], 30) && near(pts[pts.length - 1][1], 50), `graph: ${ease} starts at the first keyframe and holds the last to the end`);
    if (ease === 'step') {
      const justBefore = pts.find(([x]) => x > 19.99 && x < 20);
      check(justBefore && near(justBefore[1], 10), 'graph: a step holds until its keyframe, then jumps (drawn upright)');
    }
  }
  // A loop glides back to its first keyframe by its length, with the first keyframe's easing.
  const loop = An.normalize({id: 'l', length: 40, loop: true, keyframes: [{at: 0, values: {'root-y': 0}}, {at: 20, ease: 'ease-out', values: {'root-y': 4}}]});
  const segs = G.segments(loop);
  check(segs.length === 2 && segs[1].from === 20 && segs[1].to === 40 && segs[1].b === 0, 'graph: a loop adds the glide back to the start');
  const lp = G.curve(loop, 'root-y', 1);
  check(near(lp[lp.length - 1][0], 40) && near(lp[lp.length - 1][1], 0), 'graph: the loop\'s curve ends back at the first value');
  check(lp.every(([t, v]) => t >= 40 || near(v, An.sample(loop, t, ['root-y'])['root-y'], 1e-4)), 'graph: loop curve points are Animation.sample');
  const r = G.range(loop, 'root-y');
  check(near(r[0], 0) && near(r[1], 4), 'graph: the range covers the curve and 0');
  check(G.segmentAt(loop, 30).from === 20 && G.segmentAt(loop, 5).to === 20, 'graph: clicking a tick finds its segment');
  check(G.gridStep(100, 5) === 20 && G.gridStep(7, 6) === 2 && G.gridStep(1, 4) === 0.25, 'graph: value grid lines at round steps');
  console.log(`${failed === before ? 'ok  ' : 'BAD '} graph.js: curves drawn exactly as the game plays them, every easing`);
}
async function testImport() {
  const Bb = await load('bbimport.js');
  const Ax = await load('axes.js');
  const An = await load('anim.js');
  const Rig = await load('rig.js');
  const before = failed;
  const bbText = fs.readFileSync(path.join(SAMPLES, 'wave.bbmodel'), 'utf8');
  const bedText = fs.readFileSync(path.join(SAMPLES, 'wave.animation.json'), 'utf8');

  // Reading the files.
  const bb = Bb.readFile('wave.bbmodel', bbText);
  check(bb.kind === 'bbmodel' && !bb.flipped && bb.animations.length === 2, 'import: a Blockbench 5 .bbmodel with two animations');
  check(bb.animations[0].bones.rightArm && !bb.animations[0].bones.Effects, 'import: bone animators are read, effect animators are not');
  const bed = Bb.readFile('wave.animation.json', bedText);
  check(bed.kind === 'bedrock' && bed.flipped && bed.animations.length === 2, 'import: a Bedrock .animation.json with two animations');
  for (const [text, what] of [['not json', 'not JSON'], ['{}', 'no animations'], ['{"meta": {"format_version": "5.0"}, "animations": []}', 'a .bbmodel without animations']]) {
    let threw = false;
    try {
      Bb.readFile('x', text);
    } catch (e) {
      threw = e.message.length > 10;
    }
    check(threw, `import: ${what} is refused with a message`);
  }

  // The wave from the .bbmodel.
  const wave = Bb.convert(bb, bb.animations[0]);
  const a = wave.animation;
  const ats = a.keyframes.map(k => k.at).join(',');
  check(ats === '0,4,5,7,8,10,15,20,32', `import: keyframes at every source keyframe, in ticks, and a tick before a jump (got ${ats})`);
  check(a.length === 40 && a.loop === false, 'import: 2 seconds is 40 ticks, "once" plays once');
  const at = t => a.keyframes.find(k => k.at === t);
  check(near(at(10).values['right-arm-forward'], 165) && near(at(10).values['right-arm-out'], -30), 'import: right arm X 165, Z -30 is forward 165, out -30');
  check(at(5).ease === 'ease-in-out' && at(32).ease === 'ease-in-out', 'import: catmullrom becomes ease in and out');
  check(near(at(4).values['root-y'], 3) && at(8).ease === 'step' && !at(8).values['root-y'], 'import: a step hold, then the jump at the next keyframe');
  // The head's three values together turn it exactly as Blockbench would.
  const head = Rig.pose(at(10).values, {}).parts.head.rotation;
  check(sameTurn(head, Ax.bbRotation(-10, -20, 8), 1e-3), 'import: the head turns exactly as Blockbench turns it (X -10, Y -20, Z 8)');
  check(wave.report.mapped.some(m => m.startsWith('rightArm: rotation')) && wave.report.mapped.some(m => m.startsWith('root: position')), 'import: the report says what mapped');
  check(wave.report.ignored.some(m => m.startsWith('rightArm scale')), 'import: scale is reported as left out');
  check(wave.report.approximated.some(m => /catmullrom|ease in and out/.test(m)), 'import: smooth moves are reported as approximated');

  // The same wave from the Bedrock file (rotation X/Y the other way round) is the same motion.
  const wave2 = Bb.convert(bed, bed.animations[0]).animation;
  for (const k of a.keyframes) {
    const v1 = An.sample(a, k.at), v2 = An.sample(wave2, k.at);
    check(An.DEFAULT_CHANNELS.every(c => near(v1[c], v2[c], 1e-3)), `import: the Bedrock file gives the same pose at tick ${k.at}`);
  }
  for (let t = 0; t <= 12; t++) check(near(An.sample(a, t)['root-y'], An.sample(wave2, t)['root-y'], 1e-6), `import: the hop is the same at tick ${t} (pre/post jump vs step)`);

  // Extras: a value all the time, a leg's twist, Molang, a tail, a head's position, hold on last frame.
  const extras = Bb.convert(bed, bed.animations[1]);
  const e = extras.animation;
  check(e.keyframes.every(k => near(k.values['left-arm-out'] || 0, 20)), 'import: a value all the time ([0, 0, -20] on the left arm) is on every keyframe: out 20');
  const k10 = e.keyframes.find(k => k.at === 10);
  check(k10 && near(k10.values['right-leg-forward'], 30), 'import: Bedrock right leg X -30 swings it forward 30');
  const r = extras.report;
  check(r.ignored.some(m => m.startsWith('tail')), 'import: a bone the rig does not have is reported');
  check(r.ignored.some(m => m.startsWith('right_leg rotation Y')), 'import: a leg\'s twist is reported');
  check(r.ignored.some(m => m.startsWith('head position')), 'import: a head\'s position is reported');
  check(r.approximated.some(m => m.includes('Molang')), 'import: Molang is reported');
  check(r.approximated.some(m => m.includes('Hold on last frame')), 'import: hold on last frame is reported');
  check(r.approximated.some(m => m.includes('at the hips')), 'import: the body bone turning at the hips is reported');

  // A loop, and a file from before Blockbench 5 (rotation X/Y the Bedrock way round).
  const nod = Bb.convert(bb, bb.animations[1]).animation;
  check(nod.loop && nod.length === 20 && near(nod.keyframes.find(k => k.at === 5).values['head-pitch'], 15), 'import: a looping nod, X -15 looks down 15');
  const old = Bb.readFile('old.bbmodel', JSON.stringify({meta: {format_version: '4.10'}, outliner: [], animations: [{name: 'old', loop: 'once', length: 1,
    animators: {a: {name: 'RightArm', type: 'bone', keyframes: [{channel: 'rotation', time: 0.5, interpolation: 'linear', data_points: [{x: -90, y: 0, z: 0}]}]}}}]}));
  const oldAnim = Bb.convert(old, old.animations[0]).animation;
  check(old.flipped && near(oldAnim.keyframes.find(k => k.at === 10).values['right-arm-forward'], 90), 'import: a pre-5.0 .bbmodel\'s X -90 is an arm raised in front');

  // Names and ids.
  check(Bb.boneOf('RightArm') === 'right_arm' && Bb.boneOf('right_arm') === 'right_arm' && Bb.boneOf('rightArm') === 'right_arm' && Bb.boneOf('Torso') === 'body' && !Bb.boneOf('tail'), 'import: bone names, case and separators ignored');
  check(Bb.suggestId('animation.player.wave_hello') === 'player_wave_hello' && Bb.suggestId('Wave', new Set(['wave'])) === 'wave_2', 'import: ids made from the animation\'s name');
  console.log(`${failed === before ? 'ok  ' : 'BAD '} bbimport.js: .bbmodel and Bedrock files converted, with their report`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const {failed: f} = await run();
  process.exit(f ? 1 : 0);
}
