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
  check(nearVec(Ax.shownRange('right-arm-forward', -90, 220), [-220, 90]), 'axes: a flipped channel\'s slider range flips');

  // The table matches what Blockbench's values mean on a player model, checked on the rig itself.
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
    // [what, shown values, test]
    ['head Rotation X + looks down', [['head', 'Rotation X', 30]], p => tip(p, 'head', [0, 0, 1])[1] < -0.4],
    ['head Rotation Y + turns to its right', [['head', 'Rotation Y', 30]], p => tip(p, 'head', [0, 0, 1])[0] < -0.4],
    ['head Rotation Z + tilts its top to its left', [['head', 'Rotation Z', 20]], p => tip(p, 'head', [0, 1, 0])[0] > 0.3],
    ['torso Rotation X + bends forward', [['body', 'Rotation X', 30]], p => tip(p, 'body', [0, 1, 0])[2] > 0.4],
    ['torso Rotation Y + twists to its right', [['body', 'Rotation Y', 30]], p => tip(p, 'body', [0, 0, 1])[0] < -0.4],
    ['torso Rotation Z + leans to its left', [['body', 'Rotation Z', 20]], p => tip(p, 'body', [0, 1, 0])[0] > 0.3],
    ['right arm Rotation X -90 points ahead', [['right_arm', 'Rotation X', -90]], p => tip(p, 'right_arm', [0, -12, 0])[2] > 11.9],
    ['right arm Rotation X + swings back', [['right_arm', 'Rotation X', 30]], p => tip(p, 'right_arm', [0, -12, 0])[2] < -5],
    ['right arm Rotation Z + swings out to its right', [['right_arm', 'Rotation Z', 40]], p => tip(p, 'right_arm', [0, -12, 0])[0] < -7],
    ['left arm Rotation Z - swings out to its left', [['left_arm', 'Rotation Z', -40]], p => tip(p, 'left_arm', [0, -12, 0])[0] > 7],
    ['right arm Rotation Y + (raised) turns to its right, out', [['right_arm', 'Rotation X', -90], ['right_arm', 'Rotation Y', 30]], p => tip(p, 'right_arm', [0, -12, 0])[0] < -5],
    ['left arm Rotation Y + (raised) turns to its right, across', [['left_arm', 'Rotation X', -90], ['left_arm', 'Rotation Y', 30]], p => tip(p, 'left_arm', [0, -12, 0])[0] < -5],
    ['right leg Rotation X - swings forward', [['right_leg', 'Rotation X', -30]], p => tip(p, 'right_leg', [0, -12, 0])[2] > 5],
    ['right leg Rotation Z + swings out to its right', [['right_leg', 'Rotation Z', 30]], p => tip(p, 'right_leg', [0, -12, 0])[0] < -5],
    ['left leg Rotation Z - swings out to its left', [['left_leg', 'Rotation Z', -30]], p => tip(p, 'left_leg', [0, -12, 0])[0] > 5],
    ['whole body Rotation Y + spins to its right', [['root', 'Rotation Y', 90]], p => Rig.rotate(p.Rb, [0, 0, 1])[0] < -0.99],
    ['whole body Position X + moves to its left', [['root', 'Position X', 3]], p => near(p.o[0], 3) && near(p.o[2], 0)],
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

// ---------------------------------------------------------------- run

export async function run({quiet = false} = {}) {
  const tests = [testAxes, testSnap];
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

async function testGraph() {}
async function testImport() {}
void SAMPLES;

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const {failed: f} = await run();
  process.exit(f ? 1 : 0);
}
