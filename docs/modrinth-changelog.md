# Modrinth version descriptions

Paste the matching block into the **changelog** field when uploading a version.

---

## 1.3.6

**Added**
- Your real skin is saved before it gets overridden, so clearing a skin or identity restores it
  instantly — no Mojang lookup, works even when their API is down
- A backup route to Mojang for when the server's own lookup comes back empty
- Kit pages hold 27 kits now

**Changed**
- `/skits` is now `/kitted`

---

## 1.3.5

**Changed**
- Enchantment icons are symbolic now — feather for Feather Falling, cactus for Thorns, and so on
- Kits sort oldest first, so new ones appear at the bottom
- New-kit star moved into the first kit slot; grey glass gone from the middle of the menu

---

## 1.3.4

**Changed**
- Bold button names and menu titles
- Kit menu frame is back
- `/skits` again; `/kitted` removed for now

---

## 1.3.3

**Fixed**
- 1.3.2 crashed on startup while registering `/kitted`. Do not use it; use this.
- A kit being dealt out no longer overwrites `/copyinv` or an open `/invsee`

**Changed**
- Rank permission checks and rank saving are both much cheaper

---

## 1.3.2

**Kits**
- `/kitted` replaces `/skits` (which still works). `/kitted <player>` hands the kits to them.
- Kit names can have spaces and capitals
- Right click a kit for options: equip, give to, edit, rename, set icon, delete
- Edit a kit's contents directly, like a chest
- A sound plays when the kit finishes arriving, and the glass background is gone

**Ranks**
- See who is in each rank, offline players included

---

## 1.3.1

**Added**
- `@b` for bots — `/tp @b` teleports the bots and nobody else

**Changed**
- `@d` is real players without op. Bots used to count as `@d` because they are never ops.

---

## 1.3.0

**Ranks**
- Give players a rank instead of ticking permissions one at a time. No rank means `default`.
- Comes with default / crew / director, each with its own permissions, prefix and colour.
- Ranks control vanilla commands too: pick a permission level, then allow everything, only the
  commands you tick, or everything except them.

**Fake tab list**
- Make the server look busy: small 15, medium 40, big 80, very big 200, MASSIVE 500, or your own
  number. Display only — nothing joins and nothing ticks.

**Added**
- Singleplayer and LAN support
- `/villagermaker` — a real villager editor instead of typing trades
- Fill presets included: survival, armory, market, vault, each with a fitting icon
- Fill categories open so you can pick individual blocks; shift click for the whole category

**Changed**
- `/immortal` toggles now, like `/freeze`
- `/invsee` and `/ec` are laid out like an actual inventory instead of by raw slot number
- Enchantment browser shows real item icons instead of a page of identical books
- Main menu nick and skin roll a random one on click; shift click copies a named account

Blocked commands still show up in suggestions and refuse to run: Minecraft builds the command list
from four coarse permission levels, so a server-side mod cannot hide them one by one. Operators are
never affected.

---

## 1.2.1

**Added**
- `/clearground [radius]` — clears dropped items off the ground

**Fixed**
- XP bar showed empty after a staged death until you relogged. The level was never lost, just not
  sent to your client. Also affected skin changes and vanish.
- `/randomnick` repeated itself constantly. It checked names against Mojang, and only a handful of
  common invented names are real accounts — so the same few kept coming back. Generated locally now.
- Nicknames won't reuse a leading word for 20 rolls, so no more ten players called Midnight-something

**Changed**
- Name shapes rebuilt from real usernames on NameMC. Real names are mostly lowercase, short and
  invented, not `AdjectiveNoun42` - so that style is now a minority of rolls instead of all of them.
- `/randomskin` and `/randomidentity` still use real verified accounts

---

## 1.2.0

**Added**
- `/identity`, `/randomidentity` — name and skin together
- `/itemmaker` — enchantment browser, lore editor, name colour, stack size
- `/copyinv <player>` — exact copy of your inventory
- Selectors everywhere: `@a` `@s` `@p` `@r` `@o` ops `@d` no op
- `@o` and `@d` work on vanilla commands too

**Changed**
- `/skin clear` restores your own skin; `/skin reset` removed
- `/freeze` toggles
- Random skins skip accounts with no skin
- `/scriptifyperms grant|revoke` takes the node first, then the player

**Fixed**
- `@` selectors parse at all
- Vanilla commands accept nicknames

Vanilla clients don't suggest `@o` / `@d` and underline them red. They still work.

---

## 1.1.2

**Added**
- Staged deaths drop a copy of your gear on the ground. You keep everything — it just looks like you died.
- The death sound now plays when someone is staged.

---

## 1.1.1

**Fixed**
- Bots disconnect when killed instead of getting stuck in spectator
- Bot kills now send both the death message and the leave message
- Minecart, projectile and knockback kills show the correct death message
- Skin commands no longer freeze the server

**Changed**
- `/randomnick` and `/randomskin` now use real Minecraft accounts, verified through Mojang
- Kit animation plays on the hotbar and armour only

---

## 1.0.0

**Everything you need to shoot scripted Minecraft videos. Your players install nothing.**

Server-side only, works with a vanilla client.

- **Staged deaths** — no drops, no respawn screen. Everyone sees a death message and a convincing
  "left the game" while you keep filming in spectator.
- **Nicknames and skins** — rename or reskin anyone, including random. No NickAPI needed.
- **Kits** that keep enchantments, dealt in one item at a time.
- **Inventory rollback**, saved automatically on death.
- **Random fill** — look at a chest and fill it, with categories and presets.
- **Immortality, vanish, freeze, invisibility, potion presets, sudo.**
- **Item maker, villager maker, teams, permissions.**

Everything runs from `/scriptify`.

**Requires** Minecraft 1.21.11, Fabric, Fabric API, Java 21. LuckPerms optional. `sgui` bundled.
