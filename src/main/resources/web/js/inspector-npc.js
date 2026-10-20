// The inspector in NPC mode: name plate (with a live preview), skin (stored, by player name, image
// link or file), held item and icon, click actions, animations, looking, position and facing,
// Save and Delete.

import {S, on} from './store.js';
import * as St from './store.js';
import * as Act from './actions.js';
import {h, fill, icon, button, toggle, checkbox} from './ui.js';
import {render as renderMini} from './minimessage.js';
import {itemPicker} from './items.js';
import {faceThumb, decode} from './skins.js';
import {looksSlim} from './model.js';

const CLICK_TYPES = [
  ['player', 'Player command', 'Command run as the player, without /: shop mobs'],
  ['console', 'Console command', 'Command run by the server, without /: give {player} diamond 1'],
  ['message', 'Chat message', 'MiniMessage sent to the player: <green>Hello {player}!'],
];

function parseClick(line) {
  const i = line.indexOf(':');
  const type = i > 0 ? line.slice(0, i) : '';
  if (CLICK_TYPES.some(t => t[0] === type)) return {type, text: line.slice(i + 1)};
  return {type: 'raw', text: line};
}

function joinClick(c) {
  return c.type === 'raw' ? c.text : c.type + ':' + c.text;
}

export function mountNpcInspector(root) {
  let builtFor = null;
  const collapsed = new Set();
  let skinTab = 'player';
  let addingSkin = false;
  let skinCount = S.skins.length;

  function section(id, title, iconName, ...body) {
    const sec = h('div', {class: ['section', collapsed.has(id) && 'collapsed']});
    const head = h('div', {class: 'section-head', tabIndex: 0, attrs: {role: 'button', 'aria-expanded': String(!collapsed.has(id))},
      on: {
        click: () => toggleSection(),
        keydown: e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSection(); } },
      }}, icon(iconName, 14), h('span', {text: title}), h('div', {class: 'spacer'}), icon('chevron', 14, 'chev'));
    function toggleSection() {
      if (collapsed.has(id)) collapsed.delete(id);
      else collapsed.add(id);
      sec.classList.toggle('collapsed');
      head.setAttribute('aria-expanded', String(!collapsed.has(id)));
    }
    sec.append(head, h('div', {class: 'section-body'}, ...body));
    return sec;
  }

  // ---- name plate ----
  function plateSection(n) {
    const rowsBox = h('div', {class: 'list-rows'});
    const preview = h('div', {class: 'plate-preview', attrs: {'aria-label': 'Preview'}});
    const draw = () => {
      const d = St.npcEntry().draft;
      const lines = d.plate.length ? d.plate.flatMap(l => renderMini(l, {npc: d.id})) : [h('span', {class: 'muted small', text: 'No name plate'})];
      fill(preview, lines);
    };
    const rows = () => {
      const d = St.npcEntry().draft;
      fill(rowsBox, ...d.plate.map((line, i) => {
        const input = h('input', {class: 'input mono-input', value: line, placeholder: '<gold><bold>Name', attrs: {spellcheck: 'false', 'aria-label': 'Line ' + (i + 1)},
          on: {input: () => { St.changeNpc(x => { x.plate[i] = input.value; }, 'plate' + i); draw(); }}});
        return h('div', {class: 'list-row'}, input,
          button(null, 'up', {class: 'btn-xs btn-ghost', title: 'Move up', disabled: i === 0, onClick: () => { St.changeNpc(x => { [x.plate[i - 1], x.plate[i]] = [x.plate[i], x.plate[i - 1]]; }); rows(); draw(); }}),
          button(null, 'x', {class: 'btn-xs btn-ghost', title: 'Remove this line', onClick: () => { St.changeNpc(x => { x.plate.splice(i, 1); }); rows(); draw(); }}));
      }));
    };
    rows();
    draw();
    return section('plate', 'Name plate', 'sparkle',
      preview,
      rowsBox,
      h('div', {class: 'row'}, button('Add line', 'plus', {class: 'btn-sm', onClick: () => { St.changeNpc(x => { x.plate.push(''); }); rows(); draw(); rowsBox.querySelector('.list-row:last-child input')?.focus(); }})),
      h('div', {class: 'hint', text: 'MiniMessage: <gold>, <bold>, <#ff8800>, <gradient:red:blue>…  {npc} is its id.'}));
  }

  // ---- skin ----
  function skinSection(n) {
    const current = h('div', {class: 'skin-current'});
    const grid = h('div', {class: 'skin-grid', role: 'listbox', attrs: {'aria-label': 'Stored skins'}});
    const drawCurrent = () => {
      const d = St.npcEntry().draft;
      const s = S.skins.find(x => x.key === d.skin);
      fill(current, faceThumb(d.skin, 'skin-face'), h('div', {class: 'grow'},
        h('div', {style: {fontWeight: 600}, text: d.skin ? (s ? sourceName(s.source) : 'Unknown skin') : 'Default skin'}),
        h('div', {class: 'small muted ellipsis', text: d.skin ? `${d.skin}${s && s.slim ? ' · slim arms' : ''}` : 'Players see the game\'s own skin; no moving model'})));
    };
    const drawGrid = () => {
      const d = St.npcEntry().draft;
      const tile = (key, label, slim) => h('button', {
        type: 'button', class: ['skin-tile', d.skin === key && 'active'], title: label, attrs: {'aria-label': label, 'aria-selected': String(d.skin === key)},
        on: {click: () => { St.changeNpc(x => { x.skin = key; }); drawCurrent(); drawGrid(); St.emit('view'); }},
      }, key ? faceThumb(key) : faceThumb(''), slim ? h('span', {class: 'slim', text: 'slim'}) : null);
      fill(grid, tile('', 'Default skin', false), ...S.skins.map(s => tile(s.key, sourceName(s.source) + ' (' + s.key + ')', s.slim)));
    };
    drawCurrent();
    drawGrid();

    // Adding a skin.
    const tabs = h('div', {class: 'seg'});
    const pane = h('div', {class: 'field'});
    const status = h('div', {class: 'field'});
    const drawTabs = () => fill(tabs, ...[['player', 'Player name'], ['url', 'Image link'], ['upload', 'Upload']].map(([k, label]) =>
      h('button', {type: 'button', class: skinTab === k ? 'active' : '', text: label, on: {click: () => { skinTab = k; drawTabs(); drawPane(); }}})));
    let busy = false;

    async function add(request, progress) {
      if (busy) return;
      busy = true;
      const started = Date.now();
      const bar = h('div', {class: 'progress indeterminate'}, h('div'));
      const note = h('div', {class: 'hint'});
      const tick = () => {
        const s = Math.round((Date.now() - started) / 1000);
        note.textContent = request.type === 'player' ? `Asking Mojang for ${request.value}'s skin… ${s} s` : `Signing the skin with MineSkin… ${s} s (up to a minute)`;
      };
      tick();
      const timer = setInterval(tick, 1000);
      fill(status, bar, note);
      addingSkin = true;
      try {
        const skin = await Act.addSkin(request, share => {
          if (share < 1) {
            bar.classList.remove('indeterminate');
            bar.firstChild.style.width = Math.round(share * 100) + '%';
            note.textContent = `Uploading… ${Math.round(share * 100)}%`;
          } else {
            bar.classList.add('indeterminate');
            bar.firstChild.style.width = '';
          }
        });
        St.changeNpc(x => { x.skin = skin.key; });
        fill(status, h('div', {class: 'banner banner-info'}, icon('check', 15), h('div', {text: `Skin added and put on ${St.npcEntry().draft.id}. Save to show it in game.`})));
        drawCurrent();
        drawGrid();
        drawPane();
        St.emit('view');
      } catch (e) {
        fill(status, h('div', {class: 'banner banner-danger'}, icon('warn', 15), h('div', {text: e.message})));
      } finally {
        clearInterval(timer);
        busy = false;
        addingSkin = false;
        skinCount = S.skins.length;
      }
    }

    function drawPane() {
      if (skinTab === 'player') {
        const input = h('input', {class: 'input', placeholder: 'Player name, e.g. Notch', attrs: {spellcheck: 'false', autocomplete: 'off', maxlength: 16}});
        const go = () => {
          const v = input.value.trim();
          if (!/^[A-Za-z0-9_]{1,16}$/.test(v)) {
            fill(status, h('div', {class: 'error-text', text: 'A player name has 1 to 16 letters, numbers or _.'}));
            return;
          }
          add({type: 'player', value: v, slim: false});
        };
        input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
        fill(pane, h('div', {class: 'field-row'}, input, button('Add', 'plus', {class: 'btn-sm', onClick: go})),
          h('div', {class: 'hint', text: 'Their skin today, signed by Mojang. Slim arms come with it.'}));
      } else if (skinTab === 'url') {
        const input = h('input', {class: 'input', placeholder: 'https://… .png', attrs: {spellcheck: 'false', autocomplete: 'off'}});
        const slim = checkbox('Slim arms', false, () => {});
        const go = () => {
          const v = input.value.trim();
          if (!/^https?:\/\/\S+$/i.test(v)) {
            fill(status, h('div', {class: 'error-text', text: 'Paste a web link that starts with http:// or https://.'}));
            return;
          }
          add({type: 'url', value: v, slim: slim.input.checked});
        };
        input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
        fill(pane, h('div', {class: 'field-row'}, input, button('Add', 'plus', {class: 'btn-sm', onClick: go})), slim.el,
          h('div', {class: 'hint', text: 'A 64x64 skin image. Signed through MineSkin (needs skins.mineskin-key in config.yml).'}));
      } else {
        const file = h('input', {type: 'file', accept: 'image/png', hidden: true});
        const slim = checkbox('Slim arms', false, () => {});
        let chosen = null;
        const drop = h('div', {class: 'drop', tabIndex: 0, attrs: {role: 'button'}},
          icon('upload', 22), h('div', {}, h('strong', {text: 'Drop a skin PNG here'}), ' or click to choose'), h('div', {class: 'hint', text: '64x64 or 64x32, up to 1 MB'}));
        const sendBtn = button('Upload and add', 'upload', {class: 'btn-sm btn-primary', disabled: true, onClick: () => {
          if (chosen) add({type: 'upload', value: chosen.base64, slim: slim.input.checked}, null);
        }});
        const pick = async f => {
          chosen = null;
          sendBtn.disabled = true;
          if (!f) return;
          if (f.type && f.type !== 'image/png') {
            fill(status, h('div', {class: 'error-text', text: `${f.name} is not a PNG image.`}));
            return;
          }
          if (f.size > 1024 * 1024) {
            fill(status, h('div', {class: 'error-text', text: `${f.name} is bigger than 1 MB: a skin is a few kilobytes.`}));
            return;
          }
          try {
            const img = await decode(f);
            if (img.width !== 64 || (img.height !== 64 && img.height !== 32)) {
              fill(status, h('div', {class: 'error-text', text: `${f.name} is ${img.width}x${img.height}: a skin is 64x64 (or an old 64x32).`}));
              return;
            }
            slim.input.checked = looksSlim(img);
            const bytes = new Uint8Array(await f.arrayBuffer());
            let bin = '';
            for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
            chosen = {name: f.name, base64: btoa(bin)};
            sendBtn.disabled = false;
            fill(status, h('div', {class: 'hint', text: `${f.name} is ready (${img.width}x${img.height}${slim.input.checked ? ', looks slim' : ''}).`}));
          } catch {
            fill(status, h('div', {class: 'error-text', text: `${f.name} could not be read as an image.`}));
          }
        };
        file.addEventListener('change', () => pick(file.files[0]));
        drop.addEventListener('click', () => file.click());
        drop.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); file.click(); } });
        drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
        drop.addEventListener('dragleave', () => drop.classList.remove('over'));
        drop.addEventListener('drop', e => {
          e.preventDefault();
          drop.classList.remove('over');
          pick(e.dataTransfer.files[0]);
        });
        fill(pane, drop, file, h('div', {class: 'row'}, slim.el, h('div', {class: 'spacer'}), sendBtn),
          h('div', {class: 'hint', text: 'Signed through MineSkin (needs skins.mineskin-key in config.yml).'}));
      }
    }
    drawTabs();
    drawPane();
    return section('skin', 'Skin', 'user', current, h('div', {class: 'field-label', text: 'Stored skins'}), grid,
      h('div', {class: 'field-label', text: 'Add a skin'}), tabs, pane, status);
  }

  function sourceName(source) {
    if (!source) return 'Skin';
    if (source.startsWith('player:')) return source.slice(7) + '\'s skin';
    if (source.startsWith('url:')) return 'From a link';
    if (source === 'upload') return 'Uploaded';
    if (source === 'file') return 'From a file';
    return source;
  }

  // ---- items ----
  function itemsSection(n) {
    const held = itemPicker(n.held, v => St.changeNpc(x => { x.held = v; }), {title: 'The item in its left hand'});
    const iconPick = itemPicker(n.icon, v => St.changeNpc(x => { x.icon = v; }), {title: 'An item floating, turning, over the plate'});
    return section('items', 'Held item and icon', 'hand',
      n.entity === 'player' ? h('div', {class: 'field'}, h('label', {text: 'In its left hand'}), held.el) : null,
      h('div', {class: 'field'}, h('label', {text: 'Icon over the name plate'}), iconPick.el));
  }

  // ---- click actions ----
  function clickSection(n) {
    const box = h('div', {class: 'list-rows'});
    const rows = () => {
      const d = St.npcEntry().draft;
      fill(box, ...d.click.map((line, i) => {
        const c = parseClick(line);
        const sel = h('select', {class: 'select', style: {width: '142px', flex: 'none'}, attrs: {'aria-label': 'What it does'}},
          ...CLICK_TYPES.map(([k, label]) => h('option', {value: k, text: label, selected: c.type === k})),
          c.type === 'raw' ? h('option', {value: 'raw', text: 'As written', selected: true}) : null);
        const input = h('input', {class: 'input mono-input', value: c.text, placeholder: (CLICK_TYPES.find(t => t[0] === c.type) || [0, 0, ''])[2],
          attrs: {spellcheck: 'false', 'aria-label': 'Command or message'}});
        const save = gesture => St.changeNpc(x => { x.click[i] = joinClick({type: sel.value, text: input.value}); }, gesture);
        sel.addEventListener('change', () => {
          input.placeholder = (CLICK_TYPES.find(t => t[0] === sel.value) || [0, 0, ''])[2];
          save(null);
        });
        input.addEventListener('input', () => save('click' + i));
        return h('div', {class: 'list-row'}, sel, input,
          button(null, 'up', {class: 'btn-xs btn-ghost', title: 'Move up', disabled: i === 0, onClick: () => { St.changeNpc(x => { [x.click[i - 1], x.click[i]] = [x.click[i], x.click[i - 1]]; }); rows(); }}),
          button(null, 'x', {class: 'btn-xs btn-ghost', title: 'Remove', onClick: () => { St.changeNpc(x => { x.click.splice(i, 1); }); rows(); }}));
      }));
      if (!d.click.length) box.append(h('div', {class: 'hint', text: 'Nothing happens on a click (it still plays its "when clicked" animation).'}));
    };
    rows();
    return section('click', 'Click actions', 'target', box,
      h('div', {class: 'row'}, button('Add action', 'plus', {class: 'btn-sm', onClick: () => { St.changeNpc(x => { x.click.push('player:'); }); rows(); }})),
      h('div', {class: 'hint', text: 'In order, when a player clicks it. {player} becomes their name.'}));
  }

  // ---- animations ----
  function animSection(n) {
    const ids = [...S.anims.keys()];
    const animOptions = (value, noneLabel) => [h('option', {value: '', text: noneLabel, selected: !value}),
      ...ids.map(id => h('option', {value: id, text: (S.anims.get(id).draft.name || id) + (S.anims.get(id).draft.name !== id ? ` (${id})` : ''), selected: value === id})),
      value && !S.anims.has(value) ? h('option', {value, text: value + ' (missing!)', selected: true}) : null];
    const pickRow = (label, field, noneLabel, hint) => {
      const sel = h('select', {class: 'select', on: {change: () => St.changeNpc(x => { x[field] = sel.value; })}}, animOptions(n[field], noneLabel));
      return h('div', {class: 'field'}, h('label', {text: label, title: hint}), sel);
    };
    const mode = () => {
      const d = St.npcEntry().draft;
      return !d.idle.length ? 'default' : d.idle.length === 1 && d.idle[0] === 'none' ? 'none' : 'choose';
    };
    const seg = h('div', {class: 'seg'});
    const picks = h('div', {class: 'anim-picks'});
    const drawIdle = () => {
      const m = mode();
      fill(seg, ...[['default', 'Default mix'], ['none', 'None'], ['choose', 'Choose']].map(([k, label]) => h('button', {
        type: 'button', class: m === k ? 'active' : '', text: label,
        on: {click: () => {
          St.changeNpc(x => {
            if (k === 'default') x.idle = [];
            else if (k === 'none') x.idle = ['none'];
            else if (mode() !== 'choose') x.idle = ['look_around', 'stretch'].filter(id => S.anims.has(id)).concat(S.anims.has('look_around') ? [] : ids.slice(0, 1));
          });
          drawIdle();
        }},
      })));
      picks.hidden = m !== 'choose';
      if (m === 'choose') {
        const d = St.npcEntry().draft;
        fill(picks, ...ids.map(id => {
          const c = checkbox(S.anims.get(id).draft.name || id, d.idle.includes(id), on2 => {
            St.changeNpc(x => {
              x.idle = on2 ? [...x.idle.filter(v => v !== id), id] : x.idle.filter(v => v !== id);
              if (!x.idle.length) x.idle = ['none'];
            });
            drawIdle();
          });
          return h('div', {class: 'anim-pick'}, c.el);
        }), ...d.idle.filter(id => id !== 'none' && !S.anims.has(id)).map(id => h('div', {class: 'anim-pick error-text', text: id + ' (missing!)'})));
      }
    };
    drawIdle();
    return section('anims', 'Animations', 'film',
      h('div', {class: 'field'}, h('label', {text: 'Now and then (idle)'}), seg, picks,
        h('div', {class: 'hint', text: 'The default mix: stretch, look around, wave, nod, head shake, shrug, scratch head, clap, foot tap, look up, check item, yawn, think, laugh, point.'})),
      pickRow('When a player walks up (greet)', 'greet', 'None'),
      pickRow('When clicked', 'clicked', 'None'),
      pickRow('Beckoning players over', 'beckon', 'Never beckons'));
  }

  // ---- looking ----
  function lookSection(n) {
    const t = toggle('Turns to look at players', n.look, v => St.changeNpc(x => { x.look = v; }));
    return section('look', 'Behaviour', 'eye', t.el);
  }

  // ---- position ----
  function positionSection(n) {
    const cells = h('div', {class: 'pos-grid'});
    const drawCells = () => {
      const d = St.npcEntry().draft;
      fill(cells, ...[['x', d.x], ['y', d.y], ['z', d.z]].map(([k, v]) => h('div', {class: 'pos-cell'}, h('div', {class: 'k', text: k}), h('div', {class: 'v', text: (Math.round(v * 100) / 100).toString()}))));
    };
    drawCells();
    const yawSlider = h('input', {class: 'slider', type: 'range', min: -180, max: 180, step: 1, value: n.yaw, attrs: {'aria-label': 'Facing'}});
    const yawNum = h('input', {class: 'input', type: 'number', step: 1, value: Math.round(n.yaw * 10) / 10, style: {width: '72px', textAlign: 'right'}});
    const setYaw = (v, gesture) => {
      let y = ((Number(v) + 180) % 360 + 360) % 360 - 180;
      if (!Number.isFinite(y)) return;
      y = Math.round(y * 10) / 10;
      St.changeNpc(x => { x.yaw = y; }, gesture);
      yawSlider.value = y;
      if (document.activeElement !== yawNum) yawNum.value = y;
      St.emit('view');
    };
    yawSlider.addEventListener('input', () => setYaw(yawSlider.value, 'yaw'));
    yawSlider.addEventListener('change', () => St.endGesture());
    yawNum.addEventListener('change', () => setYaw(yawNum.value, null));
    const compass = h('div', {class: 'compass'}, ...[['S', 0, 'south'], ['W', 90, 'west'], ['N', 180, 'north'], ['E', -90, 'east']].map(([k, v, word]) =>
      button(k, null, {class: 'btn-xs', title: 'Face ' + word, onClick: () => setYaw(v, null)})));
    return section('pos', 'Position', 'move',
      h('div', {class: 'row'}, h('span', {class: 'muted small', text: 'World'}), h('span', {class: 'mono small', text: n.world})),
      cells,
      h('div', {class: 'row'}, button('Move to me', 'target', {class: 'btn-sm', title: 'Puts it where you stand in game, facing your way', onClick: () => Act.moveNpcToMe(n.id)}),
        h('span', {class: 'hint', text: S.player.online ? '' : 'Join the server to use this.'})),
      h('div', {class: 'field'}, h('label', {text: 'Facing (yaw: 0 south, 90 west)'}), h('div', {class: 'field-row'}, yawSlider, yawNum)),
      compass);
  }

  function build() {
    const e = St.npcEntry();
    // A different NPC starts at the top of the panel.
    if ((e ? e.id : null) !== builtFor) root.scrollTop = 0;
    builtFor = e ? e.id : null;
    if (!e) {
      fill(root, h('div', {class: 'insp-empty'}, icon('user', 40), h('div', {text: S.npcs.size ? 'Pick an NPC on the left.' : 'No NPCs yet: press + on the left to make one where you stand in game.'})));
      return;
    }
    const n = e.draft;
    const person = n.entity === 'player';
    const head = h('div', {class: 'insp-head'},
      h('div', {class: 'insp-title'}, h('div', {style: {fontWeight: 650, fontSize: '15px'}, class: 'ellipsis', text: n.id}),
        h('span', {class: ['badge', person ? '' : 'badge-mob'], text: person ? 'person' : (n.entity.replace(/_/g, ' ') + (n.variant ? ' · ' + n.variant : ''))}),
        h('div', {class: 'spacer'}), h('span', {id: 'npc-dirty'})),
      e.remote ? h('div', {class: 'banner banner-warn'}, icon('warn', 15), h('div', {},
        h('div', {text: e.remote === 'deleted' ? 'This NPC was deleted in game. Saving brings it back.' : 'This NPC was changed in game since you started. Saving replaces that with yours.'}),
        h('div', {class: 'actions'}, button(e.remote === 'deleted' ? 'Drop mine' : 'Use theirs', null, {class: 'btn-xs', onClick: () => St.takeRemote('npc', e.id)})))) : null,
      person ? null : h('div', {class: 'banner banner-info'}, icon('info', 15), h('div', {text: 'A mob NPC looks the same for everyone and only looks at people and nods: no skin, held item or animations.'})));
    const saveBtn = button('Save', 'save', {class: 'btn-primary grow', title: 'Save (Ctrl+S)', onClick: () => Act.saveNpc(n.id)});
    saveBtn.id = 'npc-save';
    fill(root, head,
      plateSection(n),
      person ? skinSection(n) : null,
      itemsSection(n),
      clickSection(n),
      person ? animSection(n) : null,
      lookSection(n),
      positionSection(n),
      h('div', {class: 'insp-foot'}, saveBtn, button(null, 'trash', {class: 'btn-danger', title: 'Delete this NPC', onClick: () => Act.deleteNpc(n.id)})));
    updateDirty();
  }

  function updateDirty() {
    const e = St.npcEntry();
    const mark = root.querySelector('#npc-dirty');
    const save = root.querySelector('#npc-save');
    if (!e || !mark) return;
    fill(mark, e.dirty ? h('span', {class: 'badge badge-unsaved', text: 'unsaved'}) : h('span', {class: 'badge badge-ok', text: 'saved'}));
    if (save) save.disabled = !e.dirty;
  }

  on('select', () => {
    if (S.mode !== 'npcs') return;
    build();
  });
  on('npc', ev => {
    if (S.mode !== 'npcs') return;
    const e = St.npcEntry();
    if (!e || builtFor !== e.id || (ev && (ev.remote || ev.history || ev.external))) build();
    else updateDirty();
  });
  on('lists', () => {
    if (S.mode !== 'npcs') return;
    const e = St.npcEntry();
    if (!e || builtFor !== (e && e.id)) build();
    else updateDirty();
  });
  on('server', () => {
    // A skin added elsewhere: redraw, unless something here is being typed in.
    if (S.mode !== 'npcs' || addingSkin || S.skins.length === skinCount) return;
    skinCount = S.skins.length;
    if (!root.contains(document.activeElement)) build();
  });
  return {build};
}
