// Skin images for the 3D view and the thumbnails: fetched once per key (with the login), decoded,
// prepared the way the game draws them, and kept for the page's life.

import {apiBlob} from './api.js';
import {asTheGameDraws, face, neutralSkin} from './model.js';
import {h, icon, imageCanvas} from './ui.js';

const cache = new Map();

/** Decodes an image file (Blob) to {width, height, data}. */
export async function decode(blob) {
  const bitmap = await createImageBitmap(blob, {premultiplyAlpha: 'none', colorSpaceConversion: 'none'});
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const ctx = canvas.getContext('2d', {willReadFrequently: true});
  ctx.drawImage(bitmap, 0, 0);
  const data = ctx.getImageData(0, 0, bitmap.width, bitmap.height);
  bitmap.close();
  return {width: data.width, height: data.height, data: data.data};
}

/** The skin with this key, prepared: {img, face}. Rejects when it cannot be had. */
export function loadSkin(key) {
  if (!key) return Promise.resolve(null);
  if (!cache.has(key)) {
    const p = apiBlob('skins/' + encodeURIComponent(key) + '.png')
      .then(decode)
      .then(raw => {
        const img = asTheGameDraws(raw);
        return {img, face: face(img)};
      });
    p.catch(() => cache.delete(key));
    cache.set(key, p);
  }
  return cache.get(key);
}

const neutralFace = face(neutralSkin());

/** A face thumbnail for a skin key ('' = the plain skin): a canvas that fills in once loaded. */
export function faceThumb(key, cls = '') {
  const holder = h('div', {class: cls});
  if (!key) {
    holder.append(imageCanvas(neutralFace));
    return holder;
  }
  holder.append(icon('user', 16));
  loadSkin(key).then(s => {
    if (s) holder.replaceChildren(imageCanvas(s.face));
  }).catch(() => {
    holder.replaceChildren(icon('warn', 14));
    holder.title = 'This skin\'s picture could not be loaded';
  });
  return holder;
}
