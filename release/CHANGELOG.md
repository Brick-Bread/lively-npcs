# Changelog

## 1.0.0 ({{RELEASE_DATE}})

The first public release. Paper 26.3; needs PacketEvents 2.14 or newer.

### NPCs that feel alive

- NPCs turn their head, then their body, to look at players nearby.
- At rest they breathe, shift their weight, and now and then play an idle animation; everyone sees
  the same one at the same moment, and an NPC never plays the same one twice in a row.
- Walk up to an NPC and it greets you with a few sparkles and a wave with a little hop.
- Look towards an NPC from 6 to 16 blocks away and it may beckon you over.
- Click an NPC and it talks, then runs its click actions: a command as the player or as the console,
  or a message (`{player}` is whoever clicked).
- NPCs can hold an item in their left hand, and show an item icon turning above their name plate.
- Name plates of one or more lines, in MiniMessage.
- 14 built-in animations: greet, wave, talk, beckon, stretch, look around, nod, head shake, shrug,
  scratch head, clap, foot tap, look up and hop.
- Mob NPCs: villagers (with their biome and job), golems or any other mob. They look at people and
  nod.

### Two looks, one NPC

- Players whose game has loaded the NPC models see a moving model built from the NPC's own skin.
- Everyone else sees a player NPC in the same skin, which still looks at them, swings its arm now and
  then, and answers clicks. A player switches to the moving model as soon as their pack loads.

### The NPC models (resource pack)

- Lively NPCs builds the models itself from the skins your NPCs wear, and rebuilds them when a skin
  is added.
- `own` mode sends its pack beside any other server pack, from Lively NPCs' own web server or from a
  link you give it (GitHub, mc-packs.net).
- `merged` mode writes the models to `export/` for you to merge into the pack your server already
  sends, and watches whether players load it.
- The same files can be made from the command line, without a server.

### The web editor

- `/npc editor` gives a private link to an editor in your browser.
- Pose a 3D model of the NPC in its own skin with rings or sliders.
- Make animations on a keyframe timeline: easing, looping, whole-body moves (hop, step, spin, bend),
  undo and redo, copy and paste a pose, mirror a pose.
- Live preview: the NPC in game plays your draft for you while you edit; "Play for everyone" shows
  the saved animation to everyone near it.
- Change NPCs: name plate, skin (player name, image link or upload), held item, icon, click actions
  and the animations each one plays. Make, copy and delete NPCs.
- The built-in animations are editable presets that can be put back as they shipped.

### Commands and setup

- `/npc` with tab completion for every argument: create, remove, list, tp, movehere, turn, skin,
  item, icon, plate, click, anim, look, entity, play, editor, pack and reload.
- Skins from a player's name (Mojang), or from an image link or an uploaded file (through MineSkin,
  with your own free key).
- Every setting in `config.yml` has a default; a bad value is reported with its path and the default
  is used. `/npc reload` applies everything except the web server's port and address.
- Permissions `livelynpcs.admin` (every command) and `livelynpcs.editor` (the editor only).
