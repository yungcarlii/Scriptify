# Scriptify

**A server-side toolkit for making scripted Minecraft videos.**

Fake deaths, nicknames, skins, a cast of bots, kits, vanish, immortality and a pile of scene-setting
tools — all driven from one menu, all running entirely on the server.

> **Your players don't install anything.** Everything works on a completely vanilla client. No client
> mod, no resource pack, no modpack to hand out. If they can join your server, they can see it.

---

## Why this exists

Shooting a scripted SMP episode means constantly faking things: someone has to "die" convincingly,
another player needs a different name and skin for one scene, a fight needs twenty extras who can
actually fight, everyone needs the same loadout for take four, and nobody can lose their gear doing
it.

Scriptify puts all of that behind one command — `/scriptify` — so you can set up a shot in seconds
instead of stringing together twelve vanilla commands and hoping.

---

## Quick start

1. Drop `scriptify-2.0.0.jar` into your server's `mods` folder.
2. Start the server.
3. Run **`/scriptify`** in game.

That's it. The menu that opens is the whole mod.

### Requirements

| | |
| --- | --- |
| **Minecraft** | 1.21.11 |
| **Loader** | Fabric 0.19.3+ |
| **Fabric API** | Required |
| **Java** | 21+ |
| **LuckPerms** | Optional — used automatically if installed |
| **HeroBot** | Optional — needed only to drive bots |

Built and tested against 1.21.11, and it declares exactly that. The mod hooks internal server
classes, and those are renamed between Minecraft releases, so a build that claims a version it was
not made for does not degrade politely — it fails to start. A refused install that tells you the
version is wrong is the better outcome, so that is what you get. Each Minecraft version gets its own
build.

`sgui` is bundled inside the jar. You do not need to download it separately.

**Runs on the server.** Dedicated servers, LAN worlds and singleplayer all work — in singleplayer the
integrated server is the server, so the menus open normally. Other players never need it.

---

## Features

### Deaths that aren't deaths

The headline feature. When a player takes a fatal hit, Scriptify catches it: they don't actually die,
lose nothing, and see no respawn screen. Instead everyone watching gets the real death message
followed by a genuine-looking **"left the game"** — and the player quietly drops out of the tab list
and into spectator, free to keep filming their own "death".

They also **drop a copy of their gear on the ground** and the death sound plays, so from the outside
it reads as an ordinary death. The copies are duplicates — the player keeps everything they had — and
they can be picked up by default, because looting the "corpse" is usually the point of the shot.

Coming back is easy: switch out of spectator, run `/ondeath restore`, or just reconnect. Their
inventory, XP and health come back exactly as they were.

A **held totem still pops normally**, so it never eats a dramatic totem moment. Bots and other fake
players disconnect instead of being parked in spectator, since nobody is driving them back.

### Bots

A cast of extras that fight, follow, walk routes, hold still and act out scenes on cue. Formerly a
separate mod called SBots; as of 2.0.0 it is part of Scriptify and lives under **`/bots`**.

- **Edit a bot by hitting it.** `/bots edit` turns the mode on, then punching any bot opens its panel
  — because naming one on a command line while looking at two hundred of them is the thing that makes
  editing them not worth doing. Three labelled bands: **Settings** (hittable, collidable, crouching,
  shows in tab, immortal, drops on death), **Actions** (copy my effects, rename, change skin,
  randomise identity, teleport to me, apply a kit) and **Movement** (follow, wander, path, record).
- **Paths are drawn by walking them.** Your hotbar is borrowed — emerald drops a point where you
  stand, red dye takes one back, lime dye saves, barrier cancels. The finished route is drawn in the
  world while it is walked and loops back round to the first point instead of turning about and
  retracing itself. The older point-at-a-time commands write to the same file, so the two are
  interchangeable.
- **Recording copies you exactly** — position, look direction and crouch, sampled every tick, not
  just the path you took. A bot replaying a recording reads as a person rather than as something
  following waypoints. Use a recording for the shot and a path for a patrol.
- **Tools instead of typing.** `/bots tools` opens a rack holding a **Path Stick** and a **Kit
  Shield**: hold right click to put every bot around you on a path, or deal them all a kit, without
  naming any of them.
- **Factions** decide who fights whom. Create them, set hostility, add players or whole groups, then
  send a faction down a path or hand it a kit in one command.
- **Scenarios** are prewritten scenes — a conversation, a crowd, a prison — laid out by walking the
  points and assigning bots to them.
- **Combat AI** with weapon selection, healing, pearling, bridging and cobweb tactics. It is tuned to
  look right on camera rather than to win: bots pearl away *before* healing rather than after failing
  to, cut webs with a sword rather than their fists, climb out of holes instead of walking into the
  wall, and do not leave block-breaking cracks over the scenery mid-fight.
- **`/bots performance`** reports what the roster is actually costing — count, stride, and how many
  bots thought last tick. Check it before blaming the server.

The bot half began as a fork of [PvP Bot](https://github.com/Stepan1411/pvp-bot-fabric) by
Stepan1411, which is public domain under the Unlicense. See
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

Bots are driven by a fake-player backend. **HeroBot is suggested, not required** — without it the
commands still exist and say plainly what is missing rather than failing oddly. Carpet's `/player`
bots are recognised as bots too, including by staged deaths.

### Targeting

Any command that takes a `[player]` also takes a selector.

| Selector | Who it means |
| --- | --- |
| `@a` | Everyone online |
| `@s` | You |
| `@p` | The nearest player |
| `@r` | One player at random |
| `@o` | Operators only |
| `@d` | Default players — real players without op |
| `@b` | Bots and other fake players |

Real usernames work too, and so do nicknames — the name above someone's head is a name you can type.
Several targets can be listed at once: `/heal Steve @o`.

`@d` rather than `@n` for non-operators, because vanilla already uses `@n` for the nearest entity.

`@o`, `@d` and `@b` also work on **vanilla** commands — `/gamemode adventure @d`, `/tp @b`, command
blocks and functions included. Your client does not know these letters: it will not suggest them and
will underline them red as you type. It sends the command anyway and the server is what decides, so
they work regardless.

### Identity

Both are full screens with a grid of player faces — pick someone, click, done. No typing names.

- **Nicknames** — change a player's name everywhere it matters: tab list, above their head, death
  messages, chat. Commands still target their real account, so you never lose track of who's who.
- **Random nicknames** — generated usernames for one-off characters, built from the shapes real
  accounts actually use. Real usernames are mostly lowercase, short and invented (`nitronze`,
  `akode`) or oddly decorated (`_Stanik`, `qRapid`, `sorryimbritish`), not tidy `AdjectiveNoun42`
  compounds, so that is what the generator produces. It remembers recent rolls, never repeats a name
  inside one roster, and never cuts one off mid-word.
- **Skins** — put any player's skin on anyone, and put it back afterwards. **Random skin** pulls a
  real account's skin, verified through Mojang, skipping accounts that never set one. If Mojang is
  unreachable it borrows from someone online instead.
- **Identity** — name and skin as a single act. `/identity set <account>` makes someone that player;
  `/randomidentity` makes them a random real one. Both halves come from the same account, because a
  real name over an unrelated skin reads as two glitches rather than one person.

Your own skin is copied and stored the first time Scriptify overrides it, so `/identity clear` and
`/skin clear` give back what you actually had rather than a guess.

### Player control

| Feature | What it does |
| --- | --- |
| **Freeze** | Locks someone in place. They can still look around, sneak and jump. Run it again to release. |
| **Invisibility** | Properly invisible — armour included, which the vanilla effect doesn't hide. |
| **Vanish** | Fully gone: no entity, no tab list, no trace. |
| **Immortality** | Hits still land and knock back, you just can't die. Optional timer. |
| **Gamemode** | `/gms` `/gmc` `/gma` `/gmsp` |
| **Max health** | Change the actual heart row, not just current health. |
| **Heal / Feed / Totems / Top** | The usual quality-of-life shortcuts. |

### Inventory

- **Kits** — save a full loadout and hand it to anyone. Enchantments and custom items survive intact.
  The kit's menu icon is whatever you were holding when you made it. Items are **dealt out one at a
  time** — along the hotbar, then the backpack, then armour piece by piece — with a rising chime as
  it fills. It looks like being handed a loadout rather than an inventory blinking into existence.
  Turn it off with `kits.animate` if you want it instant. Kits are ordered oldest first, so a new one
  lands at the bottom instead of jumping into the middle of the grid.
- **Inventory rollback** — a snapshot is taken **automatically the moment anyone dies**, plus whenever
  you ask for one. Death is exactly when a loadout vanishes and exactly when nobody remembers to
  press save first. Death snapshots are marked with a skull and are the last to be pruned. Open
  anyone's history with `/backupinv <player>`.
- **Restock** — top every stack back to full between takes.
- **Durability** — repair everything, or scuff it all up for a battle-worn look.
- **Random fill** — **look at a chest, barrel or shulker box** and fill it. Pick from 13 item
  categories (blocks, weapons, armour, food, valuables, mob drops, discs…) **or pick exact items** by
  holding them, set the stack-size range and how full it gets, then save the whole thing as a named
  preset — or click a category to open it and tick individual items, which is usually what a
  believable container needs. Four presets are included: **survival**, **armory**, **market** and
  **vault**. Leaving it below 100% full is what makes a container read as looted rather than
  generated. Category pools **exclude anything unobtainable in survival** — no command blocks,
  barriers, spawn eggs or bedrock turning up in a chest. Hand-picked items bypass that filter, since
  choosing one is a deliberate decision.
- **Copy inventory** — `/copyinv <player>` hands someone your exact loadout, durability and custom
  items included, for doubles and lineups of identically kitted extras. Theirs is snapshotted first,
  so it is undoable from `/backupinv`.
- **Clear ground** — `/clearground [radius]` sweeps up dropped items between takes.
- **Invsee / Ender chest** — live, editable views of another player's storage.

### Authoring

- **Item maker** — `/itemmaker` opens a full editor on whatever you are holding, with the item
  previewed live at the top of every screen. Browse every enchantment the server knows and click to
  set the level — *any* enchantment at *any* level on *any* item, because a Sharpness 100 carrot is a
  valid prop. Enchantments are listed by what they do rather than what they go on. Edit lore line by
  line, pick a name colour from a wall of swatches, set the stack size, toggle unbreakable and forced
  glint. Nothing in it needs an id typed by hand; the `/item` subcommands are still there for
  scripting.
- **Villager maker** — shopkeepers with hand-written trades. Scene villagers are frozen by default:
  no AI, no wandering, invulnerable, and stock never runs out, so a merchant can't walk off mid-take.
- **Teams** — colours, prefixes, friendly fire, collision and nametag visibility, built on vanilla
  scoreboard teams so everything else on your server understands them. New teams start with friendly
  fire and collision off, because cast members shoving each other ruins a shot.
- **Ranks and permissions** — see below.

### Scene tools

- **Potion presets** — named effect bundles, one click. **Save your own**: give yourself effects with
  `/effect`, then press save and the whole set becomes a named preset.
- **Fake join / leave messages** — on demand.
- **Fake death messages** — without anyone dying.
- **Fake tab list** — pad the tab with players who are not there, so a small server reads as a busy
  one. Every padded entry has a face, and the roster **drifts**: names turn over at a rate that
  scales with the list, and latency bars twitch on their own clock, because a tab list that is
  byte-for-byte identical for a whole shot is the tell. The invented names stay out of command
  autocomplete and can never collide with somebody actually connected.
- **Sudo** — make a player run a command or say a line on cue.
- **Mute chat** — silence everyone but staff during a take.
- **Fake advancements** — `/fake advancement Steve challenge Betrayal | Stab your best friend` prints
  vanilla's own purple challenge line, hover included, and plays the real toast on Steve's screen. Any
  title, task, goal or challenge, and the icon is whatever you are holding. It is gone again the
  moment the toast has played: it never shows in the advancement screen and nothing is saved.
- **Fake kills, kicks and bans** — `/fake death Steve Herobrine` is "Steve was slain by Herobrine",
  for a killer who need not be online or exist. `/fake kick` and `/fake ban` show the real disconnect
  screens with your reason and print the normal leave line; nothing is recorded, so they rejoin
  straight away.
- **Fake chat and whispers** — `/fake chat Herobrine I am watching you` is a chat line from somebody
  nobody is logged in as; `/fake whisper Steve Herobrine Turn around` reaches Steve alone.
- **Harmless lightning and explosions** — `/fake lightning` strikes where you are looking with real
  thunder but no fire, no damage and no charged creepers; `/fake explosion` is the blast, its sound
  and nothing else, visible from across the map. The set stays standing for take two.

### Cues — scene scripts

A cue is one beat of a scene — a title, a pause, lightning, a line of dialogue, a death — fired with
one command, at exactly the same timing on every take.

- **Record it by doing it.** `/cue record ambush`, then run the scene's commands at the pace you
  want them, then `/cue done`. Every command is kept together with the real time between them. Your
  first command starts the cue, so time spent thinking before it is not part of the scene.
- **Or write it.** `/cue new ambush`, then `/cue add ambush <command>` and `/cue wait ambush <seconds>`.
  A command that does not parse is refused when you add it, not halfway through a take.
- **Play it.** `/cue play ambush` — from chat, a command block behind a button, or another cue.
  Commands run **silently**: no "Teleported Steve to…" in chat, because chat is in the shot. They run
  with the permissions of whoever played the cue, never more.
- **Read and fix it.** `/cue show ambush` lists every step with the second it starts at; click a
  step to remove it. `/cue` lists all cues; click one to play it.

Cues are saved one file each under `scriptify/events/` in the world folder.

---

## Commands

Everything below is also reachable from the `/scriptify` menu.

### Core

| Command | Description |
| --- | --- |
| `/scriptify` | Open the main menu |
| `/scriptify help` | Clickable command index |
| `/scriptify version` | Which build you are running |
| `/scriptify compat` | What overlaps with other mods, and who won |
| `/kitted [player]` | Open the kit menu; with a player, kits go to them |
| `/randomfill` | Open the fill menu |
| `/randomfill now` | Fill what you're looking at with the current settings |
| `/randomfill preset save\|load\|delete\|list` | Manage fill presets |
| `/backupinv [player]` | Inventory rollbacks |
| `/backupinv save [player]` | Take a snapshot now |
| `/potion` | Potion presets |
| `/faketab off\|small\|medium\|big\|verybig\|massive` | Pad the tab list |
| `/faketab count <n>` | An exact number of fake players |

### Bots

| Command | Description |
| --- | --- |
| `/bots` | Open the bot menu |
| `/bots spawn <n>` | Spawn bots |
| `/bots list` · `/bots remove <bot>` · `/bots removeall` | Manage the roster |
| `/bots who <bot>` | A bot's personality and current playstyle |
| `/bots performance` | What the roster is costing right now |
| `/bots edit` | Turn on edit mode, then hit a bot to open its panel |
| `/bots set <bot> <setting> <value>` | Set one thing outright, for command blocks |
| `/bots group <group> <bot>` · `/bots ungroup <bot>` | Grouping |
| `/bots skin <bot> ...` | Give a bot an appearance from the local pool |
| `/bots tools` · `/bots tool <name>` | The tool rack, or one tool from it |
| `/bots kit create-kit\|delete-kit\|kits` | Bot kits |
| `/bots kit edit <kit>` · `/bots kit stick <kit>` | Kit editor, and a Kit Stick bound to one |
| `/bots kit give-kit\|give-kit-near\|give-kit-near-random` | Deal kits out |
| `/bots bot-management mass-spawn <n>` | Spawn a lot at once |
| `/bots bot-management attack\|stop-attack` | Point them at something |
| `/bots bot-management inventory <bot>` | Look in a bot's inventory |
| `/bots bot-management path create <name>` | Draw a path by walking it |
| `/bots bot-management path start <path> [bot]` | Start a bot on it, or take the Path Stick |
| `/bots bot-management path add-point\|remove-point\|clear\|delete` | Edit a path by hand |
| `/bots bot-management path loop\|show\|info\|list\|walk-type` | Path properties |
| `/bots bot-management path distribute\|start-near\|stop\|stop-all` | Put bots on a path |
| `/bots bot-management path populate <path> <count> [seconds]` | Spawn bots at the start of a path one by one, a loosely random gap apart (4 s by default); `stop-all` cancels the rest |
| `/bots bot-management path walk-type <path> mixed\|walk\|sprint\|bhop` | `mixed` (the default): each bot walks, sprints or sprint-jumps in its own way and some stop now and then to look around. A bot boxed into one 3×3 patch for 4 s searches a route out and rejoins the path |
| `/bots faction create\|delete\|list\|info` | Factions |
| `/bots faction add\|remove\|add-near\|add-all` | Faction membership |
| `/bots faction hostile\|attack\|tp\|give` | What a faction does |
| `/bots faction path start\|stop` · `/bots faction kit give-kit` | Move or equip a whole faction |
| `/bots scenarios new\|point add\|point undo\|finish\|cancel` | Lay out a scene by walking it |
| `/bots scenarios list\|remove\|removeall` | Manage scenes |
| `/bots scenarios crowd\|assigncrowd` | Crowd scenes |
| `/bots savescene <name> [radius]` | Save every bot around you — spot, gear, skin, faction, settings, path, follow, wander, recording — as a named scene (radius 48 by default) |
| `/bots scene <name>` · `/bots scene <name> remove\|delete` · `/bots scenes` | Bring a scene back (bots already standing are skipped), take its bots out again, forget it, or list them |
| `/bots massspawn <n>` · `/bots massaction <action>` | Do one thing to the whole roster |
| `/bots kitall <kit>` · `/bots tphere` | Kit or gather every bot |
| `/bots hide\|show\|defaultsettings` | NPC appearance and defaults |

### Identity

| Command | Description |
| --- | --- |
| `/nick <name> [player]` | Set a nickname |
| `/randomnick [player]` | Assign a generated nickname |
| `/unnick [player]` | Remove a nickname |
| `/skin set <source> [player]` | Copy another account's skin |
| `/skin clear [player]` | Restore their own skin |
| `/randomskin [player]` | Roll a random real player's skin |
| `/identity set <source> [player]` | Take a name and skin together |
| `/identity clear [player]` | Give back their own name and skin |
| `/randomidentity [player]` | Become a random real player |

### Player

| Command | Description |
| --- | --- |
| `/gms` `/gmc` `/gma` `/gmsp` | Gamemode, optionally with a target |
| `/freeze [player]` | Lock a player in place; run it again to release |
| `/unfreeze [player]` | Release explicitly |
| `/invis on\|off [player]` | Full invisibility |
| `/vanish` | Disappear completely |
| `/heal [player]` · `/feed [player]` | Restore health / hunger |
| `/health <hearts> [player]` · `/health reset` | Change the heart row |
| `/totem [count] [player]` | Give totems |
| `/top` | Teleport to the surface |
| `/pvp on\|off` | Toggle PvP server-wide |

### Immortality

| Command | Description |
| --- | --- |
| `/immortal [players]` | Toggle immortality, like `/freeze` |
| `/immortal <players> <seconds>` | Turn it on for a fixed time (`-1` = forever) |
| `/immortal status [player]` | Check someone's status |
| `/immortal global [seconds]` | Toggle it for everyone |
| `/immortal fall` · `armor` · `void` | Toggle the sub-options |

### Inventory

| Command | Description |
| --- | --- |
| `/invsee <player>` · `/ec <player>` | View and edit their storage |
| `/copyinv <player>` | Give them an exact copy of your inventory |
| `/clearground [radius]` | Remove dropped items from the ground |
| `/restock` | Refill every stack |
| `/durability` | Menu: repair, or wear gear down (heavy / moderate / light / exact %) |

### Authoring

| Command | Description |
| --- | --- |
| `/itemmaker` | Open the item editor on the held item |
| `/villagermaker` | Open the villager editor on the one you face |
| `/item name <text>` | Rename the held item (`&` colour codes work) |
| `/item lore add\|undo\|clear` | Edit the item's lore |
| `/item enchant <id> <level>` | Any enchantment, any level, any item (`0` removes) |
| `/item unbreakable\|glint\|count` | Other item properties |
| `/villager create <name> [profession] [level]` | Spawn a scene villager |
| `/villager addtrade` | Slot 1 + slot 2 → held item, on the villager you face |
| `/villager removetrade\|cleartrades\|list` | Manage its trades |
| `/steams create\|delete\|add\|remove` | Team membership |
| `/steams colour\|prefix\|friendlyfire\|collision\|nametags` | Team appearance and rules |
| `/scriptifyperms grant\|revoke <node> <player>` | Grant or revoke a node |
| `/scriptifyperms list <player>` · `/scriptifyperms nodes` | Inspect permissions |
| `/scriptifyranks` | Rank editor |
| `/scriptifyranks create\|delete <name>` | Manage ranks |
| `/scriptifyranks set <rank> <player>` | Put a player in a rank |
| `/scriptifyranks node <rank> <node> <true\|false>` | Change what a rank allows |
| `/scriptifyranks level <rank> <0-4>` | Vanilla permission level for a rank |
| `/scriptifyranks filter <rank> <all\|only\|except>` | Which vanilla commands it may run |
| `/scriptifyranks check <command> <player>` | Explain, in order, why they can or can't |
| `/scriptifyranks list` · `/scriptifyranks prune` | Inspect ranks; clear rows left by old builds |

### Scene

| Command | Description |
| --- | --- |
| `/ondeath` | Show staged-death status |
| `/ondeath on\|off` | Toggle staged deaths |
| `/ondeath restore [player]` | Bring someone back into the scene |
| `/sudo <player> command <...>` | Run a command as them |
| `/sudo <player> say <...>` | Say something as them |
| `/fake join\|leave <players>` | A join or leave line; a leave also drops them from the tab list |
| `/fake death <players> [killer]` | A death line, or "slain by" whoever you name |
| `/fake advancement <player> task\|goal\|challenge <title> [\| description]` | Chat line and toast; icon is your held item |
| `/fake kick\|ban <player> [reason]` | The real disconnect screen; nothing recorded |
| `/fake chat <speaker> <message>` | A chat line from anyone — an online player's display name, or any name at all |
| `/fake whisper <to> <from> <message>` | "<from> whispers to you", to that one player |
| `/fake lightning [pos]` | Real bolt and thunder; no fire, no damage. Lands where you look by default |
| `/fake explosion [pos]` | The look and sound of a blast; nothing broken, nobody hurt |

### Cues

| Command | Description |
| --- | --- |
| `/cue` · `/cue list` | Every cue, click to play |
| `/cue record <name>` · `/cue done` | Record the commands you run, with their timing |
| `/cue new <name>` | Start an empty cue |
| `/cue add <name> <command>` | Add a command step |
| `/cue wait <name> <seconds>` | Add a pause |
| `/cue show <name>` · `/cue remove <name> <step>` | Read the steps; take one out |
| `/cue play <name>` · `/cue stop [name]` | Run a cue; stop one or all |
| `/cue delete <name>` | Forget a cue |

---

## Permissions

Works out of the box — **server operators can use everything**. If you want finer control, install
LuckPerms and Scriptify will use it automatically.

| Node | Covers |
| --- | --- |
| `scriptify.admin` | Everything, including `/sudo`, `/cue` and reload |
| `scriptify.gui.open` | The `/scriptify` menu |
| `scriptify.nick` | Nickname commands |
| `scriptify.skin` | Skin commands |
| `scriptify.player` | Player state: freeze, invis, vanish, immortal, health, gamemode |
| `scriptify.inventory` | Invsee, backups, copyinv, clearground, restock, durability, random fill |
| `scriptify.kits` | Kits |
| `scriptify.ondeath` | Staged deaths and `/fake` |
| `scriptify.items` | Item maker |
| `scriptify.villagers` | Villager maker |
| `scriptify.teams` | Team management |
| `scriptify.bots` | The bot roster, editor, paths, factions and scenarios |

### Ranks

Ranks are named permission bundles assigned per player, for when per-player grants get tedious.
Scriptify ships with `default`, `crew` and `director`; a player with no rank counts as `default`, so
the baseline is something you can edit rather than something hardcoded. Ranks stack with individual
grants rather than replacing them.

Ranks also configure **vanilla** commands: a permission level (none, moderator, gamemaster, admin)
and either an allow-list or a deny-list of command names.

Two things worth knowing:

- **A rank's command filter is enforced when the command runs, not by hiding it.** A blocked command
  still appears in autocomplete and then refuses.
- **Operators are never filtered.** A rank hands out access; it cannot take it away from an op.

`/scriptifyranks check <command> <player>` explains every step of the decision, including whether
vanilla agrees, asked through that player's own command source rather than inferred. It is the fast
way to answer "why can't they run this".

---

## Configuration

`config/scriptify.json` is created on first start. Bad values are corrected automatically rather than
crashing the server.

```json
{
  "schemaVersion": 5,
  "deathControl": {
    "enabled": true,
    "showDeathMessage": true,
    "fakeLeaveMessage": true,
    "respectTotems": true,
    "disconnectBots": true,
    "dropItems": true,
    "dropsCanBePickedUp": true,
    "playDeathSound": true,
    "hideFromPlayerList": true
  },
  "kits": {
    "animate": true,
    "animationIntervalTicks": 2
  }
}
```

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Turn staged deaths on or off |
| `showDeathMessage` | `true` | Broadcast the real death message first |
| `fakeLeaveMessage` | `true` | Follow it with a "left the game" message |
| `respectTotems` | `true` | Let a held totem pop instead of staging the death |
| `disconnectBots` | `true` | Fake players disconnect instead of going to spectator |
| `dropItems` | `true` | Scatter a copy of their gear on the ground |
| `dropsCanBePickedUp` | `true` | Whether those copies can be collected — off makes them scenery |
| `playDeathSound` | `true` | Play the death sound the cancelled death would have skipped |
| `hideFromPlayerList` | `true` | Also remove them from the tab list |
| `kits.animate` | `true` | Deal kit items out one at a time |
| `kits.animationIntervalTicks` | `2` | Ticks between each item — raise for a slower deal |

Everything else — nicknames, skins, kits, backups, toggles — is stored per-world under
`<world>/scriptify/` and needs no manual editing.

---

## Proximity chat

Players only hear people near them. A conversation in a base stays in that base, and a shout across a
field does not carry to another biome — which is what makes a scripted scene feel like it is
happening somewhere rather than in a chat room.

Set from the menu only, as designed: **/scriptify → Proximity Chat**. Ranges of 8, 16, 48, 128 and
256 blocks, or an exact number between 1 and 512. There is no proximity command.

Only player chat is limited. Private messages, death messages, joins, leaves and `/say` still reach
everyone — those either have no source point at all, or are how you address the whole cast. Another
dimension is always out of earshot, whatever the range.

Nothing about a message is rewritten, so chat signatures and the secure-chat indicator are untouched
for the players who do receive it.

---

## Running alongside other mods

Scriptify does several things other server mods also do. Two mods writing the same display name do
not produce an error — one of them just quietly stops working, and which one loses depends on the
order your mods happened to load in. Scriptify looks for that at startup and says so.

Each contested surface is settled on its own, so you never have to choose between whole mods:

| Setting | Effect |
| --- | --- |
| `PRIORITY` | Scriptify wins this surface. The default. |
| `YIELD` | Scriptify stands down and leaves this one thing to the other mod |
| `IGNORE` | Both run, and load order decides — usually the state you are trying to escape |

```json
"compat": {
  "enabled": true,
  "logOnStartup": true,
  "policies": {
    "nicknames": "PRIORITY",
    "skins": "PRIORITY",
    "tab_list": "PRIORITY",
    "permissions": "PRIORITY",
    "death": "PRIORITY",
    "commands": "PRIORITY"
  },
  "commandOverrides": {},
  "ignoredMods": []
}
```

Yielding is per surface, not per mod. `"nicknames": "YIELD"` hands names to Styled Nicknames and
changes nothing about Scriptify's kits, skins or ranks. `commandOverrides` goes one step finer —
`"nick": "YIELD"` gives up that single command and keeps every other one.

`/scriptify compat` shows what was found and who won each surface.

### What it looks for

Mods known to overlap, by what they contest:

| Surface | Mods |
| --- | --- |
| Nicknames | Styled Nicknames, Styled Chat, Drogtor, Fabric Custom Names, Simple Nicknames, Namer, Player Roles, Simple Voice Chat |
| Skins | FabricTailor, SkinRestorer, SkinShifter, Drogtor |
| Tab list | Styled Player List, Vanish |
| Permissions | LuckPerms, Essential Commands, Player Roles |
| Death | Universal Graves, You're in Grave Danger |
| Commands | Essential Commands, Vanish, Carpet, and anything else claiming the same names |

Being on that list is not a criticism — several of these are better at their one job than Scriptify
is, which is exactly what `YIELD` is for. LuckPerms in particular is a real permission system where
Scriptify ranks are a convenience; if you run both, yielding `permissions` is usually right.

Alongside those, Scriptify recognises a handful of mods it does **not** fight with and reports them
as notes rather than warnings — **ServerReplay**, **ViaVersion**, **Polymer**, **C2ME**,
**Moonrise**, **Lithium** and the live-map mods. Warning about a mod that is working perfectly is how
a log gets skimmed, and then the warning that matters goes past with it.

Two of those change something Scriptify leans on, and are worth knowing about:

- **C2ME and Moonrise** replace the chunk system Scriptify drives to rebuild a player after an
  identity change. It detects this and falls back to plain packets — slightly less precise, and it
  says so in the log rather than failing quietly.
- **Live maps** (BlueMap, squaremap, Dynmap) read the account name rather than the nickname, so a
  disguised player appears on the web map under their real name.

Command collisions are not read from that list. Scriptify claims ordinary names — `/nick`, `/heal`,
`/top`, `/ec`, `/gmc` — and watches the command tree directly to see who else claims them, which
works for a mod published long after this was written. Vanilla commands Scriptify deliberately
extends, such as `/item`, are reported as extensions rather than dressed up as conflicts.

---

## Recording

Scriptify sends **no custom packets**. Everything it does is expressed in vanilla ones, which is what
makes a vanilla client enough — and it also means replay tools record it correctly. A replay that
hits a packet from a mod it does not know is a replay that crashes when you scrub to that frame, so
this is worth stating plainly rather than leaving to chance.

- **[Flashback](https://modrinth.com/mod/flashback)** and the Replay Mod record the client's packet
  stream, so a Scriptify scene records the way it looked.
- **[ServerReplay](https://github.com/senseiwells/ServerReplay)** records from the server instead.
  Start recording before changing an identity and the change is captured as it happened.

One thing to know either way: changing a nickname or skin means rebuilding that player's entity for
everyone who can see them, because a client snapshots the profile when it first spawns a player. In a
recording that shows up as a brief player-list removal and re-add at the moment of the change. It is
correct, and it is how the change gets there at all — just don't cut on that exact frame.

---

## Credits and third-party code

Some features are genuinely difficult to do without a client mod, and Scriptify leans on prior art
for the tricky parts. **[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) is the full list**, also
shipped inside the jar. In short:

| Project | Author | Licence | Relationship |
| --- | --- | --- | --- |
| [PvP Bot](https://github.com/Stepan1411/pvp-bot-fabric) | Stepan1411 | Unlicense (public domain) | **Forked.** Everything under `/bots` descends from it, substantially rewritten and extended. Its Modrinth page shows ARR, which is Modrinth's default for an unset field; the repository's `LICENSE` is the Unlicense. |
| [sgui](https://github.com/Patbox/sgui) | Patbox | LGPL-3.0 | **Bundled** in the jar. Every menu is an sgui screen. Remains LGPL-3.0; Scriptify's MIT does not cover it. |
| [SkinRestorer](https://github.com/Suiranoil/SkinRestorer) | Lionarius | MIT | **Derived.** The sequence that rebuilds a player after a name or skin change is adapted from it, as is the fallback to Mojang's public API and the use of Ely.by. |
| [Vanish](https://github.com/DrexHD/Vanish) | DrexHD | MIT | **Credited.** How to remove a player from the world properly. No code. |
| [immortality-mc-mod](https://github.com/Thesupereevee1/immortality-mc-mod) | Thesupereevee1 | CC0-1.0 | **Credited.** Letting hits land while cancelling the death. No code. |

Minecraft, Fabric and HeroBot are depended on and never redistributed. Mojang, Ely.by and NameMC are
queried over the network; no code of theirs is present.

If you wrote something here and the description is wrong, or you should be credited and are not, open
an issue and it will be fixed in the next release.

A few things worth knowing up front:

- **Nicknames are capped at 16 characters.** That's the limit for a Minecraft username, and the
  above-head nametag is delivered by that mechanism.
- **Changing your own skin updates instantly for everyone else, but you'll see it on your next
  reconnect.** Refreshing your own view mid-session would close whatever screen you had open.
- **A vanilla client still underlines `@o`, `@d` and `@b` red while you type.** It colours commands
  with its own parser, which only knows the six vanilla selectors. The command is sent regardless and
  the server is what decides, so they work — they just don't look like they will.
- **Simple Voice Chat can't draw its speaking icon next to a nametag Scriptify has changed.** That is
  documented on their side and applies to any mod that rewrites nametags. Voice itself is keyed by
  account, so it keeps working.

---

## License

MIT. Use it, fork it, ship it commercially — just keep the copyright notice and the licence
text with any substantial copy of the source.

Releases up to and including 1.2.0 were published under CC0-1.0. That cannot be withdrawn, so
anything you already took from those builds stays public domain; MIT applies from 2.0.0 onward.
