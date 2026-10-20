// Onion skin: faint ghosts of the poses before and after the playhead, in two tints (blue before,
// orange after), so you can see where a move comes from and where it goes. Either the keyframes either
// side of the playhead, or every N ticks around it (View > Onion skin, the timeline's layers button).

import * as A from './anim.js';

export const BEFORE = 0x4aa8ff;
export const AFTER = 0xff9a3c;

/**
 * The ticks to draw ghosts at: [{tick, side ('before' | 'after'), near (true for the closest)}].
 * mode 'keys': the keyframes either side of the playhead; 'ticks:N': N and 2N ticks either side
 * (round the end of a looping animation, clamped to it otherwise).
 */
export function onionTicks(anim, tick, mode = 'keys') {
  if (!anim) return [];
  const t = Math.round(tick);
  const len = anim.length;
  const out = [];
  if (mode === 'keys') {
    const ats = anim.keyframes.map(k => k.at);
    const before = [...ats].reverse().find(a => a < t);
    const after = ats.find(a => a > t);
    if (before !== undefined) out.push({tick: before, side: 'before', near: true});
    else if (anim.loop && ats.length > 1 && ats[ats.length - 1] !== t) out.push({tick: ats[ats.length - 1], side: 'before', near: true});
    if (after !== undefined) out.push({tick: after, side: 'after', near: true});
    else if (anim.loop && ats.length > 1 && t !== 0) out.push({tick: 0, side: 'after', near: true});
    return out;
  }
  const n = Math.max(1, Number(String(mode).split(':')[1]) || 4);
  for (const [side, dir] of [['before', -1], ['after', 1]]) {
    for (let i = 1; i <= 2; i++) {
      let at = t + dir * n * i;
      if (anim.loop) at = ((at % len) + len) % len;
      else if (at < 0 || at > len) continue;
      if (at === t) continue;
      out.push({tick: at, side, near: i === 1});
    }
  }
  return out;
}

/** Puts the onion skin on a Viewport: viewport.onion.update(live) each frame draws the ghosts. */
export function mountOnion(viewport, {S, St}) {
  viewport.onion = {
    update(live) {
      const e = St.animEntry();
      if (!S.view.onion || S.mode !== 'animations' || !e || S.playing) {
        viewport.setGhosts([]);
        return;
      }
      const ghosts = onionTicks(e.draft, S.tick, S.view.onionMode).map(g => ({
        values: A.sample(e.draft, g.tick, S.channelKeys),
        tint: g.side === 'before' ? BEFORE : AFTER,
        opacity: g.near ? 0.3 : 0.16,
      }));
      viewport.setGhosts(ghosts, live);
    },
  };
}
