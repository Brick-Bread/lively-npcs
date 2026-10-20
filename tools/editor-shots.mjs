#!/usr/bin/env node
// Screenshots of the web editor from headless Chrome, with no Minecraft server: starts the preview
// harness (tools/editor-preview.mjs), opens the editor at 1440x900, puts it in each state below and
// saves a PNG per state. Page errors and console errors are printed (and make the exit code 1).
//
//   node tools/editor-shots.mjs                       every shot, into docs/screenshots/
//   node tools/editor-shots.mjs --only graph-editor   one shot
//   node tools/editor-shots.mjs --check               no pictures: clicks and drags in the timeline, checked
//   node tools/editor-shots.mjs --out <folder> --chrome <path to chrome.exe>
// It takes free ports for the harness and Chrome by itself (or --port / --debug-port).
//
// Needs node 22+ (its WebSocket) and Chrome or Edge.

import {spawn} from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const args = process.argv.slice(2);
const arg = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const OUT = path.resolve(ROOT, arg('--out', 'docs/screenshots'));
const ONLY = arg('--only', null);
const CHECK = args.includes('--check');
let PORT = Number(arg('--port', 0));
let DEBUG_PORT = Number(arg('--debug-port', 0));
const WIDTH = 1440, HEIGHT = 900;
const CHROMES = [arg('--chrome', null), 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/google-chrome', '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].filter(Boolean);

const sleep = ms => new Promise(r => setTimeout(r, ms));

/** The states to photograph: name -> code run in the page (it may return a promise). */
export const SHOTS = {
  'animate-timeline': `
    const {St, S} = window.__editor;
    St.setMode('animations'); St.selectAnim('greet');
    S.expanded.clear(); St.toggleExpanded('right_arm', true); St.toggleExpanded('root', true);
    St.selectPart('right_arm'); St.setView({graph: false, onion: false, zoom: 0});
    St.setTick(12); St.selectKey(12); St.setKeySelection([8, 12, 16]);`,
  'graph-editor': `
    const {St, S} = window.__editor;
    St.setMode('animations'); St.selectAnim('greet');
    St.selectChannel('right_arm', 'right-arm-forward'); St.setView({graph: true, onion: false, zoom: 0});
    St.setTick(20); St.selectKey(20);`,
  'onion-skin': `
    const {St, S, viewport} = window.__editor;
    St.setMode('animations'); St.selectAnim('stretch');
    St.selectPart('right_arm'); St.setView({graph: false, onion: true, onionMode: 'keys', zoom: 0});
    St.setTick(6); viewport.view('three-quarter', false);`,
  'npc-mode': `
    const {St, viewport} = window.__editor;
    St.setMode('npcs'); St.selectNpc('mobshop'); viewport.view('three-quarter', false);`,
  'import-dialog': () => `
    const {St} = window.__editor;
    St.setMode('animations'); St.selectAnim('wave'); St.setView({graph: false, onion: false});
    window.__editor.importText('wave.bbmodel', ${JSON.stringify(fs.readFileSync(path.join(ROOT, 'src/test/resources/blockbench/wave.bbmodel'), 'utf8'))});`,
};

/** A TCP port nothing listens on right now. */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, () => {
      const {port} = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

/** Presses, drags and lets go with the mouse, as a person would (pixels in the page). */
async function drag(cdp, from, to, {shift = false, steps = 6} = {}) {
  const modifiers = shift ? 8 : 0;
  await cdp.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: from[0], y: from[1], modifiers});
  await cdp.send('Input.dispatchMouseEvent', {type: 'mousePressed', x: from[0], y: from[1], button: 'left', buttons: 1, clickCount: 1, modifiers});
  for (let i = 1; i <= steps; i++) {
    const x = from[0] + (to[0] - from[0]) * i / steps, y = from[1] + (to[1] - from[1]) * i / steps;
    await cdp.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x, y, button: 'left', buttons: 1, modifiers});
  }
  await cdp.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: to[0], y: to[1], button: 'left', buttons: 0, clickCount: 1, modifiers});
  await sleep(120);
}

/** Clicks and drags in the timeline, each checked against the editor's state. Returns the failures. */
async function interactions(cdp) {
  let bad = 0;
  const ok = (cond, what) => {
    console.log(`${cond ? 'ok  ' : 'BAD '} ${what}`);
    if (!cond) bad++;
  };
  const js = code => cdp.evaluate(`(() => { const {S, St} = window.__editor; ${code} })()`);
  const rect = sel => js(`const r = document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2, r.width, r.height];`);
  const keys = () => js(`return St.animEntry().draft.keyframes.map(k => k.at).join(',');`);
  await js(`St.setMode('animations'); St.selectAnim('greet'); St.setView({zoom: 10, graph: false, onion: false, snapTicks: 1}); S.expanded.clear(); St.selectPart(null); return 1;`);
  await sleep(400);
  const before = await keys();

  // Dragging a diamond 3 ticks to the right moves that keyframe; undo puts it back.
  let k = await rect('.tl-row.all .kf[data-at="8"]');
  await drag(cdp, [k[0], k[1]], [k[0] + 30, k[1]]);
  let now = await keys();
  ok(now.includes(',11,') && !now.includes(',8,'), `a diamond dragged 3 ticks on moves its keyframe from 8 to 11 (${now})`);
  await js(`St.undo(); return 1;`);
  await sleep(200);
  ok(await keys() === before, 'undo puts it back in one step');

  // With "Snap: 5 ticks" the drag lands on a multiple of 5; Shift moves freely.
  await js(`St.setView({snapTicks: 5}); return 1;`);
  await sleep(200);
  k = await rect('.tl-row.all .kf[data-at="12"]');
  await drag(cdp, [k[0], k[1]], [k[0] + 22, k[1]]);
  now = await keys();
  ok(now.includes(',15,') && !now.includes(',12,'), `snapped to 5 ticks: 12 dragged 2.2 ticks lands on 15 (${now})`);
  await js(`St.undo(); return 1;`);
  await sleep(200);
  k = await rect('.tl-row.all .kf[data-at="12"]');
  await drag(cdp, [k[0], k[1]], [k[0] + 22, k[1]], {shift: true});
  now = await keys();
  ok(now.includes(',14,') && !now.includes(',12,'), `with Shift: 12 dragged 2.2 ticks lands on 14 (${now})`);
  await js(`St.undo(); St.setView({snapTicks: 1}); return 1;`);
  await sleep(200);

  // Box select: dragging across empty track selects the keyframes in between, on any row.
  const row = await rect('.tl-row.part .tl-track');
  const x0 = await js(`const r = document.querySelector('.tl-row.part .tl-track').getBoundingClientRect(); return r.left + 12 + 2.5 * 10;`);
  await drag(cdp, [x0, row[1]], [x0 + 110, row[1] + 30]);
  const sel = await js(`return [...S.keySel].sort((a, b) => a - b).join(',');`);
  ok(sel === '4,8,12', `box select from tick 2.5 to 13.5 selects 4, 8 and 12 (${sel})`);

  // Dragging one of them moves all three together.
  k = await rect('.tl-row.all .kf[data-at="8"]');
  await drag(cdp, [k[0], k[1]], [k[0] + 10, k[1]]);
  now = await keys();
  ok(now.startsWith('0,5,9,13,16') , `the three selected keyframes move together by one tick (${now})`);
  await js(`St.undo(); return 1;`);
  await sleep(200);
  ok(await keys() === before, 'and undo puts all three back');

  // Clicking the ruler moves the playhead there.
  const ruler = await rect('.tl-ruler');
  const rx = await js(`const r = document.querySelector('.tl-ruler').getBoundingClientRect(); return r.left + 12 + 20 * 10;`);
  await drag(cdp, [rx, ruler[1]], [rx, ruler[1]], {steps: 1});
  ok(await js(`return S.tick;`) === 20, 'clicking the ruler at 1 s puts the playhead on tick 20');

  // The Outliner and the timeline select the same part; a part's row opens into its values.
  await js(`document.querySelectorAll('.ol-row')[3].click(); return 1;`);
  await sleep(150);
  ok(await js(`return S.part;`) === 'right_arm', 'clicking Right arm in the Outliner selects the right arm');
  ok(await js(`return document.querySelectorAll('.tl-row.value').length;`) === 4, 'and the timeline shows its four values');
  ok(await js(`return document.querySelector('.tl-row.part.active .tl-name').textContent;`) === 'Right arm', 'with its row lit');

  // Keys: O for onion skin, G for the graph editor.
  for (const [key, flag] of [['o', 'onion'], ['g', 'graph']]) {
    await cdp.send('Input.dispatchKeyEvent', {type: 'keyDown', key, text: key});
    await cdp.send('Input.dispatchKeyEvent', {type: 'keyUp', key});
    await sleep(150);
    ok(await js(`return !!S.view.${flag};`), `${key.toUpperCase()} turns the ${flag === 'onion' ? 'onion skin' : 'graph editor'} on`);
  }
  ok(await js(`return !!document.querySelector('.tl-graph-canvas');`), 'the graph editor draws its curve');
  await js(`St.setView({onion: false, graph: false}); return 1;`);
  return bad;
}

function findChrome() {
  for (const c of CHROMES) if (fs.existsSync(c)) return c;
  throw new Error('No Chrome or Edge found: pass --chrome <path>');
}

async function waitFor(fn, ms = 15000, what = 'something') {
  const end = Date.now() + ms;
  for (;;) {
    try {
      const v = await fn();
      if (v) return v;
    } catch {
      // not yet
    }
    if (Date.now() > end) throw new Error('Timed out waiting for ' + what);
    await sleep(150);
  }
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.pending = new Map();
    this.handlers = [];
    this.ws.addEventListener('message', ev => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const {resolve, reject} = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method) {
        for (const h of this.handlers) h(msg);
      }
    });
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, {once: true});
      this.ws.addEventListener('error', reject, {once: true});
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({id, method, params}));
    return new Promise((resolve, reject) => this.pending.set(id, {resolve, reject}));
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (r.exceptionDetails) throw new Error((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text);
    return r.result.value;
  }
}

async function main() {
  const names = ONLY ? ONLY.split(',') : Object.keys(SHOTS);
  for (const n of names) if (!SHOTS[n]) throw new Error('No shot called ' + n + ': ' + Object.keys(SHOTS).join(', '));
  fs.mkdirSync(OUT, {recursive: true});
  if (!PORT) PORT = await freePort();
  if (!DEBUG_PORT) DEBUG_PORT = await freePort();
  const server = spawn(process.execPath, [path.join(HERE, 'editor-preview.mjs'), '--port', String(PORT)], {stdio: ['ignore', 'pipe', 'inherit']});
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lnpc-shots-'));
  const chrome = spawn(findChrome(), ['--headless=new', '--disable-gpu-sandbox', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profile}`, `--window-size=${WIDTH},${HEIGHT}`, '--hide-scrollbars',
    '--force-device-scale-factor=1', 'about:blank'], {stdio: 'ignore'});
  let problems = 0;
  try {
    await waitFor(() => fetch(`http://localhost:${PORT}/editor/`).then(r => r.ok), 15000, 'the preview server');
    const targets = await waitFor(() => fetch(`http://127.0.0.1:${DEBUG_PORT}/json`).then(r => r.json()).then(l => l.filter(t => t.type === 'page')).then(l => l.length && l), 20000, 'Chrome');
    const cdp = new Cdp(targets[0].webSocketDebuggerUrl);
    await cdp.open();
    cdp.handlers.push(msg => {
      if (msg.method === 'Runtime.exceptionThrown') {
        problems++;
        const d = msg.params.exceptionDetails;
        console.log('  PAGE ERROR ' + ((d.exception && d.exception.description) || d.text).split('\n').slice(0, 4).join('\n    '));
      } else if (msg.method === 'Runtime.consoleAPICalled' && (msg.params.type === 'error' || msg.params.type === 'warning')) {
        const text = msg.params.args.map(a => a.value ?? a.description ?? '').join(' ');
        if (msg.params.type === 'error') problems++;
        console.log(`  console.${msg.params.type}: ${text.split('\n').slice(0, 4).join('\n    ')}`);
      }
    });
    await cdp.send('Runtime.enable');
    await cdp.send('Page.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false});
    await cdp.send('Page.navigate', {url: `http://localhost:${PORT}/editor/#preview-token-0123456789`});
    await waitFor(() => cdp.evaluate('!!(window.__editor && document.getElementById("gate").hidden)'), 20000, 'the editor to open');
    await sleep(800);
    if (CHECK) {
      problems += await interactions(cdp);
      cdp.ws.close();
      return;
    }
    for (const name of names) {
      await cdp.evaluate(`document.querySelectorAll('.modal-back').forEach(m => m.remove()); undefined`);
      const code = typeof SHOTS[name] === 'function' ? SHOTS[name]() : SHOTS[name];
      await cdp.evaluate(`(async () => { ${code} })()`);
      await sleep(900);
      const shot = await cdp.send('Page.captureScreenshot', {format: 'png'});
      const file = path.join(OUT, name + '.png');
      fs.writeFileSync(file, Buffer.from(shot.data, 'base64'));
      console.log('saved ' + path.relative(ROOT, file));
    }
    cdp.ws.close();
  } finally {
    chrome.kill();
    server.kill();
    await sleep(300);
    try {
      fs.rmSync(profile, {recursive: true, force: true});
    } catch {
      // Chrome may still hold a file for a moment
    }
  }
  if (problems) {
    console.log(`${problems} page error${problems === 1 ? '' : 's'}`);
    process.exit(1);
  }
}

main().catch(e => {
  console.error(e.message);
  process.exit(1);
});
