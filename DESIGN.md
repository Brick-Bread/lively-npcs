# Lively NPCs — design

The source of truth for everyone building Lively NPCs. If code and this file disagree, fix one of
them in the same change. Owner: brick (Brick_Bread), who does not code: describe outcomes, not
implementation, when you report to him.

## What it is (owner's decisions, 2026-10-20)

- A standalone Paper **26.3** plugin, **LivelyNPCs** (jar `LivelyNPCs-<version>.jar`), usable on any
  server. **Free and open source under the GPL-3.0** (brick, 2026-10-20: EntityLib, which is
  shaded in, is GPL-3.0, so a closed release was not allowed); published on **Hangar only** for now
  (Modrinth and BuiltByBit ban mainly AI-made projects, SpigotMC wants plain Spigot support). Repo
  Brick-Bread/lively-npcs. Needs **PacketEvents** on the server; EntityLib is shaded and relocated to
  `dev.livelynpcs.libs.entitylib`.
- NPCs are **packets only**: nothing exists or ticks in the world. Players whose game has loaded the
  NPC models (a resource pack) see each person-NPC as a **moving model** (a rig of item displays);
  everyone else sees a **player model** (a fake player wearing the skin). Mob NPCs (villagers etc.)
  look the same for everyone and only look at people and nod.
- A model NPC: looks at you (head, then body), breathes, shifts its weight, plays idle animations
  now and then, **greets** you when you walk up (sparkles + its greet animation, default: a wave with
  a little hop), **talks** when clicked (default: talk with a hop), **beckons** you over when you are
  6-16 blocks away facing it, holds an item in its **left hand**, and plays any animation **on a
  command** (`/npc play`). Its arms are attached at the shoulder like a player's.
- Pack: `pack.mode: own` (Lively NPCs builds and sends its own pack, hosted by its **built-in web
  server** or a **link** you give it) or `merged` (it writes the models to `export/` for the server's
  own pack and watches whether players load that). RaiseAMob uses `merged`.
- NPCs are made and changed with **commands and npcs.yml**, and in the **web editor**.
- The **web editor** is served by the plugin (same web server, `web.port`): `/npc editor` gives the
  player a private link. It edits **NPCs and animations**: a **3D model** of the NPC with its skin to
  pose, a **keyframe timeline** (easing, loop or once), **whole-body moves** (hop, spin, step, bend),
  **live preview in game** while editing, NPC settings (plate, skin by player name / image link /
  upload, held item, icon, click actions, which animations it plays). The built-in animations are
  **editable presets**.
- Skins: by **player name** (Mojang), **image link** or **upload** (both signed through MineSkin with
  `skins.mineskin-key`).

## Layout and owners

Packages under `dev.livelynpcs`. Three builders work in parallel, each in its own git worktree and
branch, each owning its files. **Shared files** (owned by the lead; do not edit them, ask instead):
`LivelyNpcs.java`, `Settings.java`, `config.yml`, `plugin.yml`, `build.gradle.kts`, `DESIGN.md`, and
the contracts: `anim/Animation`, `anim/Keyframe`, `anim/Channel`, `anim/Easing`,
`anim/AnimationService`, `skin/Skin`, `skin/SkinService`, `pack/PackService`, `npc/NpcDef`,
`npc/NpcService`, `rig/Part`, `web/WebServer`. If a contract truly needs a change, make the smallest
**additive** change you can and say so first thing in your report.

| Builder | Owns |
|---|---|
| **runtime** | `rig/*` (except Part), `npc/*` (NpcManager and its helpers; not NpcDef/NpcService), `anim/AnimationStore` + presets (`src/main/resources/animations/*.yml`), `command/*`, their tests, `src/test/resources/pose-fixtures.json` |
| **pack** | `skin/*` (SkinStore, Mojang and MineSkin clients, image cache; not Skin/SkinService), `pack/*` (PackManager, ModelBuilder, PackBuilder, tracking; not PackService), their tests |
| **editor** | `web/EditorApi` (+ helpers in `web/`, not WebServer), `src/main/resources/web/**` (the page, its scripts, vendored three.js), `tools/check-editor-pose.mjs`, their tests |

Stubs for every owned class exist so each worktree builds on its own; replace your stubs.

Build: `./gradlew build` (Java 25; tests run). Never connect a bot or test client to any server.
No RaiseAMob code or dependency may appear here.

## Coordinates and the rig maths

Everything below is in **rig pixels**: origin at the NPC's feet, +y up, and in the NPC's own frame
(before its yaw) it faces **+z**, its **right is -x**. A pixel is `scale/16` blocks (`npcs.scale`,
default 0.9375, a player's size). Minecraft yaw: 0 faces south (+z), 90 faces west (-x).

Rotations: `about(axis, deg)` is a right-handed rotation; quaternions are `(x, y, z, w)` as JOML;
`a·b` turns by b first, then a. UP = (0,1,0), ACROSS = (1,0,0), FRONT = (0,0,1). Signs, as people
see them: positive head/torso yaw turns **to its right**; positive pitch looks/bends **down/forward**;
positive roll tilts the top **to its right**; positive arm "forward" raises the hand in front (90 =
pointing ahead, 180 = straight up); positive arm "out" swings it out to its own side while the arm
is below the shoulder (above the shoulder, positive "out" brings the hand in over the head);
positive "across" swings it in towards the chest; positive leg forward/out likewise.

Inputs for one moment: the animation's channel values (`Channel`, 0 = rest), and the live layers:
NPC yaw `Y`, gaze (`gb` body turn, `gy` head yaw, `gp` head pitch), breath `b` in 0..1, weight
shift `λ` (pixels, hips towards its left), and the slim flag of the skin.

1. Body yaw `ψ = Y + gb + ROOT_YAW`; `Rb = about(UP, -ψ)`.
2. Root offset `o = Rb · (-ROOT_X, ROOT_Y, ROOT_Z)` (to its right, up, forward).
3. In the body frame:
   - lean: `τ = asin(clamp(-λ/12, -1, 1))`, hip drop `d = -12·(1 - cos τ)`; hips `H = (λ, 12 + d, 0)`.
   - breath: rise `ρ = 0.3·b`; arms out `+2.5·b` degrees; chin `-1.5·b` degrees of pitch.
   - `Rt = about(UP, -TORSO_YAW) · about(ACROSS, TORSO_PITCH) · about(FRONT, TORSO_ROLL)`.
   - neck `N = H + Rt·(0, 12 + ρ, 0)`.
   - **body**: origin `N`, rotation `Rt`, height stretch `k = 1 + ρ/12` (scale y only).
   - **head**: origin `N`, rotation `Rt · about(UP, -hy) · about(ACROSS, hp) · about(FRONT, HEAD_ROLL)`
     with `hy = clamp(gy + wrap(HEAD_YAW), -80, 80)` (wrap: into -180..180, so whole turns are no turn;
     nothing changes within ±180), `hp = clamp(gp + HEAD_PITCH - 1.5·b, -60, 60)`.
   - **arms** (side `s` = -1 right, +1 left; `e` = 0.5 slim, 0 classic): shoulder pivot
     `P = H + Rt·(5s, 10 + ρ + SHOULDERS - e, 0)` (a player's: 22 px up, 21.5 slim);
     `Ra = about(FRONT, s·(OUT + 2.5·b)) · about(UP, -s·ACROSS) · about(ACROSS, -FORWARD)`;
     origin `P + Rt·Ra·(s·(1 - e), 2, 0)` (the arm's top centre, 2 px above the pivot);
     rotation `Rt·Ra`.
   - **legs** (side s): origin `(2s + λ, 12 + d, 0)`;
     rotation `about(FRONT, τ + s·LEG_OUT) · about(ACROSS, -LEG_FORWARD)`.
   - **held item** (left hand, s = +1): grip pivot `G = H + Rt·(5 - e, 10 + ρ + SHOULDERS - e, 0)`;
     origin `G + Rt·Ra_left·(1, -10, 2)`; rotation `Rt · Ra_left · about(ACROSS, 90)`. This is exactly
     where the game draws an off-hand item on a player (ItemInHandLayer: translateToHand, -90 x,
     180 y, translate (-1/16, 2/16, -10/16), THIRD_PERSON_LEFT_HAND), so items look held.
4. Each part's display, from body-frame origin `p` and rotation `R`: translation (blocks)
   `(o + Rb·p) · scale/16`; left rotation `Rb·R`; scale `(scale, scale·k for the body else scale,
   scale)`; right rotation `(0, 1, 0, 0)` for the six body parts (the 26.3 client turns every item
   display's model half round about y before drawing: without this every NPC shows its back) and
   identity for the held item. Item display type NONE for body parts, THIRD_PERSON_LEFT_HAND for the
   held item. Every display spawns at the NPC's feet, yaw 0 pitch 0, billboard FIXED.

The editor's 3D view must give the same picture from the same numbers: draw each part's boxes (below)
at its origin and rotation. `src/test/resources/pose-fixtures.json` (runtime builder) holds poses and
the resulting origins and rotations; `tools/check-editor-pose.mjs` (editor builder) checks the
editor's maths against it with node.

Part boxes in each part's own frame (pixels from its origin; the pack's model point (8,8,8) is the
origin): head x -4..4, y 0..8, z -4..4 (hat layer 0.5 bigger each side); body x -4..4, y -12..0,
z -2..2 (jacket +0.25); arms x -2..2 (slim -1.5..1.5), y -12..0, z -2..2 (sleeves +0.25); legs
x -2..2, y -12..0, z -2..2 (trousers +0.25). Faces take the standard 64x64 skin layout exactly as the
player model does (see the pack builder's ModelBuilder and its pixel check).

## Animations

`anim/Animation` is the format (YAML file = editor JSON; see its javadoc): keyframes with any
`Channel` values (missing = 0), easing into each keyframe, length, loop, glance. Sampling is
`Animation.sample`. Presets ship in `src/main/resources/animations/<id>.yml`; the server's own and
edited presets live in `plugins/LivelyNPCs/animations/<id>.yml` (same id = replaces the preset).

Presets (ids are what npcs.yml and commands use). Values were tuned so arms never pass through the
head or body more than ~0.6 px (0.6 px into the head; into the torso 0.6 px, or 1.2 px within 4 px of
the shoulder pivot; `ArmClippingTest` checks every preset's every tick, classic and slim, and that
salute's and think's hand really comes up to the face). The values below were the
starting point: the files in `src/main/resources/animations/` are the truth (beckon's raised frames
got right-arm-out -3, shrug's arms 9 not 11, scratch_head was redone to rub beside the head): `greet` (wave with a hop at the start), `wave`, `talk` (with a hop at the start), `beckon`,
`stretch`, `look_around` (glance), `nod`, `head_shake` (glance), `shrug`, `scratch_head`, `clap`,
`foot_tap`, `look_up` (glance), `hop`.

More presets (2026-10-20; no starting values here, the files are the truth): `bow` (a polite
greeting: bends 30° from the hips, head dipped, hands at its sides), `cheer` (fists up in a V,
pumping, two hops), `point` (right arm straight out ahead, held, a small nod that way), `check_item`
(glance; looks down at the item in its left hand, like a phone, and turns it in its hand), `dance`
(loops seamlessly: hops on the beat, arms swinging, body rocking and twisting, head bobbing; one
round is 2 s), `wave_both` (both hands waving above the head, mirrored), `salute` (right hand to the
brow, held, then down), `yawn` (sleepy stretch, chin up, then nods off and jerks awake), `laugh`
(head back, shoulders bobbing, a hand on its belly), `think` (right hand up under the chin, head
tilted, slow nods), `spin_hop` (a hop with a full spin to its right; ends at root-yaw 360, a whole
turn, which counts as rest).

The default idle mix (`AnimationStore.DEFAULT_IDLE`, used when npcs.yml says `idle: []`) is the calm
ones: stretch, look_around, wave, nod, head_shake, shrug, scratch_head, clap, foot_tap, look_up,
check_item, yawn, think, laugh, point (an NPC never plays the same one twice in a row). Bow, cheer,
dance, salute, wave_both and spin_hop are for greet, clicked or `/npc play`.

Starting values of the first fourteen (ticks; channel short names: raf = right-arm-forward, rao = right-arm-out,
raa = right-arm-across, laf/lao/laa the left arm, hy/hp/hr head yaw/pitch/roll, ry = root-y):

- greet (48): 0 rest; 4 ease-out ry 3.5, raf 90; 8 ry 0, raf 160, rao -8; then every 4 ticks
  raf/rao alternating 166/-26 and 160/-6 until 32; 36 raf 150 rao -10; 40 raf 70 rao -4; 44 rest
  (ease-in-out). wave: the same without ry.
- talk (40): 0 rest; 4 ry 3 raf 25 rao 4 raa 4 hp 7; 8 ry 0 raf 40 rao 2 raa 6 hp 1; 12 raf 22 rao 6
  raa 2 hp 9; 16 raf 38 rao 2 raa 6 hp 2; 20 raf 24 rao 7 raa 2 hp 8; 24 raf 40 rao 3 raa 6 hp 1;
  28 raf 26 rao 4 hp 7; 32 raf 34 rao 3 hp 2; 36 raf 14 rao 1; 40 rest.
- beckon (36): 0 rest; 4 raf 80 raa 4; 8 raf 145 raa 4 hp -4 hr 4; 12 raf 85 raa 4 hr 4; 16 as 8;
  20 as 12; 24 as 8; 28 raf 90 raa 3; 32 raf 40; 36 rest (ease-in-out).
- stretch (40): 0 rest; 4 raf/laf 60; 8 raf/laf 130 rao/lao -6 hp -8; 12 raf/laf 170 rao/lao -10
  hp -14 shoulders 0.8; 16 and 20 raf/laf 176 rao/lao -12 hp -16 shoulders 1; 24 raf/laf 168
  rao/lao -10 hp -14 shoulders 0.6; 28 raf/laf 120 rao/lao -6 hp -8; 32 raf/laf 50 hp -3;
  36 raf/laf 12; 40 rest.
- look_around (60, glance): 12 and 24 hy -45 hp 5; 40 and 52 hy 45; 60 rest.
- nod (20): 4 hp 12; 8 hp 2; 12 hp 12; 16 hp 2; 20 rest.
- head_shake (24, glance): 4 hy -18; 8 hy 18; 12 hy -18; 16 hy 18; 20 hy -6; 24 rest.
- shrug (24): 4, 8 and 12 rao/lao 11 raf/laf 12 shoulders 1 hr 5 hp -4; 18 rao/lao 5 raf/laf 6
  shoulders 0.4 hr 2; 24 rest.
- scratch_head (40): 4 raf 120 rao 4 raa 8 hp 4 hr 3; 8 raf 198 rao 8 raa 10 hp 8 hr 5; 12, 20 raf
  204 rao 10 raa 10 hp 8 hr 5; 16, 24 raf 196 rao 7 raa 10 hp 8 hr 5; 28 raf 200 rao 8 raa 10 hp 8
  hr 5; 32 raf 120 rao 4 raa 6 hp 3; 36 raf 30; 40 rest. (The hand behind the head, so any overlap
  is hidden; tune against the clipping test.)
- clap (36): 4 raf/laf 60 raa/laa 12 hp 6; 8, 16, 24 raf/laf 65 raa/laa 25 hp 6; 12, 20 raf/laf 62
  raa/laa 12 hp 6; 28 raf/laf 50 raa/laa 10 hp 3; 32 raf/laf 20 raa/laa 3; 36 rest.
- foot_tap (32): 4, 12, 20 right-leg-forward 14 hp 3; 8, 16, 24 hp 3; 32 rest.
- look_up (44, glance): 8 hp -30 hy 8; 16 hp -34 hy -6; 28 hp -34 hy 4; 36 hp -10; 44 rest.
- hop (10): 4 ease-out ry 4; 10 ease-in rest.

Playing: one animation at a time per NPC per viewer; a viewer's own (greet, clicked, beckon,
command for them, preview) wins over the NPC's idle one, which everyone sees at the same moment. A
glance animation is skipped for viewers the NPC is looking at. Starting, switching or stopping one
blends over 10 ticks. Whole turns: ROOT_YAW, TORSO_YAW and HEAD_YAW values a whole turn apart (360,
720...) are the same pose, so they blend the short way round, and a one-shot that ends a whole turn
round (spin_hop: root-yaw 360) ends at rest and never spins back (RigPose.wholeTurns/atRest). Keyframe packets go every
`npcs.key-ticks` ticks for moving parts (each glides one tick longer than the gap), at rest only
every quarter breath, and nothing beyond `animate-radius`. While beckoning, the NPC looks at that
player whatever look-radius says.

## npcs.yml

```yaml
npcs:
  mobshop:                       # the id
    entity: player               # or a mob type: villager, copper_golem, ...
    variant: ''                  # villagers: type/profession, e.g. plains/farmer
    world: world
    x: 6.5
    y: 0
    z: -2.5
    yaw: 180
    skin: 3f2a9c0d11e4b7a2       # a skins.yml key ('' = default skin, player model only)
    plate: ['<gold><bold>Mob Shop', '<gray>Buy mobs', '<yellow>Click']
    icon: cow_spawn_egg          # floats over the plate, turning ('' none)
    held: wheat                  # in its left hand ('' none)
    click: ['player:shop mobs']  # player:<cmd>, console:<cmd>, message:<MiniMessage>; {player} = clicker
    idle: []                     # [] = the default idle mix; [none] = no idle animations
    greet: greet                 # '' = none
    clicked: talk
    beckon: beckon               # '' = never beckons
    look: true
```

skins.yml: `skins: { <key>: { texture, value, signature, slim, source } }`.

## Pack

Model ids: item model `livelynpcs:<skin key>/<part>` (`assets/livelynpcs/items/<key>/<part>.json`,
type minecraft:model) → model `livelynpcs:npc/<key>/<part>` (`assets/livelynpcs/models/npc/<key>/
<part>.json`) → texture `livelynpcs:livelynpcs/<key>` (`assets/livelynpcs/textures/livelynpcs/
<key>.png`, 64x64 RGBA, base layers made opaque like the game does, a 64x32 skin converted the game's
way). Atlas entries: `assets/minecraft/atlases/items.json` and `blocks.json`, each
`{"sources":[{"type":"directory","source":"livelynpcs","prefix":"livelynpcs/"}]}` (26.2+ read the
items atlas, 1.21.4-1.21.10 the blocks one). `pack.mcmeta`: pack_format 46, supported_formats
[46, 97], min_format 46, max_format [97, 1], description "Lively NPCs". The zip is deterministic
(sorted entries, fixed timestamps), so the same skins give the same SHA-1.

The geometry and UV mapping are a port of RaiseAMob's resourcepack/npc_models.py (in
C:\Users\Brick\Documents\GrowAMob-season1\resourcepack\), which matches the 26.3 player model face for
face and checks itself by redrawing every base face; port that check too.

Modes (PackService): **own/builtin**: served at `/pack/<sha1>.zip` by the web server, sent on join
(`replace(false)`, the settings' prompt and required), re-sent after a rebuild (the old pack id
removed first). **own/url**: the same, from `pack.url` + `pack.sha1`; `/npc pack` tells the admin
to upload `export/lively-npcs-pack.zip` and set both after skins change. **merged**: writes
`export/assets/...` and `export/lively-npcs-assets.zip` (no pack.mcmeta), counts a player as having
the models once a server pack (any, or one whose link contains `merged-url-contains`) has
SUCCESSFULLY_LOADED; skins added after the last `/npc pack merged` are not drawn as models until
the admin runs it again. Who has what survives a hot swap (`pack-state.yml`, written on disable and
read on enable, like RaiseAMob Core does).

## Web

`web/WebServer` (built): `/editor/` serves `src/main/resources/web/` (index.html at `/editor/`),
`/pack/` (pack builder), `/api/` (EditorApi). `/npc editor` makes a Session and gives the link
`<baseUrl>/editor/#<token>`; the page keeps the token from the fragment and sends
`Authorization: Bearer <token>`; no or expired token is 401. Handlers run on web threads: hop to the
main thread (`Bukkit.getScheduler().callSyncMethod` or a CompletableFuture) before touching NPCs,
animations or players. The page's CSP is `default-src 'self'`: everything it loads (three.js,
OrbitControls, TransformControls, fonts) is vendored under `web/vendor/` with its licence.

API (all JSON; errors are `{"error": "<for people>"}` with 4xx/5xx):

- `GET /api/state` → `{player: {name, uuid, online, world}, npcs: [NpcDef], animations: [{id, builtin,
  edited, ...Animation.toMap()}], skins: [{key, slim, source}], items: [item ids], channels: [{key,
  pixels, min, max}], easings: [keys], settings: {scale, keyTicks}}`
- `PUT /api/npcs/{id}` (NpcDef JSON; id must match) → the stored NpcDef; `POST /api/npcs` `{id,
  copyFrom?}` creates one where the editor's player stands (or copying another's spot); `DELETE
  /api/npcs/{id}`; `POST /api/npcs/{id}/move-to-me`.
- `PUT /api/animations/{id}` (Animation map) → stored; `DELETE /api/animations/{id}` (a preset goes
  back to how it ships).
- `POST /api/preview` `{npc, animation: {id, ...map}}` plays the draft on that NPC for the editor's
  player (looping if loop); `POST /api/preview/stop`. `POST /api/play` `{npc, animation, everyone}`.
- `POST /api/skins` `{type: player|url|upload, value, slim}` (upload: base64 PNG) → `{key, slim,
  source}` (waits for Mojang/MineSkin, up to 60 s).
- `GET /api/skins/{key}.png` → the skin image (for the 3D view).

## RaiseAMob (the lead does this, not the builders)

Testing runs `pack.mode: merged`: the Season 1 pack (Core sends it) gets `export/` merged in by
RaiseAMob's resourcepack/build.py. The hub NPCs move from RaiseAMob-Village to npcs.yml here;
Village keeps signs, crates, bunting and portals.

## 26.3 facts that cost us before

- Item display models are turned half round about y by the client (right rotation fix above).
- Display entity-data slots (26.3): interpolation delay 8, transformation glide 9, teleport glide 10,
  translation 11, scale 12, left rotation 13, right rotation 14, billboard 15, brightness 16, view
  range 17, shadow radius 18, shadow strength 19; item display item 23, display type 24. EntityLib
  3.3.7's display setters write these right. Per-viewer keyframes are one entity-data packet with
  slots 8, 9, 11, 13 (+12 for the body); the delay (8) must go in every time or the client does not
  restart its glide.
- A fake player: skin layers are slot 16, a byte (0x7F = all); set it directly. Never touch slot 15
  (main hand): EntityLib writes the wrong type there and the client disconnects. Villager data is
  slot 19 (EntityLib writes it one too low). A wrong slot or type disconnects the client.
- Arm swings use 26.3's own swing packet (`WrapperPlayServerSwingAnimation`, main hand, WHACK, 6);
  the old animate packet no longer swings arms.
- Particles new in 26.3 kick older clients joining through ViaVersion: use old ones (HAPPY_VILLAGER).
- EntityLib keeps a player who quit as a viewer and skips them when they come back: on quit,
  `removeViewerSilently`. `setNotifyAboutChanges(false)` … set … `(true)` already sends the changed
  values; do not also call refresh().
- A fake player's player-list entry carries its skin: keep it until its chunk has been on the
  client ~200 ticks, then remove it, so NPC names stay out of chat completion.
- EntityLib's entity ids come from the server's own counter, so they never clash with other plugins.
