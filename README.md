# Lively NPCs

NPCs that feel alive: they turn to look at you, breathe, shift their weight, wave when you walk up,
beckon you over from across the square, talk when you click them, hold an item, and play any
animation you make in the built-in **web animation editor**. Everything is drawn with packets, so
nothing exists or ticks in your world.

Players whose game has loaded the NPC models (a resource pack Lively NPCs builds from each NPC's
skin) see a moving model; everyone else sees a normal player NPC in the same skin. Mob NPCs
(villagers, golems, ...) are supported too: they look at people and nod.

## Requirements

- Paper **26.3**
- [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14+ on the server

## Install

1. Put `LivelyNPCs-<version>.jar` and PacketEvents in `plugins/` and start the server.
2. Choose how players get the NPC models in `plugins/LivelyNPCs/config.yml` (`pack:`), then `/npc reload`.
3. `/npc create <id>` where you stand, `/npc skin <id> <player name>`, and you have an NPC.
4. `/npc editor` gives you a private link to the web editor.

## The NPC models (resource pack)

The models are a small resource pack Lively NPCs builds itself from the skins your NPCs wear.

- **`pack.mode: own`** (default): Lively NPCs sends its own pack to every player, beside any other
  server pack. `pack.host: builtin` serves it from Lively NPCs' web server (`web.port` must be open
  to players). `pack.host: url` uses a link you give it: upload
  `plugins/LivelyNPCs/export/lively-npcs-pack.zip` to GitHub or mc-packs.net and set `pack.url` and
  `pack.sha1` (`/npc pack` shows the SHA-1). Players joining through Minehut or MineKeep can only
  download packs from GitHub or mc-packs.net, so use `url` there.
- **`pack.mode: merged`**: your server already sends a pack (yours, or another plugin's). Lively NPCs
  writes its models to `plugins/LivelyNPCs/export/` (an `assets/` folder and
  `lively-npcs-assets.zip`): merge them into your pack, send it, then run `/npc pack merged`.
  `pack.merged-url-contains` limits which server pack counts.
  Without a server you can make the same files from the command line:
  `java -cp LivelyNPCs-<version>.jar dev.livelynpcs.pack.ExportCli --skins skins.yml --out <folder> [--zip]`

A new skin rebuilds the pack by itself (own mode re-sends it; merged mode rewrites `export/`). Until a
player has a pack with an NPC's skin, they see that NPC as a player NPC.

## Commands

All under `/npc` (aliases `/lnpc`, `/livelynpcs`), permission `livelynpcs.admin` (`/npc editor`
alone needs `livelynpcs.editor`). Tab completion everywhere.

| Command | What it does |
|---|---|
| `/npc create <id>` | A new NPC where you stand |
| `/npc remove <id>` | Removes it |
| `/npc list` | Every NPC; click one to go there |
| `/npc tp <id>` / `movehere <id>` | Go to it / move it to you |
| `/npc turn <id> <yaw\|me>` | Which way it faces |
| `/npc skin <id> <player name>` | Wear that player's skin |
| `/npc skin <id> url <link> [slim]` | A skin from an image link (needs `skins.mineskin-key`) |
| `/npc item <id> <item\|none>` | What it holds in its left hand |
| `/npc icon <id> <item\|none>` | An item turning above its name plate |
| `/npc plate <id> <line> \| <line> ...` | Its name plate (MiniMessage; `none` for none) |
| `/npc click <id> add <player\|console\|message> <text>` | What a click does (`{player}` = who clicked); `clear`, `list` |
| `/npc anim <id> idle <animations...\|default\|none>` | Its idle animations |
| `/npc anim <id> <greet\|clicked\|beckon> <animation\|none>` | What it plays when you walk up, click it, or to beckon |
| `/npc look <id> <true\|false>` | Whether it turns to look at players |
| `/npc entity <id> <player\|mob> [variant]` | A person, or a mob (`villager plains/farmer`) |
| `/npc play <id> <animation\|none> [player\|all]` | Play an animation now (`none` stops a looping one) |
| `/npc editor` | A private link to the web editor |
| `/npc pack` / `/npc pack merged` | The models' pack: what it holds and who has it / confirm a merge |
| `/npc reload` | Re-reads config.yml, npcs.yml, skins and animations |

## The web editor

`/npc editor` gives a private link (valid for `web.editor-minutes`). In your browser you can:

- pose a 3D model of the NPC in its own skin: drag rings on each joint, or use the sliders;
- make animations on a keyframe timeline, with easing, looping, whole-body moves (hop, step, spin,
  bend), undo, copy/paste and mirror pose;
- **preview live**: the NPC in game plays your animation for you while you edit it;
- change the NPCs themselves: name plate, skin (player name, link or uploaded file), held item,
  icon, click actions and which animations they play.

The built-in animations (greet, wave, talk, beckon, stretch, look_around, nod, head_shake, shrug,
scratch_head, clap, foot_tap, look_up, hop, bow, cheer, point, check_item, dance, wave_both, salute,
yawn, laugh, think, spin_hop) open in the editor like any other; an edited one can be put back with
"restore preset". The editor runs on Lively NPCs' own web server (`web:` in config.yml),
which needs a port open to your browser.

## When NPCs move

- **Idle:** every `npcs.idle-seconds` or so, one of its idle animations (everyone sees it at once).
- **Walking up** within `npcs.greet-radius`: sparkles and its greet animation (a wave with a hop).
- **Clicking:** its clicked animation (talking, with a hop), then its click actions.
- **Beckoning:** players between `beckon-from` and `beckon-to` blocks, facing an NPC, get waved over
  now and then (at most once every `beckon-every` seconds).
- **Commands:** `/npc play`, e.g. from Skript or another plugin.

## Files

- `config.yml`: every setting, documented.
- `npcs.yml`: the NPCs (also written by the commands and the editor).
- `skins.yml` and `skins/`: the skins and their images.
- `animations/<id>.yml`: your own animations, and edited built-in ones.
- `export/`: the NPC models (the pack and its parts).

Lively NPCs is free to use. It is not open source: please don't redistribute modified copies.
