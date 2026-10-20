// The graph editor's curves: one value over time, sampled exactly as the game plays it (anim.js's
// port of Animation.sample), segment by segment, so each segment shows its own easing (linear, ease
// in, ease out, ease in and out, or a step that holds and then jumps). Pure JavaScript: node tests it.

import * as A from './anim.js';

/**
 * The segments between keyframes: [{from, to, ease, a, b}] where a and b are keyframe indexes and ease
 * is the later keyframe's (it decides the way in). A looping animation adds the glide from its last
 * keyframe back to the first, ending at its length (b = 0); a one-shot one holds its last pose to the end.
 */
export function segments(anim) {
  const ks = anim.keyframes;
  const out = [];
  for (let i = 1; i < ks.length; i++) out.push({from: ks[i - 1].at, to: ks[i].at, ease: ks[i].ease, a: i - 1, b: i});
  const last = ks[ks.length - 1];
  if (anim.loop && anim.length > last.at) out.push({from: last.at, to: anim.length, ease: ks[0].ease, a: ks.length - 1, b: 0});
  return out;
}

/**
 * The curve of one channel from tick 0 to the animation's length: [[tick, value]], sampled every step
 * ticks inside each segment, with a point just before each segment's end so a step's jump is drawn
 * upright. Values are the stored ones (anim.js's sample).
 */
export function curve(anim, key, step = 0.5) {
  const pts = [];
  const at = t => A.sample(anim, t, [key])[key];
  const segs = segments(anim);
  const first = anim.keyframes[0].at;
  pts.push([first, at(first)]);
  for (const s of segs) {
    const n = Math.max(1, Math.ceil((s.to - s.from) / step));
    for (let i = 1; i < n; i++) {
      const t = s.from + (s.to - s.from) * i / n;
      pts.push([t, at(t)]);
    }
    const end = s.to - 1e-4;
    if (end > s.from) pts.push([end, at(end)]);
    // The end of a loop's last glide is its first keyframe's value again.
    pts.push([s.to, s.b === 0 ? at(0) : at(s.to)]);
  }
  const lastAt = anim.keyframes[anim.keyframes.length - 1].at;
  if (!anim.loop && anim.length > lastAt) pts.push([anim.length, at(lastAt)]);
  return pts;
}

/** The lowest and highest value of a channel's curve, and 0: [min, max]. */
export function range(anim, key) {
  let lo = 0, hi = 0;
  for (const [, v] of curve(anim, key, 1)) {
    lo = Math.min(lo, v);
    hi = Math.max(hi, v);
  }
  return [lo, hi];
}

/** The segment a tick falls in (for clicking on the curve), or null. */
export function segmentAt(anim, tick) {
  return segments(anim).find(s => tick >= s.from && tick <= s.to) || null;
}

/** A "nice" spacing for value grid lines so about count lines fit in span. */
export function gridStep(span, count = 6) {
  const raw = Math.max(1e-6, span / count);
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  for (const m of [1, 2, 2.5, 5, 10]) if (raw <= m * mag) return m * mag;
  return 10 * mag;
}
