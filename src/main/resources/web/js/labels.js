// Words for people: what each part and channel is called in the editor, which channels belong to
// which part, and the colour of the axis each one turns about (the gizmo rings use the same).

/** Selectable groups, in the order the inspector lists them. 'root' is the whole body. */
export const GROUPS = [
  {part: 'root', name: 'Whole body', channels: ['root-x', 'root-y', 'root-z', 'root-yaw']},
  {part: 'head', name: 'Head', channels: ['head-yaw', 'head-pitch', 'head-roll']},
  {part: 'body', name: 'Body', channels: ['torso-pitch', 'torso-roll', 'torso-yaw', 'shoulders']},
  {part: 'right_arm', name: 'Right arm', channels: ['right-arm-forward', 'right-arm-out', 'right-arm-across']},
  {part: 'left_arm', name: 'Left arm', channels: ['left-arm-forward', 'left-arm-out', 'left-arm-across']},
  {part: 'right_leg', name: 'Right leg', channels: ['right-leg-forward', 'right-leg-out']},
  {part: 'left_leg', name: 'Left leg', channels: ['left-leg-forward', 'left-leg-out']},
];

export const PART_NAMES = Object.fromEntries(GROUPS.map(g => [g.part, g.name]));
PART_NAMES.held = 'Held item';

const LABELS = {
  'root-x': 'Step to its right',
  'root-y': 'Rise (hop)',
  'root-z': 'Step forward',
  'root-yaw': 'Spin to its right',
  'torso-pitch': 'Bend forward',
  'torso-roll': 'Lean to its right',
  'torso-yaw': 'Twist to its right',
  'shoulders': 'Shrug',
  'head-yaw': 'Turn to its right',
  'head-pitch': 'Look down',
  'head-roll': 'Tilt to its right',
  'right-arm-forward': 'Raise forward',
  'right-arm-out': 'Out to its side',
  'right-arm-across': 'Across its chest',
  'left-arm-forward': 'Raise forward',
  'left-arm-out': 'Out to its side',
  'left-arm-across': 'Across its chest',
  'right-leg-forward': 'Swing forward',
  'right-leg-out': 'Out to its side',
  'left-leg-forward': 'Swing forward',
  'left-leg-out': 'Out to its side',
};

/** The axis (in the part's frame) each channel turns or moves along: x across, y up, z front. */
const AXES = {
  'root-x': 'x', 'root-y': 'y', 'root-z': 'z', 'root-yaw': 'y',
  'torso-pitch': 'x', 'torso-roll': 'z', 'torso-yaw': 'y',
  'head-yaw': 'y', 'head-pitch': 'x', 'head-roll': 'z',
  'right-arm-forward': 'x', 'right-arm-out': 'z', 'right-arm-across': 'y',
  'left-arm-forward': 'x', 'left-arm-out': 'z', 'left-arm-across': 'y',
  'right-leg-forward': 'x', 'right-leg-out': 'z', 'left-leg-forward': 'x', 'left-leg-out': 'z',
};

export const AXIS_COLORS = {x: '#ff6b81', y: '#6be08a', z: '#5aa9ff'};

export function channelLabel(key) {
  return LABELS[key] || key.replace(/-/g, ' ');
}

export function channelAxis(key) {
  return AXES[key] || null;
}

/** The group a channel is in. */
export function groupOf(key) {
  return GROUPS.find(g => g.channels.includes(key)) || null;
}

/** Long name, e.g. "Right arm: raise forward". */
export function channelTitle(key) {
  const g = groupOf(key);
  const label = channelLabel(key);
  return g ? `${g.name}: ${label[0].toLowerCase()}${label.slice(1)}` : label;
}

export const EASING_NAMES = {
  'linear': 'Linear',
  'ease-in': 'Ease in (speeds up)',
  'ease-out': 'Ease out (slows down)',
  'ease-in-out': 'Ease in and out',
  'step': 'Step (jumps at the end)',
};
