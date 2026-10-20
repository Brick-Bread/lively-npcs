// Animations in the browser: the same format as anim/Animation.toMap() (and the YAML files), and a
// faithful port of Animation.sample and Easing, so a draft plays here exactly as it will in game.
// Pure JavaScript, no page: node can import it.

/** Channel keys in the order of anim/Channel (the server's /api/state list replaces this). */
export const DEFAULT_CHANNELS = [
  'root-x', 'root-y', 'root-z', 'root-yaw', 'torso-pitch', 'torso-roll', 'torso-yaw',
  'head-yaw', 'head-pitch', 'head-roll', 'shoulders',
  'right-arm-forward', 'right-arm-out', 'right-arm-across', 'left-arm-forward', 'left-arm-out', 'left-arm-across',
  'right-leg-forward', 'right-leg-out', 'left-leg-forward', 'left-leg-out',
];

export const EASINGS = ['linear', 'ease-in', 'ease-out', 'ease-in-out', 'step'];

export const TICKS_PER_SECOND = 20;

/** Easing.apply: the share of the way along at share f of the time. */
export function ease(key, f) {
  f = Math.max(0, Math.min(1, f));
  switch (key) {
    case 'ease-in': return f * f;
    case 'ease-out': return 1 - (1 - f) * (1 - f);
    case 'ease-in-out': return f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
    case 'step': return f >= 1 ? 1 : 0;
    default: return f;
  }
}

/** A keyframe's value for a channel (0 when left out), as a Java float. */
function value(k, key) {
  const n = k.values[key];
  return typeof n === 'number' && Number.isFinite(n) ? Math.fround(n) : 0;
}

/**
 * The Animation constructor's rules on a draft (in place, and returned): keyframes sorted by tick, a
 * rest keyframe added at 0 when there is none, length at least 1 and at least the last keyframe's tick.
 */
export function normalize(anim) {
  const keys = (anim.keyframes || []).map(k => ({at: Math.max(0, Math.round(k.at || 0)), ease: EASINGS.includes(k.ease) ? k.ease : 'linear', values: {...(k.values || {})}}));
  keys.sort((a, b) => a.at - b.at);
  if (!keys.length || keys[0].at !== 0) keys.unshift({at: 0, ease: 'linear', values: {}});
  anim.keyframes = keys;
  anim.length = Math.max(Math.max(1, Math.round(anim.length || 0)), keys[keys.length - 1].at);
  anim.loop = !!anim.loop;
  anim.glance = !!anim.glance;
  if (!anim.name || !String(anim.name).trim()) anim.name = anim.id;
  return anim;
}

/**
 * Animation.sample: every channel's value at this tick (fractions allowed). Between two keyframes the
 * later one's easing decides the way; past the last keyframe a looping animation glides back to the
 * first by length, a one-shot one holds the last. Before 0 is the first.
 */
export function sample(anim, tick, channels = DEFAULT_CHANNELS) {
  const k = anim.keyframes;
  const length = anim.length;
  const out = {};
  if (anim.loop) tick = ((tick % length) + length) % length;
  const copy = frame => {
    for (const c of channels) out[c] = value(frame, c);
    return out;
  };
  if (tick <= 0) return copy(k[0]);
  let next = 0;
  while (next < k.length && k[next].at <= tick) next++;
  const a = k[next - 1];
  let b, end;
  if (next < k.length) {
    b = k[next];
    end = b.at;
  } else if (anim.loop && length > a.at) {
    b = k[0];
    end = length;
  } else {
    return copy(a);
  }
  const e = ease(b.ease, (tick - a.at) / (end - a.at));
  for (const c of channels) {
    const from = value(a, c), to = value(b, c);
    out[c] = Math.fround(from + (to - from) * e);
  }
  return out;
}

/** The channels that turn the NPC, its torso or its head about the vertical: a whole turn (360) is no turn. */
export const WHOLE_TURNS = ['root-yaw', 'torso-yaw', 'head-yaw'];

/** An angle brought into -180..180 (Look.wrap), so a turn takes the short way round. */
export function wrapTurn(degrees) {
  let d = degrees % 360;
  if (d > 180) d -= 360;
  else if (d < -180) d += 360;
  return d;
}

/** Whether a channel value is its rest pose: 0, or a whole number of turns for a turning channel (RigPose.atRest). */
export function atRest(key, v) {
  return (WHOLE_TURNS.includes(key) ? wrapTurn(v) : v) === 0;
}

/** Whether it ends at rest (every channel at rest at the last keyframe; root-yaw 360 counts, as in AnimationStore). */
export function endsAtRest(anim, channels = DEFAULT_CHANNELS) {
  const last = anim.keyframes[anim.keyframes.length - 1];
  return channels.every(c => atRest(c, value(last, c)));
}

/** A draft from the server's JSON ({id, builtin, edited, ...Animation.toMap()}). */
export function fromServer(entry) {
  const keyframes = (entry.keyframes || []).map(km => {
    const values = {};
    for (const [key, v] of Object.entries(km)) {
      if (key !== 'at' && key !== 'ease' && typeof v === 'number') values[key] = v;
    }
    return {at: km.at, ease: km.ease || 'linear', values};
  });
  return normalize({
    id: entry.id, name: entry.name, length: entry.length, loop: entry.loop, glance: entry.glance, keyframes,
    builtin: !!entry.builtin, edited: !!entry.edited,
  });
}

function round3(v) {
  return Math.round(v * 1000) / 1000;
}

/** Animation.toMap(): name, length, loop, glance, keyframes (only channels that are not 0). */
export function toMap(anim, channels = DEFAULT_CHANNELS) {
  return {
    name: anim.name,
    length: anim.length,
    loop: anim.loop,
    glance: anim.glance,
    keyframes: anim.keyframes.map(k => {
      const km = {at: k.at};
      if (k.ease && k.ease !== 'linear') km.ease = k.ease;
      for (const c of channels) {
        const v = value(k, c);
        if (v !== 0) km[c] = round3(v);
      }
      return km;
    }),
  };
}

/** A stable text form of a draft, to tell whether two versions differ. */
export function canonical(anim, channels = DEFAULT_CHANNELS) {
  return JSON.stringify(toMap(anim, channels));
}

/** A deep copy of a draft. */
export function clone(anim) {
  return {...anim, keyframes: anim.keyframes.map(k => ({at: k.at, ease: k.ease, values: {...k.values}}))};
}

/** The index of the keyframe at exactly this tick, or -1. */
export function keyAt(anim, tick) {
  return anim.keyframes.findIndex(k => k.at === tick);
}

/** What changes left and right when a pose is mirrored. */
const MIRROR_NEGATE = ['root-x', 'root-yaw', 'torso-roll', 'torso-yaw', 'head-yaw', 'head-roll'];

/** A pose (channel values) mirrored left to right: arms and legs swap, turns and tilts go the other way. */
export function mirror(values) {
  const out = {};
  for (const [key, v] of Object.entries(values)) {
    if (!v) continue;
    if (MIRROR_NEGATE.includes(key)) out[key] = -v;
    else if (key.startsWith('right-')) out['left-' + key.slice(6)] = v;
    else if (key.startsWith('left-')) out['right-' + key.slice(5)] = v;
    else out[key] = v;
  }
  return out;
}

/** Only the channels that are not 0, rounded as the server keeps them. */
export function compact(values) {
  const out = {};
  for (const [key, v] of Object.entries(values)) {
    if (typeof v === 'number' && Number.isFinite(v) && round3(v) !== 0) out[key] = round3(v);
  }
  return out;
}

/** "1.25 s" for a tick count. */
export function seconds(ticks) {
  const s = ticks / TICKS_PER_SECOND;
  return (Number.isInteger(s) ? s.toFixed(0) : s.toFixed(2).replace(/0$/, '')) + ' s';
}
