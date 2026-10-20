# Lively NPCs: what the four sites allow

Checked on 2026-10-20 against each site's own rule pages (links below). Rules change: read the
linked pages again on the day you publish. Nothing here is legal advice.

**brick's decisions (2026-10-20), after reading this:** Lively NPCs is open source under the GPL-3.0
(blocker 0 settled: `LICENSE` at the repo root, also inside the jar), and it is published on **Hangar
only** for now: Modrinth and BuiltByBit (AI rules) and SpigotMC (Paper only) are skipped. The
freeware TERMS.md and LICENSE.txt are gone; the store pages say GPL and link the source.

## Blockers and risks (read these first)

**0. Every site: the jar carries EntityLib, which is GPL-3.0, so it cannot simply be closed
source.** Lively NPCs bundles the library EntityLib inside its jar (`build.gradle.kts`: shaded and
moved to `dev.livelynpcs.libs.entitylib`). EntityLib 3.3.7 is published under the GNU GPL 3.0 (its
own build file says `GPL-3.0`; its licence has been GPL-3.0 since its first commit in 2023:
<https://github.com/Tofaa2/EntityLib>). The GPL lets anyone pass on a program that contains GPL code
only under the GPL, with its source code available. A closed jar with "no redistribution" terms and
EntityLib inside goes against that. The sites care: BuiltByBit rule 5.6 forbids content "which you do
not have the right or authorization to redistribute", Modrinth section 4 says you must have "the
necessary licenses", and SpigotMC removes resources on copyright complaints. Before 1.0.0,
**pick one** (the lead can do the code part; have a lawyer confirm whichever you pick):
- **Ask EntityLib's author (Tofaa) for written permission** to ship EntityLib inside a closed-source
  free plugin.
- **Stop bundling EntityLib:** replace it with Lively NPCs' own packet code (it only wraps
  PacketEvents), or make it a separate plugin that server owners install, like PacketEvents. A
  separate plugin is how most closed plugins live beside GPL code (PacketEvents is GPL-3.0 too, and so
  is the Bukkit API that Paper's API grew from), but whether that is enough is argued about.
- **Make Lively NPCs open source under the GPL-3.0.** That changes your "closed source" decision.

**1. Modrinth: its AI rule blocks Lively NPCs as it is made today.** Modrinth's Content Rules
(section 6, page last modified 2026-08-13) say "Projects cannot be entirely or primarily comprised of
content created or derived from generative AI output" and, in 6.2, "Projects may not be published
publicly if the contents are primarily or entirely a product of AI output." Lively NPCs' code is
written by Claude (31 of the 32 commits so far carry Claude as co-author), so it is primarily AI
output. Section 6.2 also bans icons, gallery images and description images "created or derived from
generative AI output": the icon and banner in `release/art/` were drawn by Claude, so they count too.
Modrinth's PacketEvents and the rest of the page are fine; the project itself is the problem.
Rules: <https://modrinth.com/legal/rules>

**2. BuiltByBit: its AI rule blocks Lively NPCs too.** BuiltByBit's announcement of 2026-07-03
(the AI content disclosure): "Significantly AI-generated content and AI art in resource files remain
entirely disallowed." Small "assisted" uses are allowed and must be disclosed in two new required
fields; "hiding significant use as 'minimal'" is treated as a violation. Its AI art page
(updated 2026-07-09) also says AI art in descriptions or media "may be rejected if it could
potentially mislead a buyer", and must not depict a product's visual features.
Rules: <https://builtbybit.com/threads/ai-content-disclosure-improved-buyer-protection-platform-fee-correction.740329/>,
<https://builtbybit.com/wiki/ai-art/>

**What this means:** once blocker 0 is settled, publish on **Hangar and SpigotMC**, which have no AI
rule today. Do not mark
Lively NPCs as "no AI" or "minimal AI" anywhere: that would break both sites' rules above. If you
still want Modrinth or BuiltByBit, ask their staff first (Modrinth support, a BuiltByBit ticket) and
expect a no. The store pages for all four are written anyway, so they are ready if a rule changes.

**3. Hangar: closed source is allowed, obfuscation is not.** Hangar's guidelines (updated
2025-12-30) have no open-source rule, and closed projects are listed there today (several use the
licence "Other: ARR"). But: "we do not allow submitted plugins to be obfuscated in any capacity."
The Lively NPCs jar is not obfuscated (EntityLib is only moved to another package), so it is fine:
**never add an obfuscator** if you want to stay on Hangar. Two more Hangar facts that would stop you
at the form: the project **name cannot have a space** (letters, digits, `-` and `_` only, at most 28),
so it is **`LivelyNPCs`**; and new versions can wait in a staff review queue before they go public.
Rules: <https://hangar.papermc.io/guidelines>

**4. SpigotMC: risk, not a rule.** SpigotMC's "Spigot" category is described as "Plugins which work
on a standard Spigot install." Lively NPCs runs on Paper only (it uses Paper's text and resource-pack
APIs), so a moderator could move or remove it. Many Paper-only plugins are listed there; say "Paper
26.3 only" in the title line and the first lines of the description, as the forum rules ask
("Resources that require third party software to load ... should clearly state so").
Rules: <https://www.spigotmc.org/wiki/spigot-rules/>

**5. Every site: say plainly what goes over the internet.** Lively NPCs contacts Mojang
(`api.mojang.com`, `sessionserver.mojang.com`) when you take a skin from a player name, downloads skin
images from `textures.minecraft.net`, and sends image links or uploaded skin files to MineSkin
(`api.mineskin.org`, with your own key) to have them signed. It also runs a small web server (the
editor, and the pack in builtin mode). It sends no statistics and does not check for updates. The
store pages all say this in a "What goes over the network" part: Modrinth requires it for uploads to
a remote server, BuiltByBit requires such services in the Dependencies tab.

## Site by site

### Hangar (hangar.papermc.io)

| Question | Answer |
|---|---|
| Free, closed source allowed? | Yes. No open-source rule. Not allowed: obfuscation "in any capacity", features behind a paywall or licence key, ads (donation links are fine). |
| Licence field | Options: Unspecified, MIT, Apache 2.0, GPL, LGPL, AGPL, **Other** (custom name up to 20 characters, plus a URL). Use **GPL** and give the public source repo's link. |
| Web server, ports, outside requests | Nothing in the guidelines. Staff "may choose to allow or disallow" anything not listed, so the page says what the plugin does. Not allowed: plugins made for offline-mode servers (Lively NPCs is not). |
| Images | Project avatar: one square image (no size published; use `art/icon-512.png`). No banner field: images in the description are Markdown links to images hosted elsewhere. Jar up to 10 MB. |
| Description format | Markdown pages (up to 75,000 characters each, up to 50 pages). Summary up to **120 characters**. |
| Categories and tags | One category from: Admin Tools, Chat, Developer Tools, Economy, Gameplay, Games, Protection, Role Playing, World Management, Miscellaneous. Pick **Gameplay**. Up to 5 keywords, 16 characters each: `npc`, `animation`, `hub`, `packets`, `editor`. Platform: **Paper** only, version 26.3 ("selected platforms must be able to load the plugin"). |
| Review | A project stays hidden until it has a version; versions can wait in a staff "approval queue" before going public. Removed for: obfuscation, paywalls, malicious code (e.g. granting OP to the author), botted downloads, names with a platform/version/tagline in them, misleading tags or keywords. |

Data checked: <https://hangar.papermc.io/guidelines>, Hangar's own form limits
(`/api/internal/data/validations`, `/licenses`, `/categories`) and its interface text.

### SpigotMC (spigotmc.org)

| Question | Answer |
|---|---|
| Free, closed source allowed? | Yes. Anyone may post free resources. Obfuscation is allowed only lightly for free resources (name obfuscation with approved tools; package root and main class kept). We use none. |
| Licence field | None required. The form has optional links (source code, donation): leave the source link empty. Put the terms in the description (and in the jar). |
| Web server, ports, outside requests | No rule found. Rules forbid malicious resources and pay-per-click download links; external download links must be direct. |
| Images | Icon: a small square image (no size published; upload `art/icon-256.png`). No banner field: put `art/banner.png` at the top of the description with `[IMG]`, hosted somewhere public (e.g. imgur or a public GitHub repo). Keep real text out of images. |
| Description format | **BBCode** (XenForo): `[B] [I] [U] [S] [COLOR] [SIZE=1-7] [URL] [IMG] [MEDIA] [LIST] [CENTER] [QUOTE] [SPOILER] [CODE] [ICODE] [TABLE]`. Tag line: one short line. |
| Categories and tags | **Spigot → Fun** (or Misc). Tested versions: 26.3. No free-form tags. |
| Review | Free resources go live at once; staff act on reports. Premium resources (not us) are reviewed and "must support the latest stable version of Spigot". Removed for: malicious code, someone else's work, advertising beyond the rules, misleading content. |

Rules: <https://www.spigotmc.org/wiki/spigot-rules/>, BBCode list: <https://www.spigotmc.org/help/bb-codes>,
categories: <https://www.spigotmc.org/resources/>, premium rules (for comparison):
<https://www.spigotmc.org/threads/31667/>.

### Modrinth (modrinth.com) — blocked, see blocker 1

| Question | Answer |
|---|---|
| Free, closed source allowed? | Closed source is allowed (no rule against it; "All Rights Reserved" is a licence choice), but the AI rule blocks this project. |
| Licence field | Required: **GPL-3.0-only**, with the source repo's link. (Skipped for now: the AI rule.) |
| Web server, ports, outside requests | No port rule. Section 1.11: nothing may "upload any data to a remote server ... without clear disclosure": MineSkin uploads must be disclosed on the page. |
| Images | Icon up to 256 KiB (`art/icon-512.png` fits); gallery images up to 5 MiB each. No banner. **No AI-made images anywhere on the page** (6.2). |
| Description format | Markdown, with a plain-text version (no text only in images; no headers used as body text). English. Summary: "a sentence or two", unformatted, not repeating the title. |
| Categories and tags | Loader **Paper**, game version 26.3. Categories: **Decoration**, **Utility** (maybe Game Mechanics). |
| Review | Every project is checked by moderators before it is public. Rejected for: wrong/misleading info, missing disclosures, AI content (section 6), reuploads, missing dependencies in the version's Dependencies list (add PacketEvents there). |

### BuiltByBit (builtbybit.com) — blocked, see blocker 2

| Question | Answer |
|---|---|
| Free, closed source allowed? | Free resources yes; closed source yes (there are "open source" and "DRM-free" disclosure fields). Blocked by the AI rule. |
| Licence field | No licence field: BuiltByBit's own resource EULAs cover downloads made there (<https://builtbybit.com/wiki/resource-eulas/>, updated 2026-07-03). Careful: its **Free EULA allows redistribution** (and modification), which is against your terms; its **Standard EULA** (unlimited projects, no redistribution) fits them better, so pick that if the form lets a free resource use it. Every BuiltByBit EULA lets people decompile and change a product for their own use. Attach `TERMS.md` as well if the form allows. |
| Web server, ports, outside requests | Must be listed in the **Dependencies tab**: "a website from which data will be downloaded or communicated for the functioning of the product" — Mojang, MineSkin, and PacketEvents (with a public link; use GitHub, since rule 1.19 forbids linking competing sites such as Modrinth, Hangar or SpigotMC). |
| Images | Cover: 2:1, at least 1000x500 (`art/cover-builtbybit.png` is 1200x600). Image carousel: up to 3 images on an unranked account. Icon: square (`art/icon-512.png`). AI art may be rejected (see blocker 2). |
| Description format | **BBCode**: `[B] [I] [U] [S] [COLOR] [SIZE] [URL] [IMG] [MEDIA] [LIST] [CENTER] [QUOTE] [SPOILER] [CODE] [ICODE] [INDENT] [HEADING=1-3]`. `[TABLE]`, `[HR]` and `[BUTTON]` are for Ultimate members only, so the page uses none. English. |
| Categories and tags | Minecraft → Plugins → **Hub** (or Decoration / Fun). Title at most 40 characters: no version, price, emojis, "best". |
| Review | Every resource is reviewed by hand before it is public, usually within 48-72 hours; most problems come back as "changes required". Rejected for: download not working as described, external download links instead of the file, missing dependency links, low-quality descriptions, wrong category, misleading titles, AI content. |

Rules: <https://builtbybit.com/wiki/rules/>, creators: <https://builtbybit.com/wiki/creators-overview/>,
dependencies: <https://creators.builtbybit.com/dependencies>, covers: <https://creators.builtbybit.com/images>,
review: <https://creators.builtbybit.com/approval-process>, BBCode: <https://builtbybit.com/wiki/bbcodes/>.

## Before you publish (checklist)

- [ ] Settle EntityLib's GPL licence (blocker 0): permission, no bundling, or open source.
- [ ] Decide about Modrinth and BuiltByBit (blockers 1 and 2). Safe plan: Hangar and SpigotMC only.
- [ ] Accounts: Hangar and SpigotMC. Only you can make these.
- [ ] Make the GitHub repo Brick-Bread/lively-npcs public (the GPL and Hangar's source link need the code to be reachable).
- [x] `LICENSE` (GPL-3.0) is in the jar: the build adds it.
- [ ] Contact details: fill every `{{...}}` placeholder in the store pages (Discord invite, e-mail).
- [ ] Host the banner and screenshots somewhere public for the BBCode pages (`{{BANNER_URL}}`...).
- [ ] In-game screenshots from `SCREENSHOTS-TO-TAKE.md` (only real screenshots: no AI images).
- [ ] Version 1.0.0 in `build.gradle.kts`, build, upload `LivelyNPCs-1.0.0.jar`.
- [x] No terms of our own any more: the GPL-3.0 is the licence.
