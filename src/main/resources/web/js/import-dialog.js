// File > Import from Blockbench (Ctrl+I, or a file dropped on the page): reads a .bbmodel or a Bedrock
// .animation.json made on a player model, plays the chosen animation on the NPC with a short report of
// what mapped, what was approximated and what was left out, then saves it as a new animation of your
// own under the id you pick. All in the browser: the file never goes to the server, only the result.

import {S} from './store.js';
import * as St from './store.js';
import * as Act from './actions.js';
import * as A from './anim.js';
import {readFile, convert, suggestId} from './bbimport.js';
import {Viewport} from './viewport.js';
import {loadSkin} from './skins.js';
import {h, fill, icon, dialog, toast} from './ui.js';

const ID = /^[a-z0-9_-]{1,40}$/;

/** "animation.player.wave_hello" -> "Wave hello". */
function niceName(name) {
  const last = String(name || 'Imported').split('.').pop().replace(/[_-]+/g, ' ').trim() || 'Imported';
  return last[0].toUpperCase() + last.slice(1);
}

/** Opens the dialog; initial {name, text} starts it with a file already read (a drop). */
export function openImportDialog(initial = null) {
  let file = null;          // readFile's result
  let fileName = '';
  let pick = 0;             // which animation in the file
  let result = null;        // convert's result
  let vp = null;
  let raf = 0;
  let saveBtn = null;

  const drop = h('div', {class: 'drop', tabIndex: 0, attrs: {role: 'button'}},
    icon('upload', 22), h('div', {}, h('strong', {text: 'Drop a .bbmodel or .animation.json here'}), ' or click to choose'),
    h('div', {class: 'hint', text: 'An animation made in Blockbench on a player model (bones head, body, rightArm, leftArm, rightLeg, leftLeg; root or waist to move the whole body).'}));
  const input = h('input', {type: 'file', accept: '.bbmodel,.json,application/json', hidden: true});
  const fileLine = h('div', {class: 'import-file'});
  const error = h('div', {class: 'error-text'});
  const animSel = h('select', {class: 'select', on: {change: () => { pick = Number(animSel.value); update(true); }}});
  const nameIn = h('input', {class: 'input', attrs: {spellcheck: 'false', 'aria-label': 'Name'}, on: {input: () => validate()}});
  const idIn = h('input', {class: 'input mono-input', attrs: {spellcheck: 'false', 'aria-label': 'Id', maxlength: 40}, on: {input: () => validate()}});
  const idError = h('div', {class: 'error-text'});
  const facts = h('div', {class: 'import-facts'});
  const report = h('div', {class: 'import-report'});
  const previewHost = h('div', {class: 'import-preview'});
  const previewTime = h('div', {class: 'import-time'});
  const details = h('div', {class: 'import-grid'},
    h('div', {class: 'import-left'}, previewHost, previewTime),
    h('div', {class: 'import-right'},
      h('div', {class: 'prop-grid'},
        h('label', {text: 'Animation'}), animSel,
        h('label', {text: 'Name'}), nameIn,
        h('label', {text: 'Save as id'}), h('div', {}, idIn, idError)),
      facts, report));
  details.hidden = true;
  const body = h('div', {class: 'import-body'}, drop, input, fileLine, error, details);

  function validate() {
    const id = idIn.value.trim();
    let msg = '';
    if (!id) msg = 'Give it an id.';
    else if (!ID.test(id)) msg = 'Use only a-z, 0-9, _ and - (at most 40).';
    else if (id === 'none') msg = '"none" means no animation: pick another id.';
    else if (S.anims.has(id)) msg = `There is already an animation called ${id}: pick another id.`;
    idError.textContent = msg;
    idIn.classList.toggle('invalid', !!msg);
    if (saveBtn) saveBtn.disabled = !result || !!msg || !nameIn.value.trim();
    return !msg;
  }

  function list(kind, iconName, title, items) {
    if (!items.length) return null;
    return h('div', {class: ['import-list', kind]}, h('div', {class: 'import-list-head'}, icon(iconName, 14), title + ` (${items.length})`),
      h('ul', {}, items.map(t => h('li', {text: t}))));
  }

  function update(resetNames) {
    if (!file) return;
    const anim = file.animations[pick];
    result = convert(file, anim);
    const a = result.animation;
    if (resetNames) {
      nameIn.value = niceName(anim.name);
      idIn.value = suggestId(anim.name, new Set(S.anims.keys()));
    }
    fill(facts, h('span', {text: `${A.seconds(a.length)} (${a.length} ticks)`}), h('span', {text: `${a.keyframes.length} keyframes`}),
      h('span', {text: a.loop ? 'loops' : 'plays once'}));
    const r = result.report;
    fill(report, list('ok', 'check', 'Mapped', r.mapped), list('warn', 'info', 'Approximated', r.approximated), list('off', 'x', 'Left out', r.ignored));
    validate();
  }

  function load(name, text) {
    error.textContent = '';
    try {
      file = readFile(name, text);
    } catch (e) {
      file = null;
      result = null;
      error.textContent = e.message;
      details.hidden = true;
      if (saveBtn) saveBtn.disabled = true;
      return;
    }
    fileName = name;
    pick = 0;
    const kind = file.kind === 'bbmodel' ? (file.flipped ? 'Blockbench model (before 5.0)' : 'Blockbench model') : 'Bedrock animation file';
    fill(fileLine, icon('film', 15), h('b', {text: fileName}), h('span', {class: 'muted', text: ` · ${kind} · ${file.animations.length} animation${file.animations.length === 1 ? '' : 's'}`}),
      h('div', {class: 'spacer'}), h('button', {type: 'button', class: 'btn btn-xs btn-ghost', text: 'Choose another…', on: {click: () => input.click()}}));
    fill(animSel, file.animations.map((a, i) => h('option', {value: i, text: a.name})));
    animSel.disabled = file.animations.length < 2;
    drop.hidden = true;
    details.hidden = false;
    update(true);
    startPreview();
  }

  async function readBlob(f) {
    if (!f) return;
    if (f.size > 8 * 1024 * 1024) {
      error.textContent = `${f.name} is bigger than 8 MB: that is not an animation file.`;
      return;
    }
    load(f.name, await f.text());
  }

  function startPreview() {
    if (vp) return;
    vp = new Viewport(previewHost, {});
    vp.picking = false;
    vp.view('three-quarter', false);
    const npc = St.shownNpc();
    const key = npc && npc.entity === 'player' ? npc.skin : '';
    const slim = !!(key && (S.skins.find(s => s.key === key) || {}).slim);
    if (key) loadSkin(key).then(s => vp && vp.setSkin(s ? s.img : null, slim)).catch(() => {});
    const t0 = performance.now();
    const frame = now => {
      if (!vp) return;
      if (result) {
        const a = result.animation;
        const span = a.loop ? a.length : a.length + 10;
        let tick = ((now - t0) / 1000 * A.TICKS_PER_SECOND) % span;
        if (!a.loop) tick = Math.min(tick, a.length);
        vp.setPose(A.sample(a, tick, S.channelKeys), {slim: vp.slim});
        previewTime.textContent = `${(tick / 20).toFixed(2)} s / ${A.seconds(a.length)}`;
      }
      vp.render(now);
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
  }

  function stopPreview() {
    cancelAnimationFrame(raf);
    if (vp) {
      vp.renderer.dispose();
      vp.renderer.forceContextLoss();
      vp = null;
    }
  }

  input.addEventListener('change', () => readBlob(input.files[0]));
  drop.addEventListener('click', () => input.click());
  drop.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', e => {
    e.preventDefault();
    drop.classList.remove('over');
    readBlob(e.dataTransfer.files[0]);
  });

  const done = dialog({
    title: 'Import from Blockbench',
    wide: true,
    body,
    actions: [{label: 'Cancel', value: null}, {label: 'Import and save', kind: 'primary', value: () => 'save', disabled: true}],
    onOpen: (modal, buttons) => {
      saveBtn = buttons[1];
      if (initial) load(initial.name, initial.text);
      else drop.focus();
    },
  });
  done.then(async v => {
    stopPreview();
    if (v !== 'save' || !result || !validate()) return;
    const id = idIn.value.trim();
    const draft = A.normalize({...A.clone(result.animation), id, name: nameIn.value.trim(), builtin: false, edited: false});
    St.addDraft('anim', draft);
    St.setMode('animations');
    St.selectAnim(id);
    toast(`Imported ${draft.name} from ${fileName} as ${id}.`, 'ok', 3000);
    await Act.saveAnim(id);
  });
  return done;
}

/** Whether a dropped or chosen file looks like something to import. */
export function isBlockbenchFile(name) {
  return /\.(bbmodel|json)$/i.test(String(name || ''));
}
