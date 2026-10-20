// Snapping, for every drag in the editor: keyframe times to a grid of ticks (1, 2, 5 or 10), angles to
// 5 or 15 degrees (or not at all), positions to quarter pixels. Holding Shift while dragging passes
// free = true: times then go by single ticks, angles by tenths of a degree, positions by hundredths.
// Pure JavaScript: node tests it (tools/editor-tests.mjs).

export const TICK_STEPS = [1, 2, 5, 10];
export const ANGLE_STEPS = [0, 5, 15];

function clean(v) {
  return Object.is(v, -0) ? 0 : v;
}

/** A tick on the grid of step ticks (free: the nearest whole tick). */
export function snapTick(tick, step = 1, free = false) {
  const s = free ? 1 : Math.max(1, Math.round(step) || 1);
  return clean(Math.round(tick / s) * s);
}

/** An angle in degrees on the grid of step degrees (0 or free: to a tenth of a degree). */
export function snapAngle(deg, step = 0, free = false) {
  if (free || !step) return clean(Math.round(deg * 10) / 10);
  return clean(Math.round(deg / step) * step);
}

/** A position in pixels to a quarter pixel (free: a hundredth). */
export function snapPixels(px, free = false) {
  return clean(free ? Math.round(px * 100) / 100 : Math.round(px * 4) / 4);
}

/**
 * Where the ruler puts its marks for a zoom of px pixels a tick: a mark every `minor` ticks, a longer
 * one every `mid`, a labelled one every second (20 ticks); `label` ticks between tick labels (0: none).
 */
export function rulerSteps(px) {
  const minor = px >= 6 ? 1 : px >= 3 ? 2 : px >= 1.2 ? 5 : 10;
  const mid = minor <= 1 ? 5 : minor === 2 ? 10 : 20;
  const label = px * 5 >= 30 ? 5 : px * 10 >= 30 ? 10 : 0;
  return {minor, mid, label};
}
