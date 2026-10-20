#!/usr/bin/env node
// A local preview of the Lively NPCs web editor, with no Minecraft server: serves
// src/main/resources/web/ at http://localhost:8790/editor/ (same headers as the plugin's WebServer)
// and answers /api/* from made-up but realistic data kept in memory: four NPCs, every preset
// animation from DESIGN.md, the channels and easings, an item list and generated skins.
//
//   node tools/editor-preview.mjs            then open the link it prints
//   node tools/editor-preview.mjs --port 9000 --offline   (the editor's player "not in game")
//
// Any token logs in (the link it prints has one); the token "expired-token-0000" answers 401, to see
// the expired screen. Nothing is saved: restarting brings the sample data back. Needs only node.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {regions, shape, PART_SHAPES} from '../src/main/resources/web/js/model.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const args = process.argv.slice(2);
const PORT = Number(args[args.indexOf('--port') + 1]) || 8790;
let online = !args.includes('--offline');
// --shots <folder>: the page may POST /__shot?name=x with a PNG data URL (a 3D view capture for checks).
const SHOTS = args.includes('--shots') ? path.resolve(args[args.indexOf('--shots') + 1]) : null;

// ---------------------------------------------------------------- skins

/** A 64x64 RGBA PNG from pixel data. */
function png(width, height, rgba) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * width * 4, width * 4).copy(raw, y * (width * 4 + 1) + 1);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

function hex(c) {
  return [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
}

/** Paints a skin: a look {hair, skin, eyes, shirt, shirt2, pants, shoes, jacket?, hat?}, slim or not. */
function paintSkin(look, slim) {
  const data = new Uint8Array(64 * 64 * 4);
  const put = (x, y, c, a = 255) => {
    if (x < 0 || y < 0 || x >= 64 || y >= 64) return;
    const [r, g, b] = typeof c === 'string' ? hex(c) : c;
    const i = (y * 64 + x) * 4;
    data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = a;
  };
  const rect = (x0, y0, x1, y1, c, a = 255) => { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) put(x, y, c, a); };
  const shade = (c, k) => hex(c).map(v => Math.max(0, Math.min(255, Math.round(v * k))));
  // Every face of a part: tone by side, a little noise so it reads as pixels.
  const part = (name, color, outer = false, alpha = 255) => {
    const {w, h, d} = shape(name, slim);
    const [u, v] = outer ? PART_SHAPES[name].outer : PART_SHAPES[name].base;
    for (const [side, [l, t, r, b]] of Object.entries(regions(u, v, w, h, d))) {
      const k = side === 'up' ? 1.1 : side === 'down' ? 0.75 : side === 'south' ? 1 : side === 'north' ? 0.85 : 0.92;
      for (let y = t; y < b; y++) {
        for (let x = l; x < r; x++) {
          const n = 0.94 + (((x * 7 + y * 13) % 5) / 40);
          put(x, y, shade(color, k * n), alpha);
        }
      }
    }
  };
  part('head', look.skin);
  part('body', look.shirt);
  part('right_arm', look.shirt);
  part('left_arm', look.shirt);
  part('right_leg', look.pants);
  part('left_leg', look.pants);
  // Hands, shoes, a belt, the shirt's second colour down the front.
  const armW = slim ? 3 : 4;
  for (const [u, v] of [PART_SHAPES.right_arm.base, PART_SHAPES.left_arm.base]) {
    rect(u, v + 4 + 9, u + 8 + 2 * armW, v + 16, look.skin);
    rect(u + 4 + armW, v, u + 4 + 2 * armW, v + 4, shade(look.skin, 0.8));
  }
  for (const [u, v] of [PART_SHAPES.right_leg.base, PART_SHAPES.left_leg.base]) {
    rect(u, v + 4 + 9, u + 16, v + 16, look.shoes);
    rect(u + 8, v, u + 12, v + 4, shade(look.shoes, 0.8));
  }
  rect(20, 20 + 9, 28, 20 + 10, look.pants);
  rect(23, 20, 25, 29, look.shirt2);
  // The face: eyes, brows, mouth; hair on top, back and sides.
  rect(8, 8, 16, 10, look.hair);
  rect(8, 0, 16, 8, look.hair);
  rect(0, 8, 8, 12, look.hair);
  rect(16, 8, 24, 12, look.hair);
  rect(24, 8, 32, 16, look.hair);
  rect(9, 12, 11, 13, '#ffffff');
  rect(10, 12, 11, 13, look.eyes);
  rect(13, 12, 15, 13, '#ffffff');
  rect(13, 12, 14, 13, look.eyes);
  rect(9, 11, 11, 12, shade(look.hair, 0.8));
  rect(13, 11, 15, 12, shade(look.hair, 0.8));
  rect(11, 14, 13, 15, shade(look.skin, 0.78));
  rect(10, 15, 14, 16, shade(look.skin, 0.62));
  // Outer layers: a hat brim or hood, and a jacket with see-through gaps.
  if (look.hat) {
    rect(32, 0, 64, 16, [0, 0, 0], 0);
    rect(40, 0, 48, 8, look.hat);
    rect(32, 8, 64, 10, look.hat);
    rect(40, 8, 48, 9, shade(look.hat, 0.7));
  }
  if (look.jacket) {
    part('body', look.jacket, true);
    rect(20 + 2, 36, 20 + 6, 48, [0, 0, 0], 0);
    part('right_arm', look.jacket, true);
    part('left_arm', look.jacket, true);
    for (const [u, v] of [PART_SHAPES.right_arm.outer, PART_SHAPES.left_arm.outer]) rect(u, v + 4 + 8, u + 16, v + 16, [0, 0, 0], 0);
  }
  return png(64, 64, data);
}

const skins = new Map();

function addSkin(bytes, slim, source) {
  const texture = crypto.createHash('sha256').update(bytes).digest('hex');
  const key = texture.slice(0, 16);
  skins.set(key, {key, slim, source, png: bytes});
  return {key, slim, source};
}

function seeded(text) {
  const h = crypto.createHash('sha256').update(text).digest();
  const pick = list => list[h[pick.i++ % h.length] % list.length];
  pick.i = 0;
  const color = () => '#' + [0, 1, 2].map(() => (60 + (h[pick.i++ % h.length] % 170)).toString(16).padStart(2, '0')).join('');
  return {
    hair: pick(['#3b2414', '#6b4423', '#d9b36c', '#1f1f24', '#a8452e', '#e6e6e6']),
    skin: pick(['#f0c8a0', '#d8a47c', '#a86b45', '#7a4b2e', '#ffd9b8']),
    eyes: pick(['#2f5fae', '#3e7d3a', '#5a3a1e', '#444444']),
    shirt: color(), shirt2: color(), pants: color(), shoes: '#3a3a40',
    jacket: h[3] % 2 ? color() : null, hat: h[4] % 3 === 0 ? color() : null,
  };
}

const SHOP = addSkin(paintSkin({hair: '#6b4423', skin: '#f0c8a0', eyes: '#2f5fae', shirt: '#4caf6a', shirt2: '#e8e0c8', pants: '#3a5fa8', shoes: '#4a3424', hat: '#d9b75a'}, false), false, 'player:MobShopKeeper');
const GUIDE = addSkin(paintSkin({hair: '#f2d27a', skin: '#ffd9b8', eyes: '#3e7d3a', shirt: '#8b7bff', shirt2: '#ffffff', pants: '#2b2f45', shoes: '#ffffff', jacket: '#5d4fd6'}, true), true, 'upload');
const BANK = addSkin(paintSkin({hair: '#1f1f24', skin: '#a86b45', eyes: '#5a3a1e', shirt: '#f4f4f4', shirt2: '#c9a227', pants: '#22252c', shoes: '#111111', jacket: '#2a2f3a'}, false), false, 'url:https://example.com/banker.png');

// ---------------------------------------------------------------- data

const CHANNELS = [
  ['root-x', true, -32, 32], ['root-y', true, -16, 32], ['root-z', true, -32, 32], ['root-yaw', false, -360, 360],
  ['torso-pitch', false, -45, 90], ['torso-roll', false, -45, 45], ['torso-yaw', false, -60, 60],
  ['head-yaw', false, -80, 80], ['head-pitch', false, -60, 60], ['head-roll', false, -40, 40], ['shoulders', true, -2, 3],
  ['right-arm-forward', false, -90, 220], ['right-arm-out', false, -60, 180], ['right-arm-across', false, -60, 90],
  ['left-arm-forward', false, -90, 220], ['left-arm-out', false, -60, 180], ['left-arm-across', false, -60, 90],
  ['right-leg-forward', false, -90, 90], ['right-leg-out', false, -30, 60], ['left-leg-forward', false, -90, 90], ['left-leg-out', false, -30, 60],
].map(([key, pixels, min, max]) => ({key, pixels, min, max}));
const EASINGS = ['linear', 'ease-in', 'ease-out', 'ease-in-out', 'step'];

// Short names as DESIGN.md writes the presets.
const SHORT = {ry: 'root-y', raf: 'right-arm-forward', rao: 'right-arm-out', raa: 'right-arm-across', laf: 'left-arm-forward',
  lao: 'left-arm-out', laa: 'left-arm-across', hy: 'head-yaw', hp: 'head-pitch', hr: 'head-roll', shoulders: 'shoulders', rlf: 'right-leg-forward'};

function kf(at, values = {}, ease) {
  const k = {at};
  if (ease) k.ease = ease;
  for (const [s, v] of Object.entries(values)) if (v) k[SHORT[s] || s] = v;
  return k;
}

const both = (f, o, extra = {}) => ({raf: f, laf: f, rao: o, lao: o, ...extra});

function greetKeys(hop) {
  const keys = [kf(0), kf(4, {ry: hop ? 3.5 : 0, raf: 90}, 'ease-out'), kf(8, {raf: 160, rao: -8})];
  for (let t = 12, up = true; t <= 32; t += 4, up = !up) keys.push(kf(t, up ? {raf: 166, rao: -26} : {raf: 160, rao: -6}));
  keys.push(kf(36, {raf: 150, rao: -10}), kf(40, {raf: 70, rao: -4}), kf(44, {}, 'ease-in-out'));
  return keys;
}

const PRESETS = {
  greet: {name: 'Greet', length: 48, keyframes: greetKeys(true)},
  wave: {name: 'Wave', length: 48, keyframes: greetKeys(false)},
  talk: {name: 'Talk', length: 40, keyframes: [kf(0), kf(4, {ry: 3, raf: 25, rao: 4, raa: 4, hp: 7}), kf(8, {raf: 40, rao: 2, raa: 6, hp: 1}),
    kf(12, {raf: 22, rao: 6, raa: 2, hp: 9}), kf(16, {raf: 38, rao: 2, raa: 6, hp: 2}), kf(20, {raf: 24, rao: 7, raa: 2, hp: 8}),
    kf(24, {raf: 40, rao: 3, raa: 6, hp: 1}), kf(28, {raf: 26, rao: 4, hp: 7}), kf(32, {raf: 34, rao: 3, hp: 2}), kf(36, {raf: 14, rao: 1}), kf(40)]},
  beckon: {name: 'Beckon', length: 36, keyframes: [kf(0), kf(4, {raf: 80, raa: 4}), kf(8, {raf: 145, raa: 4, hp: -4, hr: 4}), kf(12, {raf: 85, raa: 4, hr: 4}),
    kf(16, {raf: 145, raa: 4, hp: -4, hr: 4}), kf(20, {raf: 85, raa: 4, hr: 4}), kf(24, {raf: 145, raa: 4, hp: -4, hr: 4}), kf(28, {raf: 90, raa: 3}),
    kf(32, {raf: 40}), kf(36, {}, 'ease-in-out')]},
  stretch: {name: 'Stretch', length: 40, keyframes: [kf(0), kf(4, both(60, 0)), kf(8, both(130, -6, {hp: -8})), kf(12, both(170, -10, {hp: -14, shoulders: 0.8})),
    kf(16, both(176, -12, {hp: -16, shoulders: 1})), kf(20, both(176, -12, {hp: -16, shoulders: 1})), kf(24, both(168, -10, {hp: -14, shoulders: 0.6})),
    kf(28, both(120, -6, {hp: -8})), kf(32, both(50, 0, {hp: -3})), kf(36, both(12, 0)), kf(40)]},
  look_around: {name: 'Look around', length: 60, glance: true, keyframes: [kf(0), kf(12, {hy: -45, hp: 5}), kf(24, {hy: -45, hp: 5}), kf(40, {hy: 45}), kf(52, {hy: 45}), kf(60)]},
  nod: {name: 'Nod', length: 20, keyframes: [kf(0), kf(4, {hp: 12}), kf(8, {hp: 2}), kf(12, {hp: 12}), kf(16, {hp: 2}), kf(20)]},
  head_shake: {name: 'Head shake', length: 24, glance: true, keyframes: [kf(0), kf(4, {hy: -18}), kf(8, {hy: 18}), kf(12, {hy: -18}), kf(16, {hy: 18}), kf(20, {hy: -6}), kf(24)]},
  shrug: {name: 'Shrug', length: 24, keyframes: [kf(0), ...[4, 8, 12].map(t => kf(t, both(12, 11, {shoulders: 1, hr: 5, hp: -4}))), kf(18, both(6, 5, {shoulders: 0.4, hr: 2})), kf(24)]},
  scratch_head: {name: 'Scratch head', length: 40, keyframes: [kf(0), kf(4, {raf: 120, rao: 4, raa: 8, hp: 4, hr: 3}), kf(8, {raf: 198, rao: 8, raa: 10, hp: 8, hr: 5}),
    kf(12, {raf: 204, rao: 10, raa: 10, hp: 8, hr: 5}), kf(16, {raf: 196, rao: 7, raa: 10, hp: 8, hr: 5}), kf(20, {raf: 204, rao: 10, raa: 10, hp: 8, hr: 5}),
    kf(24, {raf: 196, rao: 7, raa: 10, hp: 8, hr: 5}), kf(28, {raf: 200, rao: 8, raa: 10, hp: 8, hr: 5}), kf(32, {raf: 120, rao: 4, raa: 6, hp: 3}), kf(36, {raf: 30}), kf(40)]},
  clap: {name: 'Clap', length: 36, keyframes: [kf(0), kf(4, {raf: 60, laf: 60, raa: 12, laa: 12, hp: 6}),
    ...[8, 16, 24].map(t => kf(t, {raf: 65, laf: 65, raa: 25, laa: 25, hp: 6})), ...[12, 20].map(t => kf(t, {raf: 62, laf: 62, raa: 12, laa: 12, hp: 6})),
    kf(28, {raf: 50, laf: 50, raa: 10, laa: 10, hp: 3}), kf(32, {raf: 20, laf: 20, raa: 3, laa: 3}), kf(36)].sort((a, b) => a.at - b.at)},
  foot_tap: {name: 'Foot tap', length: 32, keyframes: [kf(0), ...[4, 12, 20].map(t => kf(t, {rlf: 14, hp: 3})), ...[8, 16, 24].map(t => kf(t, {hp: 3})), kf(32)].sort((a, b) => a.at - b.at)},
  look_up: {name: 'Look up', length: 44, glance: true, keyframes: [kf(0), kf(8, {hp: -30, hy: 8}), kf(16, {hp: -34, hy: -6}), kf(28, {hp: -34, hy: 4}), kf(36, {hp: -10}), kf(44)]},
  hop: {name: 'Hop', length: 10, keyframes: [kf(0), kf(4, {ry: 4}, 'ease-out'), kf(10, {}, 'ease-in')]},
};

const presetMap = id => ({name: PRESETS[id].name, length: PRESETS[id].length, loop: false, glance: !!PRESETS[id].glance, keyframes: PRESETS[id].keyframes});
/** The server's own animations (files): an edited wave and one of its own. */
const files = new Map([
  ['wave', {...presetMap('wave'), name: 'Wave (big)', keyframes: presetMap('wave').keyframes.map(k => (k['right-arm-out'] ? {...k, 'right-arm-out': k['right-arm-out'] - 6} : k))}],
  ['happy-dance', {name: 'Happy dance', length: 32, loop: true, glance: false, keyframes: [kf(0),
    kf(8, {ry: 2, raf: 150, laf: 150, rao: 20, lao: 20, hr: 8, 'torso-roll': 6}, 'ease-in-out'), kf(16, {}, 'ease-in-out'),
    kf(24, {ry: 2, raf: 150, laf: 150, rao: 20, lao: 20, hr: -8, 'torso-roll': -6}, 'ease-in-out')]}],
]);

function animation(id) {
  const file = files.get(id), preset = PRESETS[id] ? presetMap(id) : null;
  const map = file || preset;
  if (!map) return null;
  return {id, builtin: !!preset, edited: !!(file && preset && JSON.stringify(file) !== JSON.stringify(preset)), ...map};
}

function allAnimations() {
  return [...new Set([...Object.keys(PRESETS), ...files.keys()])].sort().map(animation);
}

const npcs = new Map();
const npc = (o) => npcs.set(o.id, {id: o.id, entity: 'player', variant: '', world: 'world', x: 0, y: 64, z: 0, yaw: 0, skin: '', plate: [], icon: '',
  held: '', click: [], idle: [], greet: 'greet', clicked: 'talk', beckon: 'beckon', look: true, ...o});
npc({id: 'mobshop', x: 6.5, y: 64, z: -2.5, yaw: 180, skin: SHOP.key, plate: ['<gold><bold>Mob Shop', '<gray>Buy mobs', '<yellow>Click'],
  icon: 'cow_spawn_egg', held: 'wheat', click: ['player:shop mobs']});
npc({id: 'guide', x: 0.5, y: 64, z: 4.5, yaw: -90, skin: GUIDE.key, plate: ['<gradient:#5cc8ff:#b2a8ff><bold>Guide', '<gray>New here? Click me!'],
  click: ['message:<green>Welcome to the server, {player}!', 'player:spawn'], idle: ['look_around', 'wave', 'nod']});
npc({id: 'banker', x: -8.5, y: 64, z: 3.5, yaw: 90, skin: BANK.key, plate: ['<#ffd166><bold>Bank', '<gray>Your coins, safe'], held: 'gold_ingot',
  icon: 'gold_block', click: ['console:bank open {player}'], beckon: ''});
npc({id: 'farmer', entity: 'villager', variant: 'plains/farmer', x: 12.5, y: 64, z: 8.5, yaw: 135, plate: ['<green>Farmer', '<gray>Seeds and tools'],
  click: ['player:shop farm'], greet: '', clicked: '', beckon: ''});

const ITEMS = ('acacia_planks air allium amethyst_shard anvil apple arrow axolotl_bucket baked_potato bamboo barrel beacon bee_spawn_egg beef bell '
  + 'blaze_rod blue_ice bone book bookshelf bow bowl bread brick brown_mushroom bucket cactus cake campfire carrot cauldron chain chest chicken '
  + 'clock coal cobblestone cocoa_beans compass cooked_beef cooked_chicken cookie copper_ingot cow_spawn_egg crafting_table crossbow diamond '
  + 'diamond_axe diamond_block diamond_hoe diamond_pickaxe diamond_shovel diamond_sword dirt egg elytra emerald emerald_block enchanted_book '
  + 'ender_pearl experience_bottle feather filled_map fishing_rod flint_and_steel flower_pot glass glow_berries glowstone_dust gold_block '
  + 'gold_ingot gold_nugget golden_apple golden_carrot golden_hoe golden_sword grass_block gunpowder hay_block honey_bottle honeycomb hopper '
  + 'iron_axe iron_block iron_hoe iron_ingot iron_pickaxe iron_sword jukebox kelp ladder lantern lava_bucket lead leather lectern lily_pad '
  + 'map melon_slice milk_bucket minecart music_disc_cat name_tag nether_star netherite_ingot netherite_sword oak_log oak_planks oak_sapling '
  + 'painting paper pig_spawn_egg poppy potato potion pufferfish pumpkin pumpkin_pie rabbit_foot red_mushroom redstone rose_bush saddle '
  + 'salmon sheep_spawn_egg shears shield slime_ball snowball spyglass stick stone stone_pickaxe string sugar sugar_cane sunflower sweet_berries '
  + 'tnt torch totem_of_undying trident tropical_fish villager_spawn_egg water_bucket wheat wheat_seeds white_wool wooden_axe wooden_hoe '
  + 'wooden_pickaxe wooden_shovel wooden_sword writable_book written_book').split(' ').filter(i => i !== 'air').sort();

const PLAYER = {name: 'Brick_Bread', uuid: '3f2a9c0d-11e4-4b7a-9a10-6c5e2b7d8e91'};
const started = Date.now();
const SESSION_MS = 120 * 60_000;

function state() {
  return {
    player: {...PLAYER, online, world: online ? 'world' : ''},
    npcs: [...npcs.values()].sort((a, b) => a.id.localeCompare(b.id)),
    animations: allAnimations(),
    skins: [...skins.values()].map(({key, slim, source}) => ({key, slim, source})),
    items: ITEMS,
    channels: CHANNELS,
    easings: EASINGS,
    settings: {scale: 0.9375, keyTicks: 2},
    session: {expiresIn: Math.max(0, started + SESSION_MS - Date.now())},
  };
}

// ---------------------------------------------------------------- the API

const ID = /^[a-z0-9_-]{1,40}$/;
const sleep = ms => new Promise(r => setTimeout(r, ms));
class Fail extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function needOnline() {
  if (!online) throw new Fail(409, 'You are not in game: join the server first (a new NPC stands where you are).');
}

async function api(method, parts, body) {
  const [what, id, extra] = parts;
  if (what === 'state' && method === 'GET') return [200, state()];
  if (what === 'npcs') {
    if (!id && method === 'POST') {
      if (!ID.test(body.id || '')) throw new Fail(400, `'${body.id}' is not an NPC id (a-z, 0-9, _ and -)`);
      if (npcs.has(body.id)) throw new Fail(409, `There is already an NPC called ${body.id}.`);
      let made;
      if (body.copyFrom) {
        const from = npcs.get(body.copyFrom);
        if (!from) throw new Fail(404, `There is no NPC called ${body.copyFrom}.`);
        made = {...structuredClone(from), id: body.id};
      } else {
        needOnline();
        made = {id: body.id, entity: 'player', variant: '', world: 'world', x: 2.37, y: 64, z: 1.12, yaw: -35.4, skin: '', plate: ['<bold>' + body.id],
          icon: '', held: '', click: [], idle: [], greet: 'greet', clicked: 'talk', beckon: 'beckon', look: true};
      }
      npcs.set(made.id, made);
      return [201, made];
    }
    const n = npcs.get(id);
    if (method === 'PUT' && !extra) {
      if (body.id !== id) throw new Fail(400, `The NPC's id (${body.id}) does not match the address (${id}).`);
      if (body.skin && !skins.has(body.skin)) throw new Fail(400, `There is no stored skin ${body.skin}.`);
      for (const f of ['held', 'icon']) if (body[f] && !ITEMS.includes(body[f])) throw new Fail(400, `${f}: '${body[f]}' is not an item.`);
      npcs.set(id, structuredClone(body));
      return [200, npcs.get(id)];
    }
    if (!n) throw new Fail(404, `There is no NPC called ${id}.`);
    if (method === 'DELETE' && !extra) {
      npcs.delete(id);
      return [200, {deleted: id}];
    }
    if (method === 'POST' && extra === 'move-to-me') {
      needOnline();
      n.world = 'world';
      n.x = Math.round((Math.random() * 20 - 10) * 100) / 100;
      n.z = Math.round((Math.random() * 20 - 10) * 100) / 100;
      n.yaw = Math.round(Math.random() * 360 - 180);
      return [200, n];
    }
  }
  if (what === 'animations' && id) {
    if (!ID.test(id)) throw new Fail(400, `'${id}' is not an animation id (a-z, 0-9, _ and -)`);
    if (method === 'PUT') {
      if (!Array.isArray(body.keyframes)) throw new Fail(400, `${id}: no keyframes list`);
      const ats = body.keyframes.map(k => k.at);
      if (new Set(ats).size !== ats.length) throw new Fail(400, 'two keyframes at the same tick');
      const length = Math.max(1, body.length | 0, ...ats);
      files.set(id, {name: body.name || id, length, loop: !!body.loop, glance: !!body.glance, keyframes: body.keyframes});
      return [200, animation(id)];
    }
    if (method === 'DELETE') {
      if (!files.has(id)) throw new Fail(404, PRESETS[id] ? `${id} is a preset as it ships: there is nothing to restore.` : `There is no animation called ${id}.`);
      files.delete(id);
      return [200, {deleted: id, animation: PRESETS[id] ? animation(id) : null}];
    }
  }
  if (what === 'preview' && method === 'POST') {
    if (id === 'stop') return [200, {stopped: true}];
    needOnline();
    if (!npcs.has(body.npc)) throw new Fail(404, `There is no NPC called ${body.npc}.`);
    if (npcs.get(body.npc).entity !== 'player') throw new Fail(409, `${body.npc} is a ${npcs.get(body.npc).entity}: only person NPCs play animations.`);
    return [200, {playing: body.animation.id, npc: body.npc, loop: !!body.animation.loop}];
  }
  if (what === 'play' && method === 'POST') {
    if (!npcs.has(body.npc)) throw new Fail(404, `There is no NPC called ${body.npc}.`);
    if (!animation(body.animation)) throw new Fail(404, `There is no animation called ${body.animation}.`);
    if (!body.everyone) needOnline();
    return [200, {playing: body.animation, npc: body.npc, everyone: !!body.everyone}];
  }
  if (what === 'skins' && !id && method === 'POST') {
    const {type, value, slim} = body;
    if (type === 'player') {
      if (!/^[A-Za-z0-9_]{1,16}$/.test(value || '')) throw new Fail(400, `'${value}' is not a player name.`);
      await sleep(900);
      if (/^error/i.test(value)) throw new Fail(400, `There is no player called ${value}.`);
      const isSlim = crypto.createHash('md5').update(value).digest()[0] % 2 === 1;
      return [200, addSkin(paintSkin(seeded(value), isSlim), isSlim, 'player:' + value)];
    }
    if (type === 'url') {
      if (!/^https?:\/\/\S+$/i.test(value || '')) throw new Fail(400, 'That is not a web link (http:// or https://).');
      await sleep(2200);
      if (/fail/i.test(value)) throw new Fail(400, 'MineSkin could not read a skin at that link.');
      return [200, addSkin(paintSkin(seeded(value), !!slim), !!slim, 'url:' + value)];
    }
    if (type === 'upload') {
      const bytes = Buffer.from(value || '', 'base64');
      if (bytes.length < 8 || bytes.readUInt32BE(0) !== 0x89504e47) throw new Fail(400, 'The upload is not a PNG image.');
      await sleep(1800);
      return [200, addSkin(bytes, !!slim, 'upload')];
    }
    throw new Fail(400, `'${type}' is not a way to add a skin: player, url or upload.`);
  }
  throw new Fail(404, 'No such API call: ' + method + ' /api/' + parts.join('/'));
}

// ---------------------------------------------------------------- the server

const TYPES = {html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', mjs: 'text/javascript; charset=utf-8',
  css: 'text/css; charset=utf-8', json: 'application/json; charset=utf-8', png: 'image/png', svg: 'image/svg+xml',
  woff2: 'font/woff2', ico: 'image/x-icon', txt: 'text/plain; charset=utf-8'};

function send(res, status, type, body, headers = {}) {
  res.writeHead(status, {'Content-Type': type, 'X-Content-Type-Options': 'nosniff', ...headers});
  res.end(body);
}

function json(res, status, body) {
  send(res, status, 'application/json; charset=utf-8', JSON.stringify(body), {'Cache-Control': 'no-store'});
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  try {
    if (p === '/') return send(res, 302, 'text/plain', '', {Location: '/editor/'});
    if (p === '/__shot' && SHOTS && req.method === 'POST') {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const name = (url.searchParams.get('name') || 'shot').replace(/[^a-z0-9_-]/gi, '');
      const data = Buffer.concat(chunks).toString('utf8').replace(/^data:image\/png;base64,/, '');
      fs.mkdirSync(SHOTS, {recursive: true});
      fs.writeFileSync(path.join(SHOTS, name + '.png'), Buffer.from(data, 'base64'));
      return json(res, 200, {saved: name});
    }
    if (p === '/editor') return send(res, 302, 'text/plain', '', {Location: '/editor/'});
    if (p.startsWith('/editor/')) {
      let file = p.slice('/editor/'.length) || 'index.html';
      if (!/^[A-Za-z0-9_./-]+$/.test(file) || file.includes('..')) return send(res, 404, 'text/plain', 'Not found');
      const full = path.join(WEB, file);
      if (!full.startsWith(WEB) || !fs.existsSync(full) || !fs.statSync(full).isFile()) return send(res, 404, 'text/plain', 'Not found');
      const ext = path.extname(file).slice(1).toLowerCase();
      const headers = {'Cache-Control': 'no-cache'};
      if (ext === 'html') {
        headers['Content-Security-Policy'] = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'";
        headers['Referrer-Policy'] = 'no-referrer';
      }
      return send(res, 200, TYPES[ext] || 'application/octet-stream', fs.readFileSync(full), headers);
    }
    if (p.startsWith('/api/')) {
      const auth = req.headers.authorization || '';
      const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
      if (!token || token === 'expired-token-0000' || Date.now() > started + SESSION_MS) {
        return json(res, 401, {error: 'Your editor link has expired or is not valid: run /npc editor again in game.'});
      }
      const parts = p.slice(5).split('/').filter(Boolean).map(decodeURIComponent);
      if (parts[0] === 'skins' && parts[1] && req.method === 'GET') {
        const key = parts[1].replace(/\.png$/, '');
        const s = skins.get(key);
        if (!s) return json(res, 404, {error: `There is no stored skin ${key}.`});
        return send(res, 200, 'image/png', s.png, {'Cache-Control': 'private, max-age=86400'});
      }
      let body = {};
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const text = Buffer.concat(chunks).toString('utf8');
        if (text) {
          try {
            body = JSON.parse(text);
          } catch {
            return json(res, 400, {error: 'The request is not JSON.'});
          }
        }
      }
      const [status, out] = await api(req.method, parts, body);
      if (!(parts[0] === 'state')) console.log(`${new Date().toLocaleTimeString()}  ${req.method} ${p} -> ${status}`);
      return json(res, status, out);
    }
    send(res, 404, 'text/plain', 'Not found');
  } catch (e) {
    if (e instanceof Fail) {
      console.log(`${new Date().toLocaleTimeString()}  ${req.method} ${p} -> ${e.status} ${e.message}`);
      return json(res, e.status, {error: e.message});
    }
    console.error(e);
    json(res, 500, {error: 'Something went wrong in the preview server: ' + e.message});
  }
});

server.listen(PORT, () => {
  console.log(`Lively NPCs editor preview: http://localhost:${PORT}/editor/#preview-token-0123456789`);
  console.log(`(the expired screen: http://localhost:${PORT}/editor/#expired-token-0000; player ${online ? 'in game' : 'not in game'}; Ctrl+C stops)`);
});
