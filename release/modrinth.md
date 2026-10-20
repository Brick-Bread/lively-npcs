<!--
Modrinth page for Lively NPCs. Read SITE-RULES.md first: Modrinth's AI rule blocks this project today.
Form fields:
  Name:     Lively NPCs
  Summary:  NPCs that turn to look at you, wave when you walk up, beckon you over and talk when clicked, with a web animation editor.
  Licence:  GPL-3.0-only, source link: https://github.com/Brick-Bread/lively-npcs
  Loader:   Paper    Game version: 26.3    Categories: Decoration, Utility
  Version dependency: PacketEvents (required)
  Gallery:  only real screenshots (screenshots/ and the in-game shots); no drawn art (section 6.2).
Everything below this comment is the description. Fill every {{...}} first.
-->

NPCs that feel alive. They turn to look at you, breathe and shift their weight, wave when you walk
up, beckon you over from across the square, talk when you click them, hold an item, and play any
animation you make in the built-in **web animation editor**.

Every NPC is drawn with packets, for each player: nothing is added to your world, and your world
never ticks or saves them.

> **Paper 26.3 only.** Needs [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14 or newer.

## Features

- **Looks at you:** turns its head, then its body, towards players nearby.
- **Alive at rest:** breathes, shifts its weight, and now and then plays an idle animation (everyone
  sees the same one at the same moment, and never the same one twice in a row).
- **Greets you:** walk up and it gives a few sparkles and a wave with a little hop.
- **Beckons you over:** look towards it from 6 to 16 blocks away and it may wave you closer.
- **Talks when clicked:** a talking animation, then its click actions: run a command as the player
  or as the console, or send a message. `{player}` is whoever clicked.
- **Holds an item** in its left hand, exactly where a player holds one.
- **An icon** (any item) turning above its name plate.
- **Name plates** of one or more lines, with MiniMessage colours and gradients.
- **14 built-in animations:** greet, wave, talk, beckon, stretch, look around, nod, head shake,
  shrug, scratch head, clap, foot tap, look up and hop. Every one opens in the editor, and an edited
  one can be put back as it shipped.
- **Your own animations,** made in the web editor and played on a trigger or by command.
- **Mob NPCs:** villagers (with their biome and job), golems or any other mob. They look at people
  and nod.
- **Skins** from a player's name, an image link or an uploaded file.
- **Commands for everything,** with tab completion, plus `/npc play` for Skript or other plugins.

## How it works

Each player sees whichever look their game can show:

- **A moving model,** for players whose game has loaded the NPC models. The model is built from the
  NPC's own skin, part by part (head, body, arms, legs), so it can wave, bend, hop and turn smoothly.
  The models are a small resource pack that Lively NPCs builds itself from the skins your NPCs wear.
- **A player NPC in the same skin,** for everyone else: players who said no to the pack, or who
  haven't got it yet. It still turns its head to look at them, swings its arm now and then, and
  answers clicks.

A player switches from one look to the other on the spot when their pack loads. A new skin rebuilds
the models by itself: with the built-in host they are sent again at once; with a link or a merged
pack, `/npc pack` tells you what to upload or merge again.

## The web editor

`/npc editor` gives you a private link (valid for 2 hours by default). In your browser you can:

- **Pose a 3D model** of the NPC in its own skin: drag the rings on each joint, or use the sliders.
- **Make animations** on a keyframe timeline: easing, looping, whole-body moves (hop, step, spin,
  bend), undo and redo, copy and paste a pose, mirror a pose left to right.
- **Preview live:** while you edit, the real NPC in game plays your draft for you, so you can watch
  it in the world. "Play for everyone" shows the saved animation to everyone near the NPC.
- **Change the NPCs:** name plate, skin (player name, link or upload), held item, icon, click
  actions, and which animations it plays when idle, greeting, clicked and beckoning. Make new NPCs
  where you stand, copy one, or delete one.

Changes made with commands in game show up in the editor, and the other way round.

## Getting the models to your players

Choose how in `config.yml` under `pack:`.

- **`mode: own`** (default): Lively NPCs sends its own pack, beside any pack your server already
  sends.
  - **`host: builtin`:** served from Lively NPCs' own web server. Its port must be open to players.
  - **`host: url`:** you upload `plugins/LivelyNPCs/export/lively-npcs-pack.zip` to GitHub or
    mc-packs.net and set `pack.url` and `pack.sha1`. `/npc pack` shows the SHA-1 and tells you when
    to upload again.
- **`mode: merged`:** your server already sends a pack (your own, or another plugin's). Lively NPCs
  writes its models to `plugins/LivelyNPCs/export/`: merge them into your pack, send it, then run
  `/npc pack merged`. You can also make these files from the command line, without a server.

Players who say no to the pack still see every NPC, as player NPCs. Set `pack.required: true` only
if you want players who say no to be disconnected.

## Requirements

- Paper **26.3**, on Java 25 or newer
- [PacketEvents](https://modrinth.com/plugin/packetevents) **2.14** or newer
- For the web editor (and the built-in pack host): a spare port that browsers can reach
- For skins from images or uploads: a free [MineSkin key](https://account.mineskin.org/keys)

## Quick start

1. Put `LivelyNPCs-1.0.0.jar` and PacketEvents in `plugins/`, then start the server.
2. Choose how players get the models in `plugins/LivelyNPCs/config.yml` (`pack:`), then `/npc reload`.
3. Stand where the NPC should be: `/npc create guide`.
4. Give it a skin: `/npc skin guide Notch`.
5. Give it a name plate: `/npc plate guide <gold><bold>Guide | <gray>Click me!`
6. `/npc editor` for the web editor.

## Commands

All under `/npc` (also `/lnpc` and `/livelynpcs`), with tab completion.

| Command | What it does |
|---|---|
| `/npc create <id>` | A new NPC where you stand |
| `/npc remove <id>` | Removes it |
| `/npc list` | Every NPC; click one to go there |
| `/npc tp <id>` / `/npc movehere <id>` | Go to it / move it to you |
| `/npc turn <id> <yaw\|me>` | Which way it faces |
| `/npc skin <id> <player name>` | Wear that player's skin |
| `/npc skin <id> url <link> [slim]` | A skin from an image link (needs a MineSkin key) |
| `/npc item <id> <item\|none>` | What it holds in its left hand |
| `/npc icon <id> <item\|none>` | An item turning above its name plate |
| `/npc plate <id> <line> \| <line> ...` | Its name plate (MiniMessage; `none` for none) |
| `/npc click <id> add <player\|console\|message> <text>` | What a click does; also `clear` and `list` |
| `/npc anim <id> idle <animations...\|default\|none>` | Its idle animations |
| `/npc anim <id> <greet\|clicked\|beckon> <animation\|none>` | What it plays when you walk up, click it, or to beckon |
| `/npc look <id> <true\|false>` | Whether it turns to look at players |
| `/npc entity <id> <player\|mob> [variant]` | A person, or a mob (`villager plains/farmer`) |
| `/npc play <id> <animation\|none> [player\|all]` | Play an animation now (`none` stops a looping one) |
| `/npc editor` | A private link to the web editor |
| `/npc pack` / `/npc pack merged` | The models' pack: what it holds and who has it / confirm a merge |
| `/npc reload` | Re-reads config.yml, npcs.yml, skins and animations |

## Permissions

| Permission | Default | What it allows |
|---|---|---|
| `livelynpcs.admin` | op | Every `/npc` command (includes `livelynpcs.editor`) |
| `livelynpcs.editor` | op | `/npc editor` only |

## Config highlights

Every setting is explained in `config.yml`. A bad value is reported in the console with its path,
and the default is used instead. `/npc reload` applies everything except `web.port` and `web.bind`,
which need a restart.

| Setting | Default | What it does |
|---|---|---|
| `npcs.look-radius` | `8` | Blocks: NPCs look at players this close |
| `npcs.animate-radius` | `24` | Blocks: further away an NPC stands still for that player and costs no packets |
| `npcs.greet-radius` | `5` | Blocks: walking this close makes an NPC greet you |
| `npcs.beckon-from` / `beckon-to` | `6` / `16` | Blocks: who may be beckoned over (`0` turns beckoning off) |
| `npcs.beckon-every` | `8` | Seconds: at most one beckon per player this often |
| `npcs.idle-seconds` | `12` | Seconds: about how often each NPC plays an idle animation (`0` = never) |
| `npcs.key-ticks` | `2` | Ticks between movement packets: `2` is smooth, `4` sends half as many |
| `npcs.sparkles` | `true` | Sparkles when an NPC greets someone |
| `pack.mode` / `pack.host` | `own` / `builtin` | How players get the models (see above) |
| `pack.required` | `false` | `true` disconnects players who say no to the pack |
| `web.enabled` / `web.port` | `true` / `8193` | Lively NPCs' web server: the editor, and the built-in pack host |
| `web.public-address` | empty | How browsers reach your server, for example `play.example.com:8193` |
| `web.editor-minutes` | `120` | How long an editor link works |
| `skins.mineskin-key` | empty | Your MineSkin key, for skins from images |

## What goes over the network

- **Mojang** (`api.mojang.com`, `sessionserver.mojang.com`): only when you give an NPC the skin of a
  player name.
- **textures.minecraft.net:** to download the skin images the models are built from.
- **MineSkin** (`api.mineskin.org`): only when you give an NPC a skin from an image link (the link is
  sent) or an uploaded file (the image is sent), with your own key.
- **Lively NPCs' own web server** on your server (port 8193 by default): the editor, and the pack in
  `host: builtin` mode. Turn it off with `web.enabled: false`.
- No statistics are collected and there is no update check.

## FAQ

**My players join through Minehut or MineKeep. Will they get the pack?**
Players coming through Minehut or MineKeep can only download packs from GitHub or mc-packs.net. Use
`pack.host: url` with the pack uploaded to one of those, or `pack.mode: merged` if your server's pack
already comes from there.

**Do I need to open a port?**
Only for the web editor and the built-in pack host. Ask your host for a spare port, set `web.port`
to it and `web.public-address` to the address players use. Without a port you can still do
everything with commands: set `web.enabled: false` and use `pack.host: url` or `pack.mode: merged`.
Editor links are private and expire; don't share them.

**Do I need a MineSkin key?**
Only for skins from an image link or an uploaded file. Skins from a player's name come from Mojang
and need no key. Keys are free at [account.mineskin.org/keys](https://account.mineskin.org/keys):
put yours in `skins.mineskin-key`, then `/npc reload`.

**Will it lag my server?**
NPCs are packets only: there are no entities in your world, so there is nothing for the server to
tick, save or collide with. Lively NPCs does its own work in one short step every couple of ticks,
and only for players near an NPC: beyond `animate-radius` an NPC costs that player no packets at
all. While a model moves it sends a small update every `key-ticks` ticks (raise it to send fewer);
at rest, only a few per breath.

**What do players without the pack see?**
A normal player NPC in the same skin. It looks at them, swings its arm now and then, and answers
clicks. They see the moving model as soon as their pack loads.

**I already send a resource pack. Will Lively NPCs replace it?**
No. In `own` mode its pack goes beside yours. In `merged` mode it sends nothing: you put its models
into your pack.

**Can I start animations from Skript or another plugin?**
Yes: `/npc play <id> <animation> [player|all]` from the console or any plugin that runs commands.

**Does it run on Spigot, Folia or other versions?**
Not for now: Paper 26.3 only.

## Made with AI

Lively NPCs is designed, tested and looked after by brick (Brick_Bread). Its code is written with
Claude, an AI model.

## Terms

Lively NPCs is free and open source under the GNU GPL 3.0: use it on as many servers as you like,
change it and share it, as long as what you share stays GPL with its source. Source: https://github.com/Brick-Bread/lively-npcs

## Support

- Discord: {{DISCORD_INVITE}}
- Bugs and ideas: {{SUPPORT_URL}}
- E-mail: {{CONTACT_EMAIL}}
