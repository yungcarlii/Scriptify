# Changelog

## Unreleased

- **Cues - scene scripts.** `/cue record <name>`, act the scene out, `/cue done`: every command you
  ran is kept with the real time between them, and `/cue play <name>` fires the whole beat again
  with the same timing, as often as there are takes. Or write one by hand with `/cue new`, `/cue add`
  and `/cue wait`. Cues run silently (no command feedback in chat, which would be in the shot), with
  the permissions of whoever plays them, and can be played from command blocks and from other cues.
  This is the old, never-reachable event engine made real: its delay step now actually waits, and its
  title step shows a title instead of a chat line.
- **`/fake`** - the illusions as commands, so cues and command blocks can stage them: `join`, `leave`,
  `death` (optionally "slain by" anyone, online or not), `advancement` (vanilla's own chat line plus
  the real toast, any title, the held item as its icon, task, goal or challenge), `kick` / `ban`
  screens that record nothing, `chat` and `whisper` from anyone (online or not), and `lightning` /
  `explosion` that look and sound real and touch nothing: no fire, no damage, no broken blocks.
- **`/durability`** opens a menu: Max, or Random as heavily, moderately or lightly worn, or an exact
  1-100 level. Every item lands a little differently so gear looks used rather than stamped.
- **Fix:** a bot stuck right beside its route point no longer crashes the server
  (`IndexOutOfBoundsException` in `BotTicker.detourStep`).

## 2.0.2

- **The fake tab only in the vanilla dimensions.** The invented players are shown to players in
  `minecraft:*` worlds (the recording) and taken out of the tab list of anyone who goes elsewhere
  (Hubify's hub and FFA), checked once a second.

## 2.0.1

- **Staged deaths only in the vanilla dimensions.** Death control (healed, spectator, fake leave)
  now applies only in `minecraft:*` worlds - the recording. In other mods' worlds (Hubify's FFA and
  hub) a death is a real death again, so those mods can respawn the player their own way.
- **Ranks no longer block commands meant for everyone.** The probe that decides which commands a
  rank can filter asked with a source that had no permissions at all, which even a level-0 check
  refuses. Commands every player may run (Hubify's `/hub`, `/ffa`, `/events`, `/kits`, any mod's
  level-0 command) were treated as level 1 and the default rank got "Your rank cannot use /hub."
  The probe now holds what a non-operator player actually holds.

## 2.0.0

Everything since 1.2.0, as one release. The version number jumps because the mod did: Scriptify now
contains a whole second mod, and `/sbots` is gone.

Minecraft 1.21.11, Fabric. Still server-side only - your cast joins with an unmodified vanilla
client and installs nothing.

---

### Bots

The headline. **SBots is now part of Scriptify** - one mod, one id, one jar, one command. If you were
running both, remove the SBots jar; the config and the saved bots are picked up where they are.

- **`/bots`** is the whole surface. The roster, combat AI, factions, kits, paths, scenarios and the
  control panel. `/sbots`, `/bot` and `/bots npc` are gone - the NPC commands fold into `/bots`
  itself, because which mod a feature originally came from is not something a player should have to
  know.
- **`/bots edit`** - switch it on, then hit a bot to open its panel. Naming a bot on a command line
  while looking at two hundred of them was the thing that made editing them not worth doing. Three
  labelled bands: **Settings** (hittable, collidable, crouching, shows in tab, immortal, drops on
  death), **Actions** (copy my effects, rename, change skin, randomise identity, teleport to me,
  apply a kit) and **Movement** (follow, wander, path, record).
- **Paths are drawn by walking them.** The hotbar is borrowed - emerald drops a point, red dye takes
  one back, lime dye saves, barrier cancels. A finished path is shown in the world while it is being
  walked, loops round to the first point instead of turning about and retracing itself, and is saved
  in the same file the older point-at-a-time commands use, so the two are interchangeable.
- **Recording copies you exactly** - position, look direction and crouch, sampled every tick, not
  just the route you took. A bot replaying it reads as a person rather than as something following
  waypoints.
- **A tool rack**: `/bots tools`, or Tools in the bot menu. Most of what you do to a bot you do while
  looking at it, so the Path Stick and the Kit Shield are issued from here - hold right click to
  apply a path or deal a kit to every bot around you, without typing a name.
- **A kit editor**: `/bots kit edit <kit>`, or right-click a kit in the kits menu. Real slots laid
  out like an inventory.
- **`/bots performance`** - what the bots are costing right now: count, stride, and how many of them
  thought last tick. The number to look at before blaming the server.
- `bot_*` event actions, so a scripted scene can spawn, direct and remove bots from an event file.
- HeroBot is **suggested, not required**. Without it the bot commands still exist and say plainly
  what is missing rather than failing oddly.

**Bots stopped looking like bots.** Each of these was visible on camera, which is the only test that
matters here:

- **They moved in slow motion above roughly two hundred.** Not memory - the heap was nearly empty
  throughout. Mass spawning also stalled around the same figure and only resumed when you killed
  something. Both are gone; spawning does not pause and the stride holds.
- **They left block-breaking cracks over the scenery mid-fight**, because the attack action doubles
  as a mining input.
- **Stuck in a cobweb, they mined the ground** - aiming at the centre of the block they were standing
  in rather than at the web - and they did it **with their fists**. A sword shears cobweb about
  fifteen times faster. Both fixed, and a bot with a water bucket now pours it to wash the web away
  instead.
- **The web bubble placed one web**, which is a cocoon with no walls and does nothing.
- **A bot in a one-block pit stayed in it forever**, walking into the wall. It digs or builds out now.
- **They pearl out before healing** rather than after failing to. Anything but a splash potion locks
  you in place while you drink it, which is how a bot died mid-heal in front of the camera.
- Bots walking a bhop path sailed over their points and never registered arriving at one.
- Paths stopped at a restart and never resumed: the routes were saved and the walkers were not.
- **The scenario prompt flickered and outlived its scenario.** Nobody owned the action bar.

---

### Ranks and permissions

New in this release, and rebuilt twice since.

- **Ranks** are named permission bundles assigned per player. Ships with `default`, `crew` and
  `director`, each carrying its own Scriptify nodes, prefix and colour. A player with no rank counts
  as `default`. Ranks stack with individual grants rather than replacing them.
- Ranks also configure **vanilla** commands - a permission level, and an allow-list or deny-list.
- `/scriptifyranks`, or the GUI from the permissions screen. `list` reports each rank's command level
  and filter, `level` and `filter` set them, `prune` clears rows left by older builds.
- **`/scriptifyranks check <command> <player>`** explains, in order, everything that decides whether
  that player can run that command - including whether **vanilla agrees**, asked through the player's
  own command source rather than inferred.
- Permissions and ranks are **one screen**. There were two and they overlapped.

Fixed along the way, and these were real:

- **Editing a rank appeared to do nothing.** Minecraft sends each player a filtered command tree at
  login and does not resend it, so a rank change did not reach anyone already connected. It
  republishes now - and granting or removing a rank's Scriptify node republishes too, which only the
  command settings used to do.
- **"Only these commands" was not a restriction**, because the list it filtered came from the wrong
  source.
- **Every vanilla command a rank granted was silently thrown away**, in the case the earlier fix
  missed.
- **Bots were being given ranks.** Every scene bot was handed the default rank as it joined and filed
  away forever; `prune` dropped 4,933 rows on the test server. A bot given a rank by an older build
  cleans itself up when it reconnects.
- **`/bots` needed a permission.** It was registered as `requires(s -> true)` - every player on the
  server could drive the bots.

---

### Identity

- Your own skin is copied and stored the first time Scriptify overrides it, so `/identity clear` and
  `/skin clear` put back what you actually had. It could restore the wrong one.
- A second, direct route to Mojang's public API, used when the server's own session service comes
  back empty.
- **Random names read like usernames now.** This took four attempts and the earlier three were
  looking at the wrong generator. They are shorter and plainer, built from a sample of real accounts
  rather than a fantasy bestiary; the same name cannot appear twice; nothing is cut off mid-word at
  sixteen characters; and no shape clusters into one visible block in the sorted tab list.
- The same account could be handed out twice in a session.
- NameMC's random-skins page now answers 403 and its names page renders through obfuscated markup;
  both are handled.

---

### Scenes

- **Proximity chat.** Players only hear people within a set distance, so a conversation in a base
  stays in the base. Ranges of 8, 16, 48, 128 and 256 blocks, or type an exact number. Set in the
  GUI. Only player chat is limited - private messages, deaths, joins, leaves and `/say` still reach
  everyone, and another dimension is out of earshot at any range.
- **A fake tab list.** Pad the tab with players who are not there, so a small server reads as a busy
  one.
  - Every padded entry has a **face**. They were all default Steves, which is worse than no tab list
    at all on camera.
  - **The roster drifts.** A tab list that is byte-for-byte identical for a whole shot is the tell.
    Names turn over at a rate that scales with the list - a row per fifty players per event, as a
    swap, so the count stays where you set it - and latency bars twitch on their own three-second
    clock. Measured on the running server: five hundred players turn over ten rows a second.
  - A fake player could be handed the name of somebody actually connected.
  - The invented names stay **out of command autocomplete**.
- `/villagermaker` - rename a villager, build trades from your inventory, remove any single trade.
- `/clearground [radius]`.
- Four fill presets out of the box - **survival**, **armory**, **market** and **vault** - and fill
  categories open into a list of their items.
- Singleplayer and LAN worlds are supported.

---

### Kits

- `/kitted` replaces `/skits`. `/kitted <player>` opens the menu aimed at somebody else - every kit
  you click goes to them.
- Kit names take spaces and capitals; quote them on a command line.
- Right click a kit for its own screen: equip, give to, edit contents, replace from inventory.
- Edit a kit in place, with real slots laid out like an inventory.
- Kits are ordered oldest first, so a new one lands at the bottom rather than jumping into the
  middle of the grid. A page holds 27.
- A kit still being dealt out no longer overwrites `/copyinv` or an open `/invsee` a tick later.

---

### Interface

- `/scriptify help` is a landing page - six ways in, each clickable, with hover hints - and the house
  style applies everywhere rather than to that one screen.
- Menus have a plain grey border and nothing behind the content. Toggles are grey for off and green
  for on; the enchanted-glint trick is gone.
- Holding a mouse button on a toggle flickered it on and off, leaving the setting wherever the last
  repeat landed.
- Button names and menu titles are bold, so a label reads as a label.
- Enchantments are shown by what they do, not what they go on.

---

### Compatibility

- **Mod conflict detection.** Scriptify finds mods that want the same things it does and settles who
  gets what, per surface rather than per mod - so yielding nicknames to a nickname mod leaves skins,
  kits and ranks alone. `PRIORITY`, `YIELD` or `IGNORE` for nicknames, skins, tab list, permissions,
  death and commands, plus `commandOverrides` for a single command and `ignoredMods` to silence one.
- Command-name collisions are found by **watching the command tree**, so a mod published tomorrow is
  caught without Scriptify knowing anything about it.
- `/scriptify compat` reports what was found and who won each surface.
- **New here:** the table knows about Simple Voice Chat, Carpet, ServerReplay, ViaVersion, Polymer,
  C2ME, Moonrise, Lithium and the live-map mods.
- **New here:** mods that are simply getting on with it are reported as notes rather than warnings.
  Warning about Lithium on a server where Lithium is working is how a log gets skimmed.
- **New here:** Scriptify stopped reporting *itself* as a conflicting mod. `/bots` is built by three
  separate places, and the watcher counted the second one as somebody else - a warning on every
  start, naming a mod that was not installed.
- **New here:** `/scriptify compat` separates "extending a vanilla command" from "another mod wants
  this". `/item` was listed under a warning triangle on every clean server.
- **Carpet is no longer refused outright.** The mod declared it as breaking and never said why; two
  of the three ids were misspelled and so had never matched anything. Carpet's `/player` bots are
  recognised as bots, and the overlap is now reported rather than used to stop the server booting.

---

### Fixed

Beyond the sections above:

- **Players sometimes invisible to other players until they relogged.** The longest-running bug in
  the mod and several separate defects. A client snapshots a player's profile when it first spawns
  that entity, so changing a name or skin means rebuilding the entity for everyone who can see it -
  and the tracker has to be driven rather than worked around, or the server ends up believing clients
  have state they do not. A refresh with nobody in range no longer hand-spawns the player for
  everyone in the world, a refresh that the tracker declines is reconciled for the next couple of
  seconds instead of being dropped, and a server whose chunk system belongs to another mod falls
  back to plain packets and **says so in the log** instead of returning silently.
- **A held totem was ignored when the holder was one-shot**, reported with TNT minecarts and mostly
  on bots. The totem check now runs ahead of every branch that could cancel the death.
- **The XP bar came back empty** after a staged death, a skin change or a vanish, until you relogged.
- **`/randomfill` only filled half a large chest.**
- **The suggestion popup stopped appearing for `/tp`** - and only `/tp`, for a reason worth reading
  the 2.10.5 note about if you care.
- **Config changes were never saved on Windows.** The write goes to a temporary file and is renamed
  over the real one; on Windows that rename fails with `AccessDeniedException` whenever anything
  holds the file open for a moment, which on a normal desktop is routine. Only
  `AtomicMoveNotSupportedException` was being caught, so every failure went straight to the error
  handler and the save was lost - one line in the log at startup, nothing at the moment of the edit,
  and every GUI change reverted at the next restart. Any failed rename now falls back and retries.
- **`/scriptify version` reported the wrong version.** It was a hand-maintained constant and it said
  2.3.0 in a build that was seven releases past that, so every bug report carried the wrong number.
  It reads the jar's own metadata now.
- **LuckPerms failures were silent.** If the bridge could not reach LuckPerms, every permission check
  quietly fell through to Scriptify's own file, which looks exactly like LuckPerms being ignored for
  no reason. It says so now, once. The bridge also resolved eight methods reflectively **on every
  permission check** - on the path Brigadier walks for every command node for every player. Resolved
  once now, and against LuckPerms' public interfaces rather than its implementation classes, which is
  a call that can fail on somebody else's server and never on yours.
- A bot hidden from the tab list reappeared for anyone who joined afterwards, permanently.
- Bots left behind in the no-collide scoreboard team when removed.
- Group lookup was quadratic in the roster, and resolving a bot's settings allocated up to three
  objects per bot per tick.
- Vanish could leave a player half-hidden on a server whose chunk system belongs to another mod.
- Fake Tab was unreachable from the main menu - it shared a slot with Death Control.
- 1.3.2 did not start at all.

---

### Breaking changes

Read this part if you have command blocks, macros or scripts.

- **`/sbots` and `/bot` no longer exist.** Everything is `/bots`. `/bots npc <x>` is now `/bots <x>`.
- **`path start` takes the path first**: `path start <path> [bot]`. With no bot it hands you the Path
  Stick instead.
- **`/skits` is `/kitted`.**
- **`/immortal` toggles** rather than taking on/off/set.
- **`/bots` now requires a permission.** On a server where players were using it, they will stop
  being able to until you grant `scriptify.bots`.
- **Minecraft version range narrowed to 1.21.11 and later 1.21.x.** It previously declared support up
  to 26.2, which it cannot honour - the mod is built against 1.21.11 mappings and would fail its
  mixins on a version that far ahead. Declaring a range you cannot meet turns a clear "this mod does
  not support your version" into a crash report.
- **Carpet is no longer declared as breaking**, so a server with Carpet installed will now start.
- **Bot config moved from `config/sbots/` to `config/scriptify/bots/`.** One jar with two config
  directories is two mods wearing a coat. The old folder is moved across automatically on first
  start, so factions, paths, kits and settings carry over; if the move fails the old folder is left
  untouched and the log says so.
- **Supported Minecraft version is now declared as exactly 1.21.11**, which is what it was built and
  tested against. It previously claimed up to 26.2, which it could not honour.
- **`THIRD-PARTY-NOTICES.md` is new**, and ships inside the jar. It records that the bot half is a
  fork of PvP Bot by Stepan1411 (Unlicense, public domain), that sgui by Patbox is bundled under
  LGPL-3.0, and that the player-refresh sequence is adapted from SkinRestorer by Lionarius (MIT),
  whose notice is reproduced in full as that licence requires.
- **The licence is now MIT**, where 1.2.0 and earlier were CC0-1.0. In practice that means one added
  obligation: keep the copyright notice and the licence text with any substantial copy of the source.
  Using the mod, running it on a server and shipping it in a modpack are unaffected. CC0 cannot be
  withdrawn, so anything taken from a build at or before 1.2.0 stays public domain.

---


## Development builds

Everything below between 2.10.5 and 1.2.1 was an internal build. They are consolidated into 2.0.0
above and kept here because several of them explain *why* something is the way it is, which the
summary has no room for. The numbering ran ahead of the published one - 2.0.0 is the first release
since 1.2.0, not the twelfth.

---

## 2.10.5

**Fixed**
- **`/tp` had no suggestion box at all, and only `/tp`.** Caused by the fix that keeps the invented
  names out of autocomplete, and the reason it was that one command is in the client:

  ```java
  public CompletableFuture<Suggestions> customSuggestion(CommandContext<?> context) {
      if (this.pendingSuggestionsFuture != null) {
          this.pendingSuggestionsFuture.cancel(false);
      }
      ...
  ```

  The client can have exactly one question outstanding. Brigadier asks *every* child of a node for
  suggestions and then waits for all of them, so marking two children of the same node
  server-answered is not twice as good - the second question cancels the first, the combined wait
  never finishes, and the box is never drawn.

  `/teleport` is the only vanilla command that takes two entity arguments in the same position: a
  destination, or a list of targets. `/tp` redirects into it. Everything else - `/msg`, `/kill`,
  `/gamemode` - has one or none there, which is exactly why they all kept working while `/tp` alone
  went silent.

  Nodes that have an entity-argument sibling are now left as vanilla built them. Measured against the
  live tree, that is precisely two nodes on the whole server - `targets` and `destination` under
  `teleport` - so every other command keeps asking the server and keeps its list clean.

  **The trade-off, stated plainly:** the invented names are offered again for `/tp`, and only for
  `/tp`. That is the thing that started all of this, so it is not a good answer - but a wrong
  suggestion can be ignored and a box that never appears cannot be worked around, and there is no
  third option that does not involve rewriting what type that argument claims to be on the client.
  Say the word and I will do that one properly.

---

## 2.10.4

**Fixed**
- **The big rosters still barely moved, and speeding them up was never going to fix it.** An event
  turned over exactly one row no matter how long the list was. At five hundred that is two tenths of
  one per cent of what somebody is looking at, and a tab list is taken in whole rather than read row
  by row - so one name quietly changing among five hundred is not a small change, it is an invisible
  one. Twice now I tuned the rate and left that alone.

  Statistically the old numbers were right: a five-hundred-player server really does turn over about
  one player every two seconds. But the point of this is not to be a simulation, it is to look alive
  on camera, and what has to stay roughly constant for that is the *share* of the list that moves,
  not the count. So the batch scales with the roster too, and the two multiply.

  An event now turns over one row per fifty players, and it is a swap - the same number out and in -
  so the list churns hard while the number at the top stays where it was asked to be. Population
  drift is now a separate, deliberately small thing on top of that, worth at most one player per
  event.

  Measured on the running server: **five hundred players turn over ten rows a second** with the count
  pinned at exactly 500, two hundred turn over two rows a second, forty turn over one row every
  fifteen seconds and fifteen one row every forty. The previous build managed a tenth of a row per
  second at five hundred, so this is a hundredfold at the top and untouched at the bottom.

  Sent as one removal packet and one addition packet per event, not one packet per person: sixteen
  people leaving individually is thirty-two packets the client applies over as many frames, which
  arrives as a stutter rather than as something happening.

**Changed**
- **Latency bars moved onto their own clock.** They used to be one churn event in five, which tied
  the cheapest movement available to the most expensive one. Now a tenth of the list is repinged
  every three seconds regardless - vanilla refreshes every player's ping every thirty seconds in one
  go, so this is the same rate spread out, and it reads as bars twitching rather than the whole
  column jumping at once. Costs a uuid and a number per entry.

**Worth knowing**
- A turnover carries whole profiles, skin included, so five hundred players works out at roughly ten
  kilobytes a second to each person actually connected. That is nothing for the handful of real
  players a scene has, and worth knowing before setting five hundred on a server with a hundred
  genuine ones. Turning drift off in the fake tab menu stops it entirely.

---

## 2.10.3

Skin lookups now follow the shape SkinRestorer uses (MIT, Lionarius), which is worth saying plainly:
its reliability is not a better endpoint, it is refusing to stop after the first source says no.

**Fixed**
- **A skin lookup gave up after one source, unless that source threw.** The fall-through to the direct
  Mojang API only ran in a `catch`. An authlib call that simply *returned nothing* - which is what a
  throttled or transiently failing lookup does - ended the whole attempt, and the player was told
  there was no such account. Nothing at that layer distinguishes "no such player" from "not right
  now", so treating the two the same is the bug.

  This is not theoretical: while testing, Mojang's own name endpoint answered `429 Too Many Requests`
  for a name that plainly exists, after nothing more than a couple of dozen lookups. Under the old
  code that was a failed command.

  Every source is now tried in turn, and an empty answer moves to the next one rather than ending it.
  A name nobody has costs two extra requests; a name that was one bad response away from working now
  works.

**Added**
- **Ely.by as a third place to ask.** An alternative skin service with its own accounts, publishing
  through an endpoint deliberately shaped like Mojang's session server - same JSON, same properties
  array - so the same parser reads both. Two reasons it earns a place: it is not behind Mojang's rate
  limit, and it has a different population, so a name that is nobody on Mojang can be somebody there.

  Asked last. Its signature is its own rather than Mojang's, because only Mojang can sign for a Mojang
  account, so a genuine Mojang answer is always preferred when there is one. Over HTTPS, where
  SkinRestorer uses plain HTTP - the payload is public either way, but the host serves both and there
  is no reason to ask in clear.

**Changed**
- **Mojang's address is asked for rather than written down.** The two hosts were hardcoded, which is
  wrong on any server started with `-Dminecraft.api.session.host` pointed elsewhere - every
  offline-mode setup behind an authentication proxy does exactly that, and the lookup was going to the
  public address regardless. Authlib already knows where it was told to look, so it is asked, falling
  back to the production hosts so the ordinary case is unchanged.

**Not changed, and worth being clear about**
- None of this lifts the fake tab's face ceiling. SkinRestorer looks up a *known* player's skin, and
  everything that makes it dependable - caching, provider fallback - is about getting one answer for
  one name. It has no notion of a random skin, so there is nothing there to copy for the half of the
  fake tab pool that has to *discover* accounts. That is still bounded by how fast Mojang will answer
  bulk profile lookups, which is what stopped the pool at three hundred.

---

## 2.10.2

**Fixed**
- **The bigger the roster, the more frozen it looked.** The gap between churn events is the tick
  budget divided by the roster size, which is the right shape - it is one population turning over, so
  a bigger one turns over faster. Then a floor of ten seconds was clamped on top of it, and every
  roster above about a hundred and twenty players hit that floor. Two hundred and five hundred churned
  at exactly the same speed, and the sizes that should have looked busiest were the ones that sat
  still. The scaling was written and then thrown away by its own safety valve.

  The floor is now low enough that the division is what decides. The budget came down as well, to an
  implied session of about half an hour, which is what a network of that size actually turns over at.
  Fifteen players is an event a minute; forty is one every twenty seconds; five hundred is one every
  two.

  Measured by sampling the roster once a second: at five hundred the population moved on ten of
  nineteen gaps, and at fifteen it did not move once in twenty seconds. Before this, five hundred
  barely moved across forty-five-second samples.

**Changed**
- **The face pool scales with the roster instead of being a flat sixty-four.** Sixty-four was fine at
  the sizes I checked it at and wrong at the top: five hundred players wearing sixty-four faces is
  every face eight times over, which is exactly the repetition a tab list gets scanned for. It now
  collects roughly one face per entry - a floor of sixty-four so that churn on a small roster is not
  handing back the face that just left, and a ceiling of three hundred where the lookups stop being
  worth it. Five hundred players now repeat under twice instead of eight times.

  The extra faces cost background time on a fresh install and nothing afterwards, since the pool is
  written to disk. A fill that runs out of budget simply stops and the next one carries on from what
  is already saved - which is what happened here: three hundred faces arrived over two passes, a
  hundred and thirty-seven then ninety-nine, and the file survives a restart in between.

- A fill now gives up after forty consecutive lookups that produce nothing. Not every real account
  has ever set a skin, so a lookup is not a face and a barren stretch is normal - but forty in a row
  is not luck, it is Mojang throttling, and a throttled request fails the same silent way a skinless
  account does. One measured run spent seven minutes at full pace to collect what the first minute
  had already shown it would not get. Stopping costs nothing, because everything found is on disk
  and the next fill continues from there.

- New faces now reach a roster that already exists. The repaint at the end of a fill only ran on a
  pool that had started empty, so raising the size to something the old pool did not cover left the
  new entries wearing the old handful of faces until churn happened to rotate each one. It now runs
  whenever a fill actually found something, which is only ever during setup - nothing starts a fill
  on a pool that already covers the roster.

---

## 2.10.1

**Fixed**
- **Fake players showed up in command autocomplete, and suggestions felt broken afterwards.** One
  cause, two symptoms, and it was not where I looked the first two times.

  A client does not ask the server who is online when you type `/tp ` - it answers from the player
  list it has been sent, which is exactly where the invented names live. Since 2.5.0 the fix has been
  to mark player-shaped arguments as server-answered while the roster is showing, and that part was
  working: measured on a live server, all twelve player arguments in the tree were rewritten when the
  roster was on, none when it was off, and the server's own answer to `tp ` came back as
  `@a @e @n @p @r @s <the one real player>` - not one invented name in it.

  The hole was that **the command tree is built once, when a player joins.** Nothing rebuilt it when
  the roster was switched on or off, so the flag changed and every client already connected carried
  on with the tree it was handed at login. Turn the roster on mid-scene and everyone online kept a
  tree that still answered locally - so `/tp ` went on offering forty people who are not there, for
  the rest of their session. Turn it off and they kept the other tree, asking the server about every
  player argument for no reason at all, which on a remote server is a visible pause before the
  suggestion box appears. That is the half that read as autocomplete being broken.

  The tree is now resent when the answer changes, which lands both symptoms at once: switching the
  roster on takes the invented names out of autocomplete immediately, and switching it off puts
  suggestions straight back to plain vanilla instead of leaving people on the slow path.

  Only on an actual change, not on every call - going from forty to eighty does not change how
  suggestions work and is not worth a command tree each. Verified by watching the tree get rebuilt
  for a player who was already connected: on at forty, twelve arguments rewritten; forty to eighty,
  no rebuild at all; off, twelve arguments back to vanilla.

  This was written up as a known trade-off in 2.7.2 and left unfixed. It should not have been - it
  was the whole reason the thing was still being reported.

---

## 2.10.0

**Added**
- **The fake tab list has faces.** Every padded entry was a default Steve, which is worse than no
  padding at all: the heads are the first thing anybody looks at, before a single name is read, and
  a column of forty identical ones reads as a bug rather than as a busy server. Entries now wear
  real, signed skins.

  Kept as a pool of sixty-four faces rather than one each. Five hundred distinct skins is five
  hundred Mojang lookups, and there is no version of that which is not either a long stall or a
  burst of traffic that gets the server rate-limited. At the sizes people actually film with -
  fifteen, forty - the pool covers the roster outright; on a large one the faces repeat, and nobody
  scrolls a tab list comparing eight-pixel heads.

  Skins of players who are actually connected are deliberately not used. They are free and local and
  it is tempting, but a real player seeing their own face on a stranger three rows down gives the
  whole illusion away, and on a small SMP with four people online their faces would be most of the
  tab list.

  The pool fills in the background, paced rather than fired off together, and everything it finds is
  written to `skins/faketab-skins.json` - so the wait happens once ever rather than once per restart.
  A roster built while the pool was still cold is repainted as soon as there is something to paint
  it with. Only signed textures are kept: a client handed a texture value without its matching
  signature falls back to the default skin, which is the thing this exists to avoid.

- **The roster drifts.** A tab list that is byte-for-byte identical for a whole shot is the thing an
  audience notices without being able to say why. Every so often one thing happens: somebody leaves,
  somebody arrives, or a handful of pings move.

  One thing, deliberately - a real server's tab list does not reshuffle, it loses a name and some
  minutes later gains one. The gap between events scales with the roster, because churn on a real
  server scales with how many people are on it: about eighty seconds apart at fifteen players,
  thirty at forty, ten at the largest sizes, each jittered by half so it is not a metronome.

  The population stays near the number that was asked for rather than wandering off it. Somebody who
  sets forty wants a server that looks like forty, so it is allowed six per cent either way - two
  players at forty, twelve at five hundred - and the odds lean back towards the middle whenever it
  drifts. One event in five moves a few latency bars instead of anybody coming or going, which is
  the cheapest thing on the list and the one that sells it best.

  Off is one click, in the fake tab menu or `fakeTab.churn` in the config, for a scene that has to
  match a screenshot exactly or where a name is read aloud and must still be there later.

**Fixed**
- A fake player could be handed the name of somebody actually connected. The roster checked itself
  for repeats but not the real player list, and a stranger wearing your name three rows down is a
  collision you notice, because you are standing right there.

---

## 2.9.1

**Fixed**
- **Players sometimes invisible to other players until they relogged.** Two defects, and they only
  bite together on a server whose chunk system has been replaced - which yours has, by C2ME.

  Refreshing a player after a name or skin change means recreating their entity for everyone who can
  see them. The preferred route drives the vanilla entity tracker, and that route is watched
  afterwards for two seconds, because the re-track can legitimately be declined while an observer's
  chunk is still queued. **The fallback route had no watchdog at all.** It sent one despawn-respawn
  by hand and stopped. A spawn packet for an entity in a chunk the client has not been sent yet is
  simply dropped, and neither side knows: the server believes it spawned them, the client has
  nothing there, and vanilla only revisits an entity when it changes section - so somebody standing
  still stayed invisible until they reconnected. That fallback is the route a C2ME server takes,
  every time. It is now watched exactly like the tracker route, re-sending twice across the window
  rather than every tick, which would be a visible flicker.

  The fallback also sent the respawn to **every player in the dimension** regardless of distance,
  spawning a copy of the player for people thousands of blocks away - entities the real tracker has
  no record of and so will never remove. The tracker route already refused to do this; the fallback
  was still doing it. It is now limited to players within view distance.

- The tracker being unavailable was a silent return, so a log gave no way to tell which of the two
  routes an install was using. It now says so once, on the first refresh that needs the fallback:
  `Entity tracker is owned by another mod (a chunk-system rewrite such as C2ME); player refreshes
  will use the packet fallback.`

---

## 2.9.0

**Changed**
- **Bots are named by the shared generator when they spawn.** The spawn names came from a list
  written out by hand - AetherClaw, BlazeRunner, NetherScribe, VoidCarver - stitched to a prefix and
  a suffix. A hundred and fifty invented fantasy compounds is a lot of typing and still reads as a
  lot of typing: spawn thirty and the tab list is obviously a cast list, because no real server has
  thirty people whose names were written by one person in one afternoon. They now come from the same
  generator as everything else, so a bot arrives already looking like somebody who plays here.

  Before: `AetherClaw`, `GazelleWalker`, `KrakenBlack`, `NecroWeapon`, `CardanoPot`.
  After: `bouchi5909`, `adam_8659`, `NitroRush`, `artem19`, `omni_cell`, `pixel_zacron`.

  The special-names list is untouched - that one is filled in deliberately and is meant to be
  recognisable.

**Fixed**
- **Paths stopped at a restart and never resumed.** The routes were saved and the walkers never were,
  so the server came back with every path still in the file and nobody on any of them. Who is walking
  what is now written beside the routes, in its own file, and read back at startup - after the routes,
  so an entry whose path has since been deleted is dropped rather than resurrecting a route that no
  longer exists.

  Nothing has to be restarted by hand afterwards. The ticker asks each bot whether it is following
  something, so a bot that reconnects into a restored entry simply carries on from the point it had
  reached. Progress is written at shutdown rather than at every point reached, which would be a disk
  write per bot every few seconds for a number that only matters once.

  A pause point and a combat flag are deliberately not restored: both are about a moment that ended
  when the server did, and bringing them back would have a bot resuming a fight that is not happening.

  Verified across a real restart - `Resuming 2 bot(s) on their paths`, and stopping one afterwards
  reported `stopped following path`, so the restored state is live rather than just a file entry.

---

## 2.8.2

**Fixed**
- **The word names were coming from a different place than the one I had been fixing.** 2.8.0 and
  2.8.1 rebuilt the local name generator, which drives ``/randomnick`` and the fake tab roster. The
  names that were actually filling the server came from ``/randomidentity`` - the ''skin and all''
  path - which adopted a **real Mojang account** wholesale, name included.

  Those names are genuine. Hawk, Dragon, Fable, Jester, King, Titan, Savage, Anvil, Mantis and Sable
  are all registered accounts; that was checked, ten out of ten. That is exactly the problem: the
  names still findable on Mojang are the ones claimed around 2011, so a server dressed in them reads
  fifteen years old. Nobody playing today is called Hawk, because everything short was gone long
  before they arrived.

  The two halves are split now. The **name** comes from the local generator, modelled on what a
  current server list actually looks like; the **skin** still comes from a real account, because a
  real skin is the half that cannot be invented. Nobody can tell the skin belongs to somebody else -
  there is nothing on screen to compare it against - whereas everybody can tell a tab list looks
  like 2011.

  Before: ``NoEndless, Mantis, Anvil, Savage, Titan, _dusty, Void_``.
  After: ``brydooss27, plavro45, dmitri8585, mrCelik, chihoung, Sloocest, Latwa, Triclum96``.

**Removed**
- ``NickManager.randomRealAsync`` and ``SkinManager.findRandomRealNameAsync``, which nothing called.

---

## 2.8.1

**Changed**
- **The name pools were a fantasy bestiary, not a username list.** 2.8.0 reweighted the shapes and
  left the vocabulary alone, which fixed the proportions and not the problem: the pools are Phoenix,
  Dragon, Warden, Viper, Boulder, Lantern, Panther, Brook, Canyon, Ash, so the output was
  ``VoidBoulder``, ``MutedPanther``, ``SuperArcticViper``, ``Phoenix_`` and ``Dragon_`` - RPG
  characters rather than people on a server.

  An eighty-name sample from a live server contains **not one bare dictionary word**, and the few
  compounds in it are modern rather than mythic: DopplerRuby, FeatherNova, CyberDienst, BioNet_,
  CloudSp4rk_. So compounds are built from a modern word list now, bare single words are gone
  entirely, and two registers that make up most of a real list were added:

  - **invented letter strings**, now the largest group at around 40% - Sythne, Wasov, Rioftaba,
    Kashellow, kuptrax, slofta;
  - **real given names with a number after them**, which the old fifty-name English pool could not
    produce - josifa67, lukicha19, celik_385, matibezgaci2137, Benienas2, _Biagii.

  The fantasy pools are kept, but only for ``generateLikelyTaken()``, where the job is to guess names
  that already exist on Mojang and an ordinary English word is exactly what does that.

  Measured over 500: no bare dictionary words, no duplicates, nothing over sixteen characters, and
  leading underscores at 2%.

---

## 2.8.0

**Changed**
- **The fake tab roster now looks like a real server's player list.** Measured against the old
  generator, 43% of a 140-name roster read as a nickname rather than a username and 18% were
  blatant about it - one in seven was an ``xX_Hawk_Xx``, an ``X_iron_X``, a ``Sc4rl3tCr4n3`` or a
  ``FROZZEN``. The shapes had been sampled from NameMC's popular-profile listings, and that is the
  flaw: popular profiles are memorable ones, and memorable means flashy. A server's tab list is the
  opposite - mostly forgettable.

  Reweighted against an actual 80-name list from a live server. ``xX_..._Xx`` and ``X_..._X`` are gone
  entirely (there were none at all in the real sample), leading underscores drop from a fifth of the
  list to 3%, and the registers that were missing are in: vowel-dropped handles (``Blxqswt``,
  ``rlxxe``), three and four digit runs (``matibezgaci2137``), single-digit leet (``Ch1llguy7``),
  mid-name underscores (``black_blade_``), phrases (``LearnHowToMacro``) and stretched letters
  (``Adzzzzzzzzzzz``).
- **Invented names are shorter and plainer.** They were built from vowel pairs given equal billing
  with plain vowels, and two landing in one word produced ``Blaequauz``, ``Choocliem``,
  ``Craifroesh`` - Elvish, not Minecraft. Real invented usernames are short and plain: Sythne,
  Wasov, slofta, Durmaz. Vowel pairs are now the exception and the length cap came down from ten to
  eight.

**Fixed**
- **The tab list showed the same name twice** - two ``Knight``s, two ``Golem``s. The generator
  remembers its last ninety-odd names, which is not enough for a roster of several hundred, so past
  that it hands back ones it has already given. The roster is deduplicated as it is built now, which
  is where the repeat is actually visible.
- **Names were cut off at sixteen characters mid-word**, producing ``theancientsleepy`` and
  ``heyrestlesscedar`` - which read as a bug rather than as somebody's handle. Only trailing digits
  and underscores are trimmed now; anything still too long is rolled again.
- Every shape with a fixed prefix clustered into one visible block, because the tab list sorts
  alphabetically - thirteen ``_Name`` entries in a row at the top. That is now three per 140, which
  is what the real list shows.

---

## 2.7.2

**Fixed**
- **The suggestion popup stopped appearing for `/tp`.** 2.5.0 stopped the fake tab roster showing up
  in command autocomplete by marking every player-shaped argument "ask the server", and applied that
  to every server whether or not a roster existed. The server answers those requests correctly - it
  returns the right names, checked - but the change moves the popup from something the client draws
  immediately to something it waits on, and that is a real change in behaviour to impose on a server
  with no invented players to hide.

  It is now only applied while the roster is actually showing. With the fake tab off - the default -
  the command tree is left exactly as vanilla built it and suggestions behave as they always did.
  With it on, the trade is the one that was always intended: the fakes stay out of autocomplete.

  The tree is built when a player joins, so switching the roster on mid-session reaches somebody at
  their next reconnect, or immediately if a rank is edited, since that resends the tree.

---

## 2.7.1

**Fixed**
- **Every vanilla command a rank granted was silently thrown away.** 2.7.0 fixed the case where the
  level was never set; this is the reason it did not work even when it was. The rank's level was
  merged onto the player with `PermissionSet.union`, which is not what its name says for these sets:
  `LevelBasedPermissionSet` overrides it as
  `this.level().isEqualOrHigherThan(other.level()) ? other : this`, which returns the **lower** of the
  two levels. Both the player's own permissions and the rank's grant are level based, so unioning
  them handed back whatever the player already had - level zero - and discarded the grant entirely.
  Every rank granting a command level did nothing at all, for every vanilla command, and it failed
  silently because the call looked exactly like the right one. The two levels are compared directly
  now and the higher wins. Anything not level based gets a real "either one allows it".
- `/scriptifyranks check` now reports whether **vanilla agrees**, asked through the player's own
  command source. That line is what found this: Scriptify said "allowed" and vanilla said no, and a
  disagreement between the two is worth being able to see rather than deduce.

---

## 2.7.0

**Fixed**
- **A rank set to "only /tp" still could not use /tp.** A rank had two controls doing two different
  jobs: the *level* decides which vanilla commands exist for a player at all, and the *list* only
  narrows what is left. Listing /tp while leaving the level at zero therefore narrowed an empty set
  down to nothing - and the screen said the rank had /tp. Naming a command in an "only these" rank
  is how somebody says "this rank may use it", so the level that command needs is now granted with
  it, worked out by asking the command itself rather than from a table. Listing /tp grants exactly
  what /tp costs: /give at the same level stays blocked by the filter. "All except" is untouched -
  a listed command there is one being taken away, so granting it would be backwards.

**Added**
- **`/bots` now needs a permission.** It was registered as `requires(s -> true)`: every player on the
  server could spawn five hundred bots, edit them, or remove them all. It answers to
  `scriptify.bots` now - the node that already existed and that until now only the bot event actions
  checked. Operators and the console are unaffected, so nothing an admin does changes; a non-op crew
  member needs the node granting, with
  `/scriptifyranks node <rank> scriptify.bots true`.
- **`/scriptifyranks check <command> <player>`** explains, in order, everything that decides whether
  somebody may run a command: their rank, what the command costs, what the rank grants, the filter,
  and the verdict - with the command that would fix it. Written because working that out by hand
  meant holding a level, a filter and op status in your head at once.

---

## 2.6.1

**Fixed**
- **Bots were being given ranks.** Every scene bot was handed the default rank as it joined and filed
  by name, so the rank system filled up with extras nobody had cast - this world had **2,466** of
  them - and each join rewrote the rank file, which by then was 281 KB. A five hundred bot mass spawn
  therefore wrote a growing quarter-megabyte file five hundred times. Ranks are for people now: a bot
  is refused at every entry point, the Players picker on the Access screen lists real players only,
  and a rank change no longer resends a command tree to bots, which have no client to redraw one.
- A bot given a rank by an older build cleans itself up when it reconnects.

**Added**
- `/scriptifyranks prune` clears rows left behind by older builds - it dropped 4,933 here and took
  the file from **281 KB to 1.4 KB**, with every rank left intact. It only removes rows that carry no
  information: somebody on the default rank with no exceptions of their own, which is the state an
  unassigned player is in anyway and is written back the moment they next join. Anyone on a real rank
  or holding an exception is left alone whether or not they are online. Picking bots out directly
  would be the obvious approach and is not safe - on an offline-mode server a real account's id is
  built the same way a bot's is, so once they are offline there is nothing to tell them apart by.

---

## 2.6.0

**Fixed**
- **Editing a rank appeared to do nothing.** This was the big one. Minecraft filters the command tree
  by permission level *once*, when it builds the tree to send a player, and it only rebuilds that at
  login or when somebody is opped. Scriptify updated everything on the server correctly and then
  never told the client, so raising a rank's command level did not make `/gamemode` appear until the
  player reconnected - and lowering it left commands listed that would then refuse to run. Both read
  as the rank not having saved. Any change to a rank now resends the command tree to everyone holding
  it, which costs one packet and only happens when somebody edits a rank.
- **"Only these commands" was not a restriction.** The commands a rank could filter came from a
  hand-written list of twenty-five names, and everything left off it was permitted regardless -
  including `/execute`, which by itself runs anything, plus `/data`, `/scoreboard`, `/say` and about
  fifty others. The list is now worked out from the live command tree, so it is complete by
  construction and picks up other mods' commands too: **79 commands on this build, against 25
  before**. Two things stay out of it on purpose - Scriptify's own commands, which answer to feature
  nodes, and commands everybody can already run without permission, since a rank grants access rather
  than taking away what a player had before they had one.
- Granting or removing a rank's Scriptify node now republishes as well; only the command settings did
  before, so a node change did not reach anyone until something else happened to trigger a publish.

**Changed**
- **Permissions and ranks are one screen.** There were two, and they overlapped: permissions opened a
  player picker and granted nodes one at a time, ranks was a separate command with its own list, and
  the rank screen's back button led into the permissions picker - so each behaved as though it were
  the top of the tree and neither said how it related to the other. **Access** replaces both, from the
  bot menu or `/scriptifyranks`. Ranks are the body of it because that is what should be edited; a
  single Players door leads to one screen showing a person's rank *and* their exceptions together,
  and their rank can be changed from there instead of being sent off to a member roster.
- `/scriptifyranks list` reports each rank's command level and filter, not just its node count - a
  rank that grants no nodes but raises the level is a normal thing to set up and used to read as
  empty. It also says how many commands a rank is able to filter.

**Added**
- `/scriptifyranks level <rank> <0-4>`, `/scriptifyranks filter <rank> <all|only|except>` and
  `/scriptifyranks command <rank> <command> <true|false>`. The vanilla-command half of a rank was
  reachable only through the menu, so it could not be scripted, put in a command block, or checked
  without opening a screen.

---

## 2.5.0

**Fixed**
- **The fake tab roster showed up in command autocomplete.** Type `/tp ` and there were the invented
  names, offered as if you could teleport to them. Nothing had leaked: for a player-shaped argument
  the vanilla client does not ask the server who is online, it answers from the player list it has
  been sent - which is exactly where the fake entries live, so no amount of care on the server side
  could reach it. Entity and profile arguments are now marked "ask the server" as the command tree is
  built for sending, so the client asks and the answer comes from the real player list. Applied to
  every server rather than only when the roster is on, because the tree is built when a player joins
  and is not rebuilt when a setting changes later.

**Added**
- **A tool rack**: `/bots tools`, or the Tools entry in the bot menu. Most of what you do to a bot
  needs its name, and its name is the one thing you do not have while stood in front of two hundred
  of them. Every tool removes that step - the bot you are looking at is the argument.
  - **Sticks are aimed.** Right click one bot: **Path Stick** puts it on a path, **Attack Stick**
    picks it up and then sets who it fights, **Stop Stick** drops its target and its path,
    **Dismiss Stick** sends it away, **Panel Stick** opens its panel.
  - **Shields are held.** Keep the button down and it applies to every bot within 32 blocks once the
    timer fills: **Kit Shield** (5s) dresses them, **Faction Shield** (3s) puts them on a side,
    **Spawn Shield** (3s) spawns a chosen number where you stand.
  - A tool that needs pointing at something - a kit, a path, a side, a count - opens a list to pick
    from rather than asking you to type a name you would have to go and look up. `/bots tool <tool>
    [what]` does the same from the command line, and suggests only what that tool can point at.

**Changed**
- The Start Stick and the Kit Stick are now the Path Stick and the Kit Shield, issued from the rack
  along with everything else. `/bots bot-management path start <path>` and `/bots kit stick <kit>`
  still work and still hand you the same tool.
- The three tool implementations are one. Each previously carried its own copy of the same four
  ideas - name the item, remember what it is bound to, notice it in a hand, forget it on disconnect -
  so every new tool meant another copy and another chance to miss the disconnect hook. Adding one is
  now a line in an enum.

---

## 2.4.1

**Fixed**
- **`/randomfill` only filled half a large chest.** It resolved the chest block entity, which is one
  half - so the other half stayed empty and the density setting quietly meant half what it said,
  being a percentage of 27 rather than of the 54 slots on screen. It now resolves both halves as one
  container through vanilla's own combiner, so it follows the same rule the game does about which
  neighbour is the other half. A chest with a block stacked on top is filled too: it cannot be opened
  by hand, but it is still the chest you are pointing at.
- **Random nicknames all read as `SomethingSomething38`.** The generator was not the problem - the
  filter behind it was. Candidate names are checked against Mojang and only real accounts are used,
  and measured against the live endpoint the invented-word shapes resolve about **3%** of the time.
  Almost the only candidates that survived were therefore the two tidy compound shapes, so the
  lookup was doing the choosing. The candidate mix is now weighted by measured hit rate: a plain word
  on its own (83%), an opener plus a word (90%), a word with a stray underscore (83%), four-letter
  handles (77%), a word and two digits (67%). Measured over 200 candidates against the real endpoint,
  **30% -> 80%** now resolve, and names carrying digits fell from **61% of results to 13%**. Live
  samples: `Lantern`, `Jpil`, `Alpha_`, `_Winter`, `mtsj`, `Muffin`, `JustOtter`.
- **The same account could be handed out twice** in one session; the pool was refilled from fresh
  candidates with nothing remembering what had already been used. The last 64 names are now refused.
- **`/identity clear` could restore the wrong skin.** The player's own skin was remembered at the
  moment Scriptify first overrode it - but by then they may already have been wearing an override
  from an earlier session, reapplied at login, so a stranger's face got filed as theirs and clearing
  restored that instead. Their real skin is now captured at login, before anything of ours is
  applied, which is the one moment the profile is guaranteed to be Mojang's rather than ours. It is
  re-read on every login, so a wrong entry cannot survive a reconnect and nothing needs storing on
  disk for a restart to come back correct.

**Note**
- NameMC's random-skins page now answers **403** and its names page renders through obfuscated
  JavaScript with no usernames in the HTML, so neither can be read any more. Random *names* come from
  Mojang's own bulk lookup instead, which is first-party, needs no scraping and returns each account's
  real capitalisation. Random *skins* still try NameMC first and fall back to borrowing from an online
  player, unchanged.

---

## 2.4.0

**Fixed**
- **Bots moved in slow motion above roughly two hundred.** Not memory - the heap was always nearly
  empty, which is why adding RAM never helped. A tick is fifty milliseconds of *one thread*, and two
  hundred and fifty combat brains do not fit in it, so the server ran late and the whole world
  crawled. There is now a governor that measures the tick time actually being achieved and widens a
  stride - how many ticks pass between one bot's decisions - to hold it under 40ms. Movement inputs
  persist between ticks, so a bot thinking every third tick still walks every tick at full
  smoothness; it loses a few hundredths of a second of reaction time. Bots within 48 blocks of a
  real player are exempt and always think every tick, so the footage stays sharp where the camera
  is. Nothing is configured in bots per core - the loop finds what the machine sustains, so a big
  host runs at full detail and a small one thins out on its own.
- **Bots left block-breaking cracks over the scenery mid-fight.** The backend's attack action is a
  real left click: it rays out from the eyes and hits whatever it finds, and if that is a block it
  starts *breaking* it. Fighting anywhere near a wall, a fence or a slope meant mining it by
  accident for the whole duel. The click now asks that question first - if a block is nearer than
  the target, the swing is sent as animation only. A swing into open air is untouched, because that
  is what a missed hit is supposed to look like.
- **Bots stuck in a cobweb mined the ground instead of the web.** They aimed at the centre of the
  block at their feet, and since the eyes sit well above that the ray carried straight through into
  the floor. They now aim at the web that is actually holding them, eye-level one first, which is
  the one a player looks at.
- **Bots cut webs with their fists.** A sword shears cobweb about fifteen times faster than a hand;
  one is now equipped before the web is touched.
- **The web bubble placed a single web, which does nothing.** One web is a cocoon with no walls. It
  now places at least two and up to four - own cell, then the side the attacker is on - and the
  cooldown is only charged if the cocoon actually got built.
- **A bot in a one-block pit stayed there forever**, walking into the wall, because every piece of
  movement code assumed the route to somewhere runs along the ground. It now towers out if it has
  anything to build with, and cuts a staircase if it does not - mine head height, mine the cell
  above, jump onto the block, repeat. Bots with nowhere to be do not do this: a decoration that
  mines its way out of its own set is worse than one that stands still.
- **The scenario prompt flickered and outlived the scenario.** Nobody owned the action bar - the
  draft reminder was pushed every second and every tool wrote its own confirmation straight over
  it, so clicking a bot flashed "Added" for a fraction of a second before the reminder stamped over
  it. Finishing simply stopped sending, which clears nothing: the client holds the last line for
  another three seconds. The bar has one owner now, it names the next step and changes as steps are
  completed rather than repeating one sentence, and finishing wipes it.

**Added**
- **Bots use water to get out of webs.** With a bucket in the inventory they pour it, let it wash
  the webs away, and pick it back up before walking off - the real technique, rather than a slower
  version of cutting.
- **Bots pearl out before healing rather than after failing to.** Anything but a splash potion locks
  a bot in place for a second and a half, which under pressure means four more hits landed and the
  potion wasted. At panic health with someone on them they now throw the pearl first and heal where
  it lands. Without a pearl, drinking under fire still beats not drinking.
- **Kit Stick** - `/bots kit stick <kit>`, or shift-left-click a kit in the kits menu. Hold the
  button for five seconds in the middle of a group and everyone within 32 blocks is wearing the kit.
  It is a shield rather than a stick on purpose: a server cannot see a *held* right click on an item
  with no use action, so a stick could only have offered click-to-start-a-timer. A shield can be
  held indefinitely and reports it, tick by tick, and cancels the moment you let go - so the raised
  arm is the progress bar.
- **Kit editor** - `/bots kit edit <kit>`, or right-click a kit in the kits menu. A real inventory
  screen laid out like a player: armour and off hand across the top, backpack in the middle, hotbar
  along the bottom. Drag items in and out normally. Making a kit previously meant emptying your own
  inventory, dressing yourself in the gear, saving, and putting your own things back - and editing
  one meant doing that again plus deleting the old kit.
- **`/bots performance`** - what the bots are costing right now: count, stride, how many thought last
  tick, and the measured tick time. It reports tick time and not memory deliberately, because memory
  has never been the thing that was wrong.

---

## 2.3.0

**Changed**
- `/bots bot-management path create <name>` now draws the path by walking it, with the same hotbar
  controls the bot editor uses - emerald adds a point, red dye takes one back, lime dye saves,
  barrier cancels. It saves to the stock `paths.json`, so a route drawn this way is identical to one
  built with the point-at-a-time commands and works with everything that reads them. Those commands
  still exist.
- **`path start` takes the path first now**: `path start <path> [bot]`. Any command block or macro
  using the old `path start <bot> <path>` order needs updating.

**Added**
- `path start <path>` with no bot hands you a **Start Stick**. Right click a bot with it and that bot
  walks the path - the same idea as edit mode, where the bot itself is the handle rather than a name
  you have to know. `path start <path> <bot>` still does it outright for scripts.

---

## 2.2.1

**Fixed**
- Bots walking a path with the bhop walk type sailed straight over their points and never registered
  arriving, so they turned back for a point they were standing on top of and circled it. Arrival was
  measured as a plain three-dimensional distance within 1.5 blocks, and the apex of a jump is more
  than a block above the ground on its own - a hopping bot is out of range for most of its stride.
  It is measured flat now, with a three block vertical allowance so a point on the floor below or
  the roof above still does not count.

---

## 2.2.0

**Fixed**
- Mass spawn stopped at around two hundred and only resumed when you killed bots. It bailed out
  entirely above a smoothed tick time of 45ms, and a few hundred bots hold it above that forever, so
  a batch of five hundred silently gave up and waited for you to undo your own spawn. It now slows
  under load instead of stopping - verified at **300 of 300** in one run.
- Mass spawn is also faster when the server is coping: four bots a turn rather than one.
- A path set to loop turned round at the end and retraced itself. A loop now walks the circuit and
  comes back round to the first point.
- Holding a mouse button on a toggle flickered it on and off, leaving the setting wherever the last
  repeat landed. Toggles ignore a repeat within a quarter of a second.
- The recording control is a block, and looking at the ground while clicking it placed it in the
  world instead of starting the recording.

**Changed**
- `/bots npc` is gone - all of it is `/bots`. Where the two trees overlapped the existing command
  wins and the duplicate is dropped, worked out from the live tree so neither side can drift.
- Menus have a plain grey border and nothing behind the content. Toggles are grey dye for off and
  green dye for on, and the enchantment glint is gone.
- Recording now captures your **exact movement** - position, look direction and crouch, every tick -
  and replays it frame for frame, starting from where the recording began. A path could only ever
  express where you went, not how.
- Change skin opens the same typed prompt renaming does. Shift click still takes a random look from
  the local pool.
- Drawn paths are shown in the world while they are being followed.

---

## 2.1.0

**Added**
- `/bots edit` - switch it on, then hit a bot to open its panel. Naming a bot on the command line is
  fine with three of them; in an arena you are looking at the one you want and do not know its name.
  The punch is swallowed, so opening the editor never damages the bot.
- The panel, in three labelled bands. **Settings**: hittable, collidable, crouching, shows in tab,
  immortal, drops on death, copy my effects, apply kit. **Actions**: rename, change skin, randomise
  identity, teleport to me, repeating action. **Movement**: wander, path, record.
- **Paths** are drawn by walking. The hotbar is borrowed - emerald adds a point, red dye takes one
  back, lime dye finishes, barrier cancels - and given back however the session ends. When the path
  is handed over the bot starts from whichever point it is already nearest, rather than walking a
  straight line back to point one.
- **Recording** a route: right click the green block, walk it, right click again (it turns red) to
  hand it over. Samples about three times a second and ignores standing still.
- Wander, reusing the scenario roamer so a wandering bot moves like one in a crowd. It stands down
  while a path or scenario is running rather than fighting them for the controls.

**Changed**
- Nothing says `/sbots` any more - not the help, not the hints, not the scenario prompts.
- Bot screens said "SBots" in the title bar and bot chat opened with a bare `»` while the rest of the
  mod said `Scriptify ›`. Both now use the mod's own voice; two voices in one chat log is the
  clearest tell that a merged mod is really two mods wearing a coat.
- The no-collide scoreboard team is `scriptify_nocollide`, and the old one is deleted at startup.
- `copy my effects` is back as a **button**. It was removed in 2.0.1 as a persisted setting nothing
  ever read - which was right, because as a setting it did nothing. As a one-off action it is
  exactly what it always should have been.

---

## 2.0.1

**Fixed**
- Group lookup was quadratic in the roster. `resolve` runs for every bot every tick and asked "which
  group is this in?" by walking every group's member list, allocating a lower-cased name for each
  comparison. At 250 bots in one group that was ~62,000 comparisons and as many throwaway strings
  per tick. Now a reverse index, looked up once.
- Resolving a bot's settings allocated up to three objects per bot per tick. Layering happens in one
  object now, so it allocates once.
- A bot hidden from the tab list reappeared for anyone who joined afterwards, permanently. Joining
  clients are sent the full player list, and the "already applied" cache meant the removal never went
  out again. Hidden bots are now re-hidden for each arrival.
- Bots left in the no-collide scoreboard team when removed. Team membership is stored by name in the
  world save, so it grew by one dead entry per bot ever spawned, across restarts.
- `/bots set` came back. It does not collide with anything and I removed it by mistake in 2.0.0,
  which left per-bot settings reachable only through a menu.

**Added**
- `/bots group <group> <bot>` and `/bots ungroup <bot>`. The existing `/bots npc group add` takes
  whoever is standing nearby, which a command block or an event file cannot do.

**Removed**
- The `copyEffects` NPC setting. It was stored, persisted, listed in the settings menu and settable
  through the API, and nothing ever read it.

---

## 2.0.0

**Merged SBots into Scriptify.** One mod, one id, one jar. SBots is no longer a separate
installation.

**Added**
- The whole bot toolkit: roster, combat AI, factions, kits, paths, scenarios and the bot control
  panel, all under `/bots`
- `/bots npc` for per-bot NPC settings, groups and defaults
- `/bots skin` - give a bot an appearance from a local pool, with no network call
- `bot_*` event actions, so a scripted scene can spawn, direct and remove bots from an event file
- `scriptify.bots` permission node

**Changed**
- `/sbots` and `/bot` no longer exist; everything is `/bots`
- HeroBot is **suggested**, not required. Without it the bot commands still exist and report that
  they cannot spawn, and the rest of Scriptify is unaffected.

**Notes**
- The bot half was written against Yarn mappings and is now Mojang official, to match the rest of
  the mod. That was a mechanical conversion of 18,000 lines driven by the mapping files rather than
  by hand, but it is a large change and the combat AI is the part least covered by testing.

---

## 1.5.3

**Added**
- SBots integration. `/bots` drives bots directly - spawn, remove, kill, actions, NPC settings,
  paths and scenarios - and the `bot_*` event actions make all of it scriptable from an event file.
- `scriptify.bots` permission node
- Chat panels are now the house style rather than one screen's decoration: `/bots list`,
  `/bots info` and `/scriptify compat` use the same layout `/scriptify help` does

**Notes**
- SBots is optional. Without it every bot command answers plainly instead of failing oddly, and
  nothing about Scriptify changes.

---

## 1.5.2

**Fixed**
- Changing identity could leave a player invisible to others until they relogged. Rebuilding a player
  untracks and re-tracks them for every observer, and vanilla declines the re-track while that
  observer's chunk send is still queued — leaving them with no entity at all. Nothing then repaired
  it, because `ChunkMap` only re-evaluates an entity whose section position changed, so a player
  standing still stayed invisible. Scriptify now keeps reconciling the tracker for two seconds after
  a refresh, which converges as soon as the chunk lands.
- A refresh with no observers in range no longer falls back to hand-spawning the entity for everyone
  in the world. That bypassed the tracker's bookkeeping and left ghost players it would never clean
  up.

**Changed**
- `/scriptify help` is a landing page: six ways in, each clickable, with hover hints. The full index
  moved to `/scriptify help all`.

---

## 1.5.1

**Fixed**
- A totem held by a bot was ignored when the bot was killed — reported with TNT minecarts, but it
  applied to any lethal damage. Staged deaths cancel the death, and cancelling is what skips vanilla's
  totem check, so the disconnect-bots path — which ran before Scriptify's own totem check — made the
  totem vanish instead of pop. The check now runs before every branch that can cancel a death, so no
  future one can swallow a totem either. Real players were never affected.

---

## 1.5.0

**Added**
- Proximity chat. Players only hear people within a set distance, so a conversation in a base stays
  in that base. Set the range in the menu — there is no command for it.
- Ranges of 8, 16, 48, 128 and 256 blocks, or type an exact number

**Fixed**
- Fake Tab was unreachable from the main menu. It shared a slot with Death Control, which was drawn
  second and covered it.

**Notes**
- Only player chat is limited. Private messages, deaths, joins, leaves and `/say` still reach
  everyone, so there is always a way to address the whole server.
- Another dimension is out of earshot at any range.

---

## 1.4.0

**Added**
- Mod conflict detection. Scriptify now finds mods that want the same things it does and settles who
  gets each one, instead of leaving it to mod load order.
- `compat` in the config: `PRIORITY`, `YIELD` or `IGNORE` per surface — nicknames, skins, tab list,
  permissions, death, commands. Yielding one leaves the rest of Scriptify alone.
- `commandOverrides` for handing a single command to another mod, `ignoredMods` for silencing an
  overlap you have already decided is fine
- Command name collisions are detected in the command tree, so a mod published tomorrow is caught
  without being in any list. Vanilla commands Scriptify extends are named as such, not as conflicts.
- `/scriptify compat` reports what was found and who won each surface

---

## 1.3.6

**Added**
- A player's own skin is copied and stored the first time Scriptify overrides it, so
  `/skin clear` and `/identity clear` put them back with no network call at all. Restoring somebody
  used to depend on Mojang answering, and failing meant leaving them wearing a stranger.
- A second, direct route to Mojang's public API, tried when the server's own session service returns
  nothing
- The kit grid uses a fourth row, so a page holds 27 kits before spilling over

**Changed**
- `/kitted` replaces `/skits`

---

## 1.3.5

**Changed**
- Enchantments are shown by what they do, not what they go on: a feather for Feather Falling, a
  cactus for Thorns, ice for Frost Walker, a bottle of experience for Mending. Modded enchantments
  still fall back to their supported item.
- Kits are ordered oldest first, so a new one lands at the bottom instead of jumping into the middle
  of the grid alphabetically
- The new-kit star sits in the first kit slot
- The kit menu keeps its border but drops the grey glass filling the middle

---

## 1.3.4

**Changed**
- Button names and menu titles are bold, so a label reads as a label and its description reads as
  description without a blank line between them
- The kit menu has its frame back
- `/kitted` is gone for now; `/skits` is the command again

---

## 1.3.3

**Fixed**
- **1.3.2 did not start.** `/kitted` was aliased with Brigadier's `redirect()`, which refuses to
  forward a node that has children. The server crashed during command registration. Both names are
  now built from one factory instead.
- A kit still being dealt out no longer overwrites `/copyinv` or an open `/invsee` a tick later

**Changed**
- Rank permission levels are prebuilt per rank instead of rebuilt on every check. `permissions()` is
  not cached by vanilla and `union()` allocates, so this ran once per command node per player.
- Rank edits are written once on the next tick rather than rewriting the file on every click.
  Flushed on shutdown too.

---

## 1.3.2

**Kits**
- `/kitted` is the command now; `/skits` still works as an alias
- `/kitted <player>` opens the menu aimed at somebody else — every kit you click goes to them
- Kit names take spaces and capitals. Commands take them quoted: `/kitted apply "PvP Kit"`
- Right click a kit for its own screen: equip, give to, edit contents, replace from inventory,
  rename, set icon, delete. Delete asks twice.
- Edit a kit in place, with real slots laid out like an inventory
- A chord plays when a kit finishes arriving
- The kit menu has no glass background any more

**Ranks**
- Every rank has a members list, offline players included
- Joining assigns the default rank explicitly, so the member list reflects the server

---

## 1.3.1

**Added**
- `@b` targets bots and other fake players — `/tp @b`, `/kill @b`. Works on vanilla commands too.

**Changed**
- `@d` now means real players without op. Bots are always non-ops, so they used to be swept up by it.

---

## 1.3.0

**Ranks**
- Named permission bundles, assigned per player. A player with no rank counts as `default`, so
  there is never a third state where somebody is neither ranked nor unranked.
- Ships with `default`, `crew` and `director`. Each carries its own Scriptify nodes, prefix and colour.
- Ranks also configure **vanilla** commands: a permission level (none, moderator, gamemaster, admin,
  owner) plus a filter — everything, only these commands, or all except these.
- Ranks stack with individual grants rather than replacing them.
- `/scriptifyranks`, or the GUI from the permissions screen.

**Fake tab list**
- Pad the tab with players who are not there, so a small server reads as a busy one. Small 15,
  medium 40, big 80, very big 200, MASSIVE 500, or an exact count.
- Display only. The roster is built once, the packet is built once and reused, and nothing ticks.
  Joining costs one packet; being connected costs nothing.

**New screens**
- `/villagermaker` — rename a villager, build trades from your inventory, remove any single trade.
- Permissions GUI rebuilt: every node says what it unlocks, plus Director / Crew / Cast roles.
- Fill categories open into a list of their items, so a container can be filled from a specific
  handful instead of several hundred blocks at once. Shift click still takes the whole category.

**Added**
- Singleplayer and LAN worlds are supported
- Four fill presets out of the box: **survival**, **armory**, **market** and **vault**, each with
  its own icon. Presets carry icons generally, so the list is readable.
- `/faketab` and `/villagermaker` open their menus when run with no arguments

**Changed**
- `/immortal` toggles, like `/freeze`. The on / off / set trio is gone; `fall`, `armor`, `void` and
  `global` toggle too. A duration still means "on for this long".
- `/invsee` and `/ec` are laid out like a real inventory — armour and offhand along the top, backpack
  in the middle, hotbar at the bottom. Items were always editable; the order was the problem.
- The enchantment browser shows the item each enchantment goes on instead of a wall of identical
  books. Icons come from the enchantment data, so modded ones get sensible icons too.
- Main menu nickname and skin entries roll a random one on click; shift click copies a named account.

**Known limitation**
- A rank's command filter is enforced when the command runs, not by hiding it. A blocked command
  still appears in suggestions and then refuses. Minecraft builds the command tree from four coarse
  permission buckets, which a server-side mod cannot split per command.
- Operators are never filtered. A rank hands out access; it cannot take it away from an op.

---

## 1.2.1

**Added**
- `/clearground [radius]` — removes dropped items, like `/kill @e[type=item]`

**Fixed**
- XP bar came back empty after a staged death, a skin change or a vanish, until you relogged. The
  respawn packet blanks it and the resend was a no-op, so nothing ever refilled it. Your level was
  never actually lost.
- `/randomnick` repeated itself constantly — it verified names against Mojang, and only a small,
  common set of invented names are real accounts. It now generates locally.
- Random nicknames remember recent rolls and won't reuse a leading word for 20 names

**Changed**
- Name shapes rebuilt from a sample of real usernames on NameMC. Most real names are lowercase,
  short and invented (`nitronze`, `akode`, `beuhlo`) or oddly decorated (`_Stanik`, `qRapid`,
  `sorryimbritish`) - not tidy CamelCase compounds. Those are now a minority of rolls, not the default.
- Bigger word pools, plus given names, sentence openers and invented syllable words
- `/randomskin` and `/randomidentity` still use verified Mojang accounts, and fill a deeper pool

---

---

## 1.2.0

**Added**
- `/identity`, `/randomidentity` — name and skin together
- `/itemmaker` — enchantment browser, lore editor, name colour, stack size
- `/copyinv <player>` — exact copy of your inventory
- Selectors everywhere: `@a` `@s` `@p` `@r` `@o` ops `@d` no op
- `@o` and `@d` work on vanilla commands too
- Several targets at once: `/heal Steve @o`

**Changed**
- `/skin clear` restores your own skin; `/skin reset` removed
- `/freeze` toggles
- Random skins skip accounts with no skin
- `/scriptifyperms grant|revoke` takes the node first, then the player

**Fixed**
- `@` selectors parse at all
- Vanilla commands accept nicknames

**Note**
- Vanilla clients don't suggest `@o` / `@d` and underline them red. They still work.

---

## 1.1.2

**Added**
- Staged deaths now scatter a duplicate of the player's inventory on the ground — they keep everything,
  but from the outside it looks like a real death
- The vanilla death sound now plays on a staged death

**Config**
- `dropItems`, `dropsCanBePickedUp` and `playDeathSound` under `deathControl`
- The dropped copies are pickupable by default; set `dropsCanBePickedUp` to `false` to make them
  scenery only

---

## 1.1.1

**Fixed**
- Bots disconnect when killed instead of getting stuck in spectator
- Bot kills now send both the death message and the leave message
- Death messages use the combat tracker, so minecart, projectile and knockback kills read correctly
- Skin commands no longer freeze the server while waiting on Mojang

**Changed**
- `/randomnick` and `/randomskin` now use real Minecraft accounts, verified through Mojang
- Skin lookups go through the server's built-in Mojang service, and come back signed
- Kit animation plays on the hotbar and armour only; the rest fills instantly

---

## 1.0.0

First release. Minecraft 1.21.11, Fabric, server-side only — players need no mods.

**Scene**
- Staged deaths: no drops, no respawn screen, a real death message and a fake disconnect
- Held totems still pop; four ways to come back
- Fake join / leave / death messages, sudo, chat mute

**Identity**
- Nicknames in the tab list, above the head, in chat and death messages
- Skins: copy, reset, clear, random

**Player**
- Immortality (hits still land), freeze, invisibility, vanish, max health
- Heal, feed, totems, top, PvP toggle, gamemode shortcuts, potion presets

**Inventory**
- Kits with full item components, dealt out one item at a time
- Inventory rollback, saved automatically on death
- Random fill for the container you're looking at, with categories and presets
- Invsee, ender chest, restock, durability

**Authoring**
- Item maker, villager maker, teams, permissions

**Interface**
- One `/scriptify` menu, `/scriptify help`, sounds and consistent styling throughout

**Known limitations**
- Nicknames cap at 16 characters
- Your own skin change shows for others instantly, for you on next reconnect
- Random fill targets one half of a double chest
