# In-game screenshots for brick to take

The editor screenshots are done (`release/screenshots/`, from the editor preview with made-up NPCs).
What the store pages still need is the plugin **in the game**, and only you can take those. Real
screenshots only: no drawn or AI-made images, and no edits beyond cropping or putting two shots side
by side (Modrinth and BuiltByBit both check this, see `SITE-RULES.md`).

## Before you start

- **A neutral spot:** a small square or a plain field, not RaiseAMob's hub signs, so the shots are
  about the NPCs and not a server. Daylight and clear weather: `/time set noon`, `/weather clear`.
- **The NPC models loaded** (accept the pack), except where a shot says otherwise.
- **Game window 1920x1080**, Fancy graphics, field of view around 70. **F1** hides the hand and the
  hotbar; **F2** saves a screenshot to `.minecraft/screenshots/`. Moving shots: press F2 a few times
  and keep the best.
- **Set up a little cast** (any player names for skins):

  ```
  /npc create guide
  /npc skin guide <player name>
  /npc plate guide <gradient:#6fd3a8:#5cc8ff><bold>Guide | <gray>New here? Click me!
  /npc click guide add message <green>Welcome to the server, {player}!
  /npc item guide book
  /npc icon guide compass

  /npc create smith
  /npc skin smith <another player name>
  /npc plate smith <gold><bold>Blacksmith | <gray>Swords and tools
  /npc item smith iron_sword

  /npc create farmer
  /npc entity farmer villager plains/farmer
  /npc plate farmer <green>Farmer | <gray>Seeds and tools
  ```

  `/npc play <id> <animation>` makes an NPC repeat a move whenever you need it for the camera.

## The shots

| # | What to show | How to frame it |
|---|---|---|
| 1 | **The cast:** three or four NPCs in the square, all turned towards you, plates readable. This is the first image on every page. | Eye level, 6 to 8 blocks back, NPCs in the middle third of the picture. F1 on. |
| 2 | **A greeting:** walk up to the Guide and catch it mid-wave, with the green sparkles. | 3 to 4 blocks away, slightly to one side so the raised arm is clear. `/npc play guide greet` to repeat it. |
| 3 | **Beckoning:** an NPC waving you over from across the square. | Stand 10 to 12 blocks away, looking at it; the NPC small but clear in the centre. `/npc play smith beckon` to repeat it. |
| 4 | **Talking when clicked:** click the Guide; its talking pose and the welcome message in chat. | 3 blocks away. **HUD on** for this one (no F1) so the chat line shows. |
| 5 | **Held item and icon:** the Guide holding its book in its left hand, its compass icon turning above its plate. | Close: 2 to 3 blocks, a little below eye level, plate and book both in the picture. |
| 6 | **Idle life:** several NPCs doing different idle moves at once (stretch, clap, look around). | Wide, like shot 1. Wait a little: each NPC moves about every 12 seconds. |
| 7 | **Two looks:** the same NPC as the moving model, and as the player NPC that players without the pack see. | Same spot and angle twice: once with the pack, once after declining it (server list → Edit → Server Resource Packs: Disabled, then rejoin). Put the two side by side. |
| 8 | **A mob NPC:** the farmer villager with its plate, turned to look at you, beside a person NPC. | Eye level, 4 to 5 blocks away. |
| 9 | **The editor next to the game:** the web editor on one half of the screen, Minecraft on the other, Live preview on, the NPC in game in the same pose as in the editor. | Snap the browser left (Win+Left) and the game right (Win+Right); take the whole screen with Win+Shift+S or PrtSc. |

Optional: a 20 to 30 second video of walking up (greeting), clicking (talking) and the editor's live
preview. Pages can link it (YouTube); images still need to be real screenshots.

## Where they go

- **Modrinth** (if allowed): the gallery, real screenshots only: shots 1 to 9 and the editor ones.
- **Hangar:** in the page as images: shot 1 at the top, then 2, 5, 7 and 9 beside their sections.
- **SpigotMC:** host the images (imgur or a public GitHub repo) and fill `{{BANNER_URL}}` and
  `{{SCREENSHOT_EDITOR_URL}}`; add shots 1, 2, 7 and 9 with `[IMG]`.
- **BuiltByBit** (if allowed): the image carousel takes 3 on a new account: shots 1, 7 and 9.

The editor screenshots will look different after the editor's restyle: take them again with
`node release/take-screenshots.mjs`.
