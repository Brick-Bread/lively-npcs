// A small MiniMessage reader for previews: colours (names, #hex, gradients, rainbow), bold, italic,
// underlined, strikethrough, obfuscated, reset and closing tags. Unknown tags stay as text, like
// MiniMessage does. It builds DOM nodes with text only (never HTML).

export const NAMED = {
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa', dark_red: '#aa0000',
  dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa', grey: '#aaaaaa', dark_gray: '#555555', dark_grey: '#555555',
  blue: '#5555ff', green: '#55ff55', aqua: '#55ffff', red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
};

const DECOR = {
  bold: 'bold', b: 'bold', italic: 'italic', i: 'italic', em: 'italic', underlined: 'underlined', u: 'underlined',
  strikethrough: 'strikethrough', st: 'strikethrough', obfuscated: 'obfuscated', obf: 'obfuscated',
};

function color(text) {
  if (!text) return null;
  const t = text.toLowerCase();
  if (NAMED[t]) return NAMED[t];
  if (/^#[0-9a-f]{6}$/.test(t)) return t;
  if (/^#[0-9a-f]{3}$/.test(t)) return '#' + t[1] + t[1] + t[2] + t[2] + t[3] + t[3];
  return null;
}

function mix(a, b, f) {
  const pa = [1, 3, 5].map(i => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map(i => parseInt(b.slice(i, i + 2), 16));
  return '#' + pa.map((c, i) => Math.round(c + (pb[i] - c) * f).toString(16).padStart(2, '0')).join('');
}

function hsl(h) {
  const f = n => {
    const k = (n + h / 30) % 12;
    const v = 1 - Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(v * 255).toString(16).padStart(2, '0');
  };
  return '#' + f(0) + f(8) + f(4);
}

/**
 * Reads MiniMessage text into lines of runs: [[{text, color, bold, italic, underlined, strikethrough,
 * obfuscated}]]. vars replaces {name} placeholders first.
 */
export function parse(input, vars = {}) {
  let src = String(input ?? '');
  for (const [k, v] of Object.entries(vars)) src = src.split('{' + k + '}').join(v);
  const lines = [[]];
  let stack = [];
  const gradients = [];
  const tagRe = /<(\/?)(!?)([a-z_#][a-z0-9_:#!,.\-]*)>/iy;

  const style = () => {
    const s = {color: null, bold: false, italic: false, underlined: false, strikethrough: false, obfuscated: false, fx: null};
    for (const f of stack) {
      if (f.color) {
        s.color = f.color;
        s.fx = null;
      }
      if (f.fx) s.fx = f.fx;
      if (f.decor) s[f.decor] = f.on;
    }
    return s;
  };
  const pushText = text => {
    if (!text) return;
    const s = style();
    if (s.fx) {
      for (const ch of text) {
        const run = {...s, text: ch, fx: s.fx, index: s.fx.count++};
        lines[lines.length - 1].push(run);
      }
    } else {
      lines[lines.length - 1].push({...s, text});
    }
  };

  let i = 0, text = '';
  while (i < src.length) {
    if (src[i] === '\\' && src[i + 1] === '<') {
      text += '<';
      i += 2;
      continue;
    }
    if (src[i] === '<') {
      tagRe.lastIndex = i;
      const m = tagRe.exec(src);
      if (m) {
        const closing = m[1] === '/', negate = m[2] === '!';
        const full = m[3].toLowerCase();
        const [name, ...args] = full.split(':');
        let frame = null, known = true;
        if (name === 'newline' || name === 'br') {
          pushText(text);
          text = '';
          lines.push([]);
          i += m[0].length;
          continue;
        }
        if (name === 'reset') {
          pushText(text);
          text = '';
          stack = [];
          i += m[0].length;
          continue;
        }
        if (DECOR[name]) frame = {tag: name, decor: DECOR[name], on: !negate};
        else if (color(name)) frame = {tag: name, color: color(name)};
        else if ((name === 'color' || name === 'colour' || name === 'c') && (closing || color(args[0]))) frame = {tag: name, color: color(args[0])};
        else if (name === 'gradient') {
          const cols = args.map(color).filter(Boolean);
          frame = {tag: name, fx: {kind: 'gradient', colors: cols.length >= 2 ? cols : ['#ffffff', '#000000'], count: 0}};
        } else if (name === 'rainbow') frame = {tag: name, fx: {kind: 'rainbow', reverse: args[0] === '!', count: 0}};
        else known = false;
        if (known) {
          pushText(text);
          text = '';
          if (closing) {
            for (let j = stack.length - 1; j >= 0; j--) {
              if (stack[j].tag === name || (DECOR[stack[j].tag] && DECOR[stack[j].tag] === DECOR[name])) {
                stack = stack.slice(0, j);
                break;
              }
            }
          } else {
            if (frame.fx) gradients.push(frame.fx);
            stack.push(frame);
          }
          i += m[0].length;
          continue;
        }
      }
    }
    text += src[i];
    i++;
  }
  pushText(text);

  // Colour every character of a gradient or rainbow now that its length is known.
  for (const line of lines) {
    for (const run of line) {
      if (!run.fx) continue;
      const fx = run.fx, n = Math.max(1, fx.count - 1), f = run.index / n;
      if (fx.kind === 'rainbow') {
        run.color = hsl(((fx.reverse ? 1 - f : f) * 360) % 360);
      } else {
        const seg = Math.min(fx.colors.length - 2, Math.floor(f * (fx.colors.length - 1)));
        const local = f * (fx.colors.length - 1) - seg;
        run.color = mix(fx.colors[seg], fx.colors[seg + 1], local);
      }
    }
  }
  return lines;
}

/** One line of MiniMessage as a DOM element (a div of spans). */
export function render(input, vars = {}, cls = 'plate-line') {
  const lines = parse(input, vars);
  const out = [];
  for (const runs of lines) {
    const line = document.createElement('div');
    line.className = cls;
    if (!runs.length || runs.every(r => !r.text)) line.textContent = ' ';
    for (const r of runs) {
      const span = document.createElement('span');
      span.textContent = r.text;
      span.style.color = r.color || '#ffffff';
      if (r.bold) span.style.fontWeight = '800';
      if (r.italic) span.style.fontStyle = 'italic';
      const deco = [r.underlined && 'underline', r.strikethrough && 'line-through'].filter(Boolean).join(' ');
      if (deco) span.style.textDecoration = deco;
      if (r.obfuscated) span.className = 'mm-obf';
      line.append(span);
    }
    out.push(line);
  }
  return out;
}

/** The text without tags (for list subtitles). */
export function plain(input, vars = {}) {
  return parse(input, vars).map(runs => runs.map(r => r.text).join('')).join(' ');
}
