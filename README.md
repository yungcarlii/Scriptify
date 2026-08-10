# Scriptify

**A server-side toolkit for making scripted Minecraft videos.**

Fake deaths, nicknames, skins, kits, vanish, immortality and a pile of scene-setting tools — all
driven from one menu, all running entirely on the server.

> **Your players don't install anything.** Everything works on a completely vanilla client. No
> client mod, no resource pack, no modpack to hand out. If they can join your server, they can see it.

---

## Why this exists

Shooting a scripted SMP episode means constantly faking things: someone has to "die" convincingly,
another player needs a different name and skin for one scene, everyone needs the same loadout for
take four, and nobody can actually lose their gear doing it.

Scriptify puts all of that behind one command — `/scriptify` — so you can set up a shot in seconds
instead of stringing together twelve vanilla commands and hoping.

---

## Quick start

1. Drop `scriptify-1.0.0.jar` into your server's `mods` folder.
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

`sgui` is bundled inside the jar. You do not need to download it separately.

**Server-side only.** Do not install it on a client.

---

## Features

### Deaths that aren't deaths

The headline feature. When a player takes a fatal hit, Scriptify catches it: they don't actually die,
drop nothing, and see no respawn screen. Instead everyone watching gets the real death message
followed by a genuine-looking **"left the game"** — and the player quietly drops out of the tab list
and into spectator, free to keep filming their own "death".

Coming back is easy: switch out of spectator, run `/ondeath restore`, or just reconnect.

A **held totem still pops normally**, so it never eats a dramatic totem moment.

### Identity

- **Nicknames** — change a player's name everywhere it matters: tab list, above their head, death
  messages, chat. Commands still target their real account, so you never lose track of who's who.
- **Random nicknames** — realistic-looking generated usernames for one-off characters.
- **Skins** — put any player's skin on anyone, and put it back afterwards.

### Player control

| Feature | What it does |
| --- | --- |
| **Freeze** | Locks someone in place. They can still look around, sneak and jump. |
| **Invisibility** | Properly invisible — armour included, which the vanilla effect doesn't hide. |
| **Vanish** | Fully gone: no entity, no tab list, no trace. |
| **Immortality** | Hits still land and knock back, you just can't die. Optional timer. |
| **Gamemode** | `/gms` `/gmc` `/gma` `/gmsp` |
| **Max health** | Change the actual heart row, not just current health. |
| **Heal / Feed / Totems / Top** | The usual quality-of-life shortcuts. |

### Inventory

- **Kits** — save a full loadout and hand it to anyone. Enchantments and custom items survive intact.
  The kit's menu icon is whatever you were holding when you made it.
- **Inventory backups** — snapshot before a destructive take, roll back after. Keeps your last 10.
- **Restock** — top every stack back to full between takes.
- **Durability** — repair everything, or scuff it all up for a battle-worn look.
- **Random fill** — fill an inventory from a themed category to dress a scene fast.
- **Invsee / Ender chest** — live, editable views of another player's storage.

### Scene tools

- **Potion presets** — named effect bundles, one click.
- **Fake join / leave messages** — on demand.
- **Fake death messages** — without anyone dying.
- **Sudo** — make a player run a command or say a line on cue.
- **Mute chat** — silence everyone but staff during a take.

---

## Commands

Everything below is also reachable from the `/scriptify` menu.

### Core

| Command | Description |
| --- | --- |
| `/scriptify` | Open the main menu |
| `/skits` | Open the kit menu |
| `/randomfill` | Fill your inventory from a category |
| `/backupinv` | Inventory backups |
| `/potion` | Potion presets |

### Identity

| Command | Description |
| --- | --- |
| `/nick <name> [player]` | Set a nickname |
| `/randomnick [player]` | Assign a generated nickname |
| `/unnick [player]` | Remove a nickname |
| `/skin set <source> [player]` | Copy another account's skin |
| `/skin reset [player]` | Restore their real skin |
| `/skin clear [player]` | Remove the skin entirely |

### Player

| Command | Description |
| --- | --- |
| `/gms` `/gmc` `/gma` `/gmsp` | Gamemode, optionally with a target |
| `/freeze [player]` · `/unfreeze [player]` | Lock a player in place |
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
| `/immortal` | Show your status |
| `/immortal set <players> [seconds]` | Make players immortal (`-1` = forever) |
| `/immortal global <true\|false> [seconds]` | Everyone at once |
| `/immortal fall <true\|false>` | Fall-damage immunity |
| `/immortal armor <true\|false>` | Armour repairs itself over time |
| `/immortal void <true\|false>` | Whether the void can still kill |
| `/immortal get <player>` | Check someone's status |

### Inventory

| Command | Description |
| --- | --- |
| `/invsee <player>` · `/ec <player>` | View and edit their storage |
| `/restock` | Refill every stack |
| `/durability max\|random` | Repair or scuff your gear |

### Scene

| Command | Description |
| --- | --- |
| `/ondeath` | Show staged-death status |
| `/ondeath on\|off` | Toggle staged deaths |
| `/ondeath restore [player]` | Bring someone back into the scene |
| `/sudo <player> command <...>` | Run a command as them |
| `/sudo <player> say <...>` | Say something as them |

---

## Permissions

Works out of the box — **server operators can use everything**. If you want finer control, install
LuckPerms and Scriptify will use it automatically.

| Node | Covers |
| --- | --- |
| `scriptify.admin` | Everything, including `/sudo` and reload |
| `scriptify.gui.open` | The `/scriptify` menu |
| `scriptify.nick` | Nickname commands |
| `scriptify.skin` | Skin commands |
| `scriptify.player` | Player state: freeze, invis, vanish, immortal, health, gamemode |
| `scriptify.inventory` | Invsee, backups, restock, durability, random fill |
| `scriptify.kits` | Kits |
| `scriptify.ondeath` | Staged deaths |

---

## Configuration

`config/scriptify.json` is created on first start. Bad values are corrected automatically rather than
crashing the server.

```json
{
  "schemaVersion": 2,
  "deathControl": {
    "enabled": true,
    "showDeathMessage": true,
    "fakeLeaveMessage": true,
    "respectTotems": true,
    "hideFromPlayerList": true
  }
}
```

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Turn staged deaths on or off |
| `showDeathMessage` | `true` | Broadcast the real death message first |
| `fakeLeaveMessage` | `true` | Follow it with a "left the game" message |
| `respectTotems` | `true` | Let a held totem pop instead of staging the death |
| `hideFromPlayerList` | `true` | Also remove them from the tab list |

Everything else — nicknames, skins, kits, backups, toggles — is stored per-world under
`<world>/scriptify/` and needs no manual editing.

---

## Notes & credits

Some features are genuinely difficult to do without a client mod, and Scriptify leans on prior art
for the tricky parts:

- **Skins** are handled the way [SkinRestorer](https://github.com/Suiranoil/SkinRestorer) does it.
- **Immortality** follows [immortality-mc-mod](https://github.com/Thesupereevee1/immortality-mc-mod),
  with global immortality defaulting to *off*.
- **Vanish** takes its approach from [Vanish](https://github.com/DrexHD/Vanish).

Two things worth knowing up front:

- **Nicknames are capped at 16 characters.** That's the limit for a Minecraft username, and the
  above-head nametag is delivered by that mechanism.
- **Changing your own skin updates instantly for everyone else, but you'll see it on your next
  reconnect.** Refreshing your own view mid-session would close whatever screen you had open.

---

## License

CC0-1.0 — public domain. Use it, fork it, ship it, no attribution needed.
