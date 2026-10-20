#!/usr/bin/env node
// Takes the Lively NPCs web editor screenshots for the store pages, in one command:
//
//   node release/take-screenshots.mjs
//
// It starts the editor preview harness (tools/editor-preview.mjs, made-up NPCs, no Minecraft
// server) on port 8795 unless something already answers there, opens the editor in headless Chrome
// at 1440x900, sets up each shot and saves it to release/screenshots/. Then it stops what it started.
// Needs only node and Chrome (or Edge, or Chromium); set CHROME=<path> if it is not found.
//
// Each shot sets the editor up through its own page code (store.js and actions.js), so a restyled
// editor is fine as long as those keep their names; a step that no longer exists is skipped and
// reported, and the shot is still taken.

import {spawn} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {findChrome} from './art/render-art.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT = path.join(HERE, 'screenshots');
const PORT = Number(process.env.PORT) || 8795;
const BASE = `http://localhost:${PORT}`;
const LINK = `${BASE}/editor/#preview-token-0123456789`;
const WIDTH = 1440, HEIGHT = 900;
const sleep = ms => new Promise(r => setTimeout(r, ms));

// Every shot: a file name and the setup that runs in the page (St = store.js, Act = actions.js).
// Setups are strings, run in the editor page, and return a list of the steps they had to skip.
const SHOTS = [
  {
    name: '01-npc-settings',
    about: 'NPCs: the Mob Shop NPC in its skin, with its plate and settings',
    setup: `
      need(St.setMode, 'setMode') && St.setMode('npcs');
      need(St.selectNpc, 'selectNpc') && St.selectNpc('mobshop');`,
  },
  {
    name: '02-animation-timeline',
    about: 'Animations: the greet preset half way through its wave, on the keyframe timeline',
    setup: `
      need(St.setMode, 'setMode') && St.setMode('animations');
      need(St.setModelNpc, 'setModelNpc') && St.setModelNpc('guide');
      need(St.selectAnim, 'selectAnim') && St.selectAnim('greet');
      need(St.pause, 'pause') && St.pause();
      need(St.setTick, 'setTick') && St.setTick(12);`,
  },
  {
    name: '03-posing',
    about: 'Posing: the right arm picked, with its rings and sliders, in a looping dance',
    setup: `
      need(St.setMode, 'setMode') && St.setMode('animations');
      need(St.setModelNpc, 'setModelNpc') && St.setModelNpc('mobshop');
      need(St.selectAnim, 'selectAnim') && St.selectAnim('happy-dance');
      need(St.pause, 'pause') && St.pause();
      need(St.setTick, 'setTick') && St.setTick(8);
      need(St.selectPart, 'selectPart') && St.selectPart('right_arm');`,
  },
  {
    name: '04-live-preview',
    about: 'Live preview on: the NPC in game plays the draft while you edit it',
    setup: `
      need(St.setMode, 'setMode') && St.setMode('animations');
      need(St.setModelNpc, 'setModelNpc') && St.setModelNpc('banker');
      need(St.selectAnim, 'selectAnim') && St.selectAnim('wave');
      need(St.pause, 'pause') && St.pause();
      need(St.setTick, 'setTick') && St.setTick(16);
      need(Act.setLive, 'setLive') && Act.setLive(true);`,
  },
  {
    name: '05-click-actions',
    about: "NPCs: the Banker's held item, icon and click actions",
    setup: `
      need(St.setMode, 'setMode') && St.setMode('npcs');
      need(St.selectNpc, 'selectNpc') && St.selectNpc('banker');
      await wait(400);
      // Scroll only the settings panel (never the page) to the held item and click actions.
      const panel = document.getElementById('inspector');
      const heading = [...(panel ? panel.querySelectorAll('*') : [])]
        .find(e => e.children.length === 0 && /^held item/i.test((e.textContent || '').trim()));
      if (panel && heading) {
        panel.scrollTop += heading.getBoundingClientRect().top - panel.getBoundingClientRect().top - 64;
      } else {
        skipped.push('the held item heading');
      }
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;`,
  },
];

// ---------------------------------------------------------------- small helpers

async function answers(url) {
  try {
    const r = await fetch(url, {redirect: 'manual'});
    return r.status > 0;
  } catch {
    return false;
  }
}

/** A minimal Chrome DevTools Protocol client over the browser's WebSocket (flattened sessions). */
class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.waiting = new Map();
    this.events = new Map();
    this.ws.addEventListener('message', e => {
      const msg = JSON.parse(e.data);
      if (msg.method) {
        const fn = this.events.get(msg.method);
        if (fn) fn(msg.params, msg.sessionId);
        return;
      }
      const w = this.waiting.get(msg.id);
      if (!w) return;
      this.waiting.delete(msg.id);
      if (msg.error) w.reject(new Error(msg.error.message));
      else w.resolve(msg.result);
    });
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, {once: true});
      this.ws.addEventListener('error', () => reject(new Error('could not reach Chrome')), {once: true});
    });
  }

  send(method, params = {}, sessionId) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    return new Promise((resolve, reject) => {
      this.waiting.set(id, {resolve, reject});
      setTimeout(() => {
        if (this.waiting.delete(id)) reject(new Error(method + ' timed out'));
      }, 60_000);
    });
  }
}

// ---------------------------------------------------------------- the run

const started = [];
let profile = null;
try {
  // 1. The preview harness, unless it is already running.
  if (!(await answers(BASE + '/editor/'))) {
    const harness = spawn(process.execPath, [path.join(ROOT, 'tools', 'editor-preview.mjs'), '--port', String(PORT)],
      {cwd: ROOT, stdio: 'ignore'});
    started.push(harness);
    for (let i = 0; i < 150 && !(await answers(BASE + '/editor/')); i++) await sleep(100);
    if (!(await answers(BASE + '/editor/'))) throw new Error('the editor preview did not start on port ' + PORT);
    console.log('Started the editor preview on port ' + PORT);
  } else {
    console.log('Using the editor preview already on port ' + PORT + ' (restart it for fresh sample data)');
  }

  // 2. Headless Chrome, with software WebGL for the 3D view.
  profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lively-shots-'));
  const chrome = spawn(findChrome(), ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', '--mute-audio',
    '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
    `--window-size=${WIDTH},${HEIGHT}`, 'about:blank'], {stdio: ['ignore', 'ignore', 'pipe']});
  started.push(chrome);
  const wsUrl = await new Promise((resolve, reject) => {
    let text = '';
    chrome.stderr.on('data', d => {
      text += d;
      const m = text.match(/DevTools listening on (ws:\/\/\S+)/);
      if (m) resolve(m[1]);
    });
    chrome.on('exit', code => reject(new Error('Chrome stopped (' + code + ')')));
    setTimeout(() => reject(new Error('Chrome did not start')), 30_000);
  });
  const cdp = new Cdp(wsUrl);
  await cdp.open();
  const {targetId} = await cdp.send('Target.createTarget', {url: 'about:blank'});
  const {sessionId} = await cdp.send('Target.attachToTarget', {targetId, flatten: true});
  const page = (method, params) => cdp.send(method, params, sessionId);
  await page('Page.enable');
  await page('Runtime.enable');
  // The editor asks "leave this page?" when a shot left unsaved changes: always leave.
  cdp.events.set('Page.javascriptDialogOpening', (params, session) =>
    cdp.send('Page.handleJavaScriptDialog', {accept: true}, session).catch(() => {}));
  await page('Emulation.setDeviceMetricsOverride', {width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false});

  const evaluate = async (expression) => {
    const r = await page('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };

  fs.mkdirSync(OUT, {recursive: true});
  const only = process.argv.slice(2);
  for (const shot of SHOTS) {
    if (only.length && !only.some(o => shot.name.includes(o))) continue;
    // A fresh page for every shot.
    await page('Page.navigate', {url: 'about:blank'});
    await sleep(200);
    await page('Page.navigate', {url: LINK});
    let ready = false;
    for (let i = 0; i < 200 && !ready; i++) {
      await sleep(100);
      ready = await evaluate(`document.readyState === 'complete' && !!document.getElementById('app') && !document.getElementById('app').hidden`)
        .catch(() => false);
    }
    if (!ready) throw new Error('the editor did not open for ' + shot.name);
    await sleep(800);
    const skipped = await evaluate(`(async () => {
      const St = await import(new URL('js/store.js', location.href));
      const Act = await import(new URL('js/actions.js', location.href));
      const skipped = [];
      const need = (fn, name) => (typeof fn === 'function' ? true : (skipped.push(name), false));
      const wait = ms => new Promise(r => setTimeout(r, ms));
      ${shot.setup}
      return skipped;
    })()`);
    // Skins load, the 3D view draws a few frames, toasts settle.
    await sleep(2200);
    const webgl = await evaluate(`!!document.querySelector('#viewport canvas')`).catch(() => false);
    const {data} = await page('Page.captureScreenshot', {format: 'png'});
    const file = path.join(OUT, shot.name + '.png');
    fs.writeFileSync(file, Buffer.from(data, 'base64'));
    console.log(`${shot.name}.png  ${shot.about}` + (skipped.length ? `  (skipped: ${skipped.join(', ')})` : '')
      + (webgl ? '' : '  (no 3D view drawn)'));
  }
  cdp.ws.close();
} finally {
  for (const child of started.reverse()) child.kill();
  if (profile) {
    await sleep(500);
    try {
      fs.rmSync(profile, {recursive: true, force: true});
    } catch {
      // Chrome may still hold a file for a moment; the folder is in the system's temp folder.
    }
  }
}
