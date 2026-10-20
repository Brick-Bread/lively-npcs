// The NPC model's boxes and how a skin goes on them: a port of the pack's geometry and UV mapping
// (RaiseAMob's resourcepack/npc_models.py, which matches the 26.3 player model face for face), and
// of the way the game prepares a skin image. Pure JavaScript, no page: node checks it.
//
// Every part's boxes are in the part's own frame, in pixels from its joint (the pack's model point
// (8, 8, 8)); the front faces +z (south) and the part's right is -x (west).

/** Each part: box size (w, h, d) in skin pixels, where its base and outer pictures start, how much bigger the outer box is. */
export const PART_SHAPES = {
  head: {size: [8, 8, 8], base: [0, 0], outer: [32, 0], grow: 0.5},
  body: {size: [8, 12, 4], base: [16, 16], outer: [16, 32], grow: 0.25},
  right_arm: {size: [4, 12, 4], base: [40, 16], outer: [40, 32], grow: 0.25},
  left_arm: {size: [4, 12, 4], base: [32, 48], outer: [48, 48], grow: 0.25},
  right_leg: {size: [4, 12, 4], base: [0, 16], outer: [0, 32], grow: 0.25},
  left_leg: {size: [4, 12, 4], base: [16, 48], outer: [0, 48], grow: 0.25},
};

/** The game's upgrade of an old 64x32 skin: (x, y, dx, dy, w, h) copies a piece, mirrored, (dx, dy) along. */
export const LEGACY = [[4, 16, 16, 32, 4, 4], [8, 16, 16, 32, 4, 4], [0, 20, 24, 32, 4, 12], [4, 20, 16, 32, 4, 12],
  [8, 20, 8, 32, 4, 12], [12, 20, 16, 32, 4, 12], [44, 16, -8, 32, 4, 4], [48, 16, -8, 32, 4, 4],
  [40, 20, 0, 32, 4, 12], [44, 20, -8, 32, 4, 12], [48, 20, -16, 32, 4, 12], [52, 20, -8, 32, 4, 12]];

/** The base layers (head, body, arms and legs), which the game always draws opaque: [x0, y0, x1, y1]. */
export const BASE_AREAS = [[0, 0, 32, 16], [0, 16, 64, 32], [16, 48, 48, 64]];

/** PART_SHAPES[part] with the 3-wide arms of a slim skin. */
export function shape(part, slim) {
  const s = PART_SHAPES[part];
  let [w, h, d] = s.size;
  if (slim && part.endsWith('_arm')) w = 3;
  return {w, h, d, base: s.base, outer: s.outer, grow: s.grow};
}

/**
 * Where each face of a w x h x d box with its picture at (u, v) finds it in the skin, as [left, top,
 * right, bottom] of the face seen from outside, upright; top and bottom are seen with north up.
 */
export function regions(u, v, w, h, d) {
  return {
    north: [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h],
    east: [u + d + w, v + d, u + 2 * d + w, v + d + h],
    south: [u + d, v + d, u + d + w, v + d + h],
    west: [u, v + d, u + d, v + d + h],
    up: [u + d, v, u + d + w, v + d],
    down: [u + d + w, v, u + d + 2 * w, v + d],
  };
}

/**
 * The corners of each face in the order the game gives them uv (u1, v1), (u1, v2), (u2, v2), (u2, v1)
 * (FaceInfo); per axis 0 = the element's from, 1 = its to. Counter-clockwise seen from outside.
 */
export const CORNERS = {
  down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
  up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
  north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
  south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
  west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
  east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]],
};

export const NORMALS = {north: [0, 0, -1], east: [1, 0, 0], south: [0, 0, 1], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0]};

/**
 * The part's elements as the pack writes them, moved so the joint is the origin: the base box, then
 * the outer layer's box. Each face's uv is [u1, v1, u2, v2] in skin pixels (the pack's uv times 4);
 * the game reads a face's v from its top edge, except the bottom face's, from its south edge.
 */
export function partElements(part, slim) {
  const {w, h, d, base, outer, grow} = shape(part, slim);
  const [y0, y1] = part === 'head' ? [0, h] : [-h, 0];
  const lo = [-w / 2, y0, -d / 2], hi = [w / 2, y1, d / 2];
  const elements = [];
  for (const [[u, v], g, layer] of [[base, 0, 'base'], [outer, grow, 'outer']]) {
    const faces = {};
    for (const [side, [left, top, right, bottom]] of Object.entries(regions(u, v, w, h, d))) {
      faces[side] = {uv: side === 'down' ? [left, bottom, right, top] : [left, top, right, bottom]};
    }
    elements.push({layer, from: lo.map(c => c - g), to: hi.map(c => c + g), faces});
  }
  return elements;
}

/** One element's faces as quads: four corners (CORNERS order) with their uv in skin pixels. */
export function faceQuads(element) {
  const ends = [element.from, element.to];
  const quads = [];
  for (const [side, corners] of Object.entries(CORNERS)) {
    const [u1, v1, u2, v2] = element.faces[side].uv;
    quads.push({
      side,
      normal: NORMALS[side],
      corners: corners.map(c => c.map((k, axis) => ends[k][axis])),
      uvs: [[u1, v1], [u1, v2], [u2, v2], [u2, v1]],
    });
  }
  return quads;
}

// ---- skin images: {width, height, data: Uint8ClampedArray RGBA} ----

function blank(width, height) {
  return {width, height, data: new Uint8ClampedArray(width * height * 4)};
}

function setAlpha(img, [x0, y0, x1, y1], alpha) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.data[(y * img.width + x) * 4 + 3] = alpha;
}

/**
 * A skin the way the game prepares it: a 64x32 one gets the 64x64 layout (its left limbs mirrored
 * from the right ones), an old skin's hat that has no see-through pixel is dropped, and the base
 * layers are made opaque. Throws for any other size.
 */
export function asTheGameDraws(img) {
  let skin;
  if (img.width === 64 && img.height === 32) {
    skin = blank(64, 64);
    skin.data.set(img.data.subarray(0, 64 * 32 * 4));
    for (const [x, y, dx, dy, w, h] of LEGACY) {
      for (let j = 0; j < h; j++) {
        for (let i = 0; i < w; i++) {
          const from = ((y + j) * 64 + (x + w - 1 - i)) * 4;
          const to = ((y + dy + j) * 64 + (x + dx + i)) * 4;
          for (let c = 0; c < 4; c++) skin.data[to + c] = skin.data[from + c];
        }
      }
    }
    let min = 255;
    for (let y = 0; y < 32; y++) for (let x = 32; x < 64; x++) min = Math.min(min, skin.data[(y * 64 + x) * 4 + 3]);
    if (min >= 128) setAlpha(skin, [32, 0, 64, 32], 0);
  } else if (img.width === 64 && img.height === 64) {
    skin = {width: 64, height: 64, data: new Uint8ClampedArray(img.data)};
  } else {
    throw new Error(`a skin is ${img.width}x${img.height}, not 64x64 or 64x32`);
  }
  for (const area of BASE_AREAS) setAlpha(skin, area, 255);
  return skin;
}

/** A plain, neutral skin for an NPC without one: soft greys, a face so the front is clear, no outer layer. */
export function neutralSkin() {
  const img = blank(64, 64);
  const fill = ([x0, y0, x1, y1], [r, g, b]) => {
    for (let y = y0; y < y1; y++) {
      for (let x = x0; x < x1; x++) {
        const i = (y * 64 + x) * 4;
        img.data[i] = r; img.data[i + 1] = g; img.data[i + 2] = b; img.data[i + 3] = 255;
      }
    }
  };
  const tones = {head: [196, 202, 212], body: [138, 150, 170], right_arm: [160, 170, 188], left_arm: [160, 170, 188],
    right_leg: [104, 114, 134], left_leg: [104, 114, 134]};
  for (const [part, tone] of Object.entries(tones)) {
    const {w, h, d, base} = shape(part, false);
    for (const [side, r] of Object.entries(regions(base[0], base[1], w, h, d))) {
      const k = side === 'up' ? 1.08 : side === 'down' ? 0.82 : side === 'south' ? 1 : 0.94;
      fill(r, tone.map(c => Math.min(255, Math.round(c * k))));
    }
  }
  // Eyes on the head's front (south face: 8..16, 8..16), so you can tell which way it faces.
  fill([9, 12, 10, 13], [240, 243, 248]);
  fill([10, 12, 11, 13], [46, 52, 66]);
  fill([13, 12, 14, 13], [46, 52, 66]);
  fill([14, 12, 15, 13], [240, 243, 248]);
  fill([11, 14, 13, 15], [150, 156, 168]);
  return img;
}

/** The face (head front with the hat over it) as an 8x8 RGBA image, for thumbnails. */
export function face(skin) {
  const out = blank(8, 8);
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      const o = (y * 8 + x) * 4;
      const b = ((8 + y) * 64 + 8 + x) * 4;
      const h = ((8 + y) * 64 + 40 + x) * 4;
      const a = skin.data[h + 3] / 255;
      for (let c = 0; c < 3; c++) out.data[o + c] = Math.round(skin.data[h + c] * a + skin.data[b + c] * (1 - a));
      out.data[o + 3] = 255;
    }
  }
  return out;
}

/**
 * A guess whether a raw skin image (before asTheGameDraws) has slim (3 pixel) arms: a slim right arm
 * leaves the last two columns of its picture (x 54..55, y 20..31) empty.
 */
export function looksSlim(img) {
  if (img.width !== 64 || img.height !== 64) return false;
  for (let y = 20; y < 32; y++) {
    for (let x = 54; x < 56; x++) if (img.data[(y * 64 + x) * 4 + 3] !== 0) return false;
  }
  return true;
}
