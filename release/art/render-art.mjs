#!/usr/bin/env node
// Renders the Lively NPCs art (the SVGs in this folder) to PNG with headless Chrome:
//   node release/art/render-art.mjs
// Needs only node and Chrome (or Edge, or Chromium); set CHROME=<path> if it is not found.
// The PNGs keep the SVGs' transparent corners.

import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** [svg, png, width, height] */
const JOBS = [
  ['icon.svg', 'icon-512.png', 512, 512],
  ['icon.svg', 'icon-256.png', 256, 256],
  ['banner.svg', 'banner.png', 1200, 400],
  ['cover-builtbybit.svg', 'cover-builtbybit.png', 1200, 600],
];

export function findChrome() {
  const candidates = [
    process.env.CHROME,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    path.join(process.env.LOCALAPPDATA || '', 'Google\\Chrome\\Application\\chrome.exe'),
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
  ];
  const found = candidates.find(c => c && fs.existsSync(c));
  if (!found) throw new Error('Chrome not found: set CHROME to its path.');
  return found;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const chrome = findChrome();
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lively-art-'));
  try {
    for (const [svg, png, w, h] of JOBS) {
      const source = path.join(HERE, svg);
      if (!fs.existsSync(source)) {
        console.log(`skip ${svg} (not there)`);
        continue;
      }
      const page = path.join(work, png + '.html');
      fs.writeFileSync(page, `<!doctype html><html><head><style>html,body{margin:0;background:transparent}`
        + `img{display:block;width:${w}px;height:${h}px}</style></head><body>`
        + `<img src="${pathToFileURL(source).href}"></body></html>`);
      const out = path.join(HERE, png);
      execFileSync(chrome, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
        '--force-device-scale-factor=1', '--default-background-color=00000000',
        `--user-data-dir=${path.join(work, 'profile')}`, `--window-size=${w},${h}`,
        `--screenshot=${out}`, pathToFileURL(page).href], {stdio: 'ignore', timeout: 60_000});
      console.log(`${png}  ${w}x${h}  ${fs.statSync(out).size} bytes`);
    }
  } finally {
    fs.rmSync(work, {recursive: true, force: true});
  }
}
