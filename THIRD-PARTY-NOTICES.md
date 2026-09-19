# Third-party notices

Scriptify is MIT licensed (see `LICENSE`). This file lists everything in or behind the mod that
somebody else wrote, what licence it carries, and what Scriptify does with it.

Four different relationships appear below, and they are not the same thing:

- **Forked** — Scriptify contains a descendant of another project's source, rewritten and extended.
- **Bundled** — the other project's compiled code ships inside `scriptify-x.y.z.jar`.
- **Derived** — Scriptify's own source, written against another project's approach, or adapted from
  it closely enough that the licence follows.
- **Credited** — an idea or a technique that was read and understood. No code moved. Listed because
  it would be graceless not to.

---

## Forked

### PvP Bot

| | |
| --- | --- |
| **Original author** | Stepan1411 |
| **Licence** | The Unlicense — public domain dedication |
| **Source** | https://github.com/Stepan1411/pvp-bot-fabric |
| **Licence file** | https://raw.githubusercontent.com/Stepan1411/pvp-bot-fabric/main/LICENSE |
| **Where** | `scriptify/bots/**` |

Everything under `/bots` descends from PvP Bot. It was forked, rewritten as SBots and then merged
into Scriptify, and it has since been rebuilt substantially — remapped from Yarn to Mojang official
mappings, reorganised, given an edit mode, a tool rack, recordings, scenarios and a shared per-tick
bot index — but the combat AI, factions and path system started there and the lineage is real.

**The Unlicense places the original in the public domain.** Its exact words are that anyone is free
to "copy, modify, publish, use, compile, sell, or distribute this software, either in source code
form or as a compiled binary, for any purpose, commercial or non-commercial". No attribution is
legally required and no permission needs to be sought. This entry exists because the lineage is worth
stating regardless.

**A discrepancy worth knowing about.** PvP Bot's Modrinth page displays *All Rights Reserved*, which
is Modrinth's default when a project's licence field is left unset. The `LICENSE` file in the source
repository is the Unlicense, and it is the Unlicense on `main`, on `master` and on the `v0.0.15`
release tag. The source repository is what was forked, and its LICENSE file is what governs that
source.

```
This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or
distribute this software, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any
means.
```

---

## Bundled

### sgui

| | |
| --- | --- |
| **Author** | Patbox |
| **Licence** | LGPL-3.0 |
| **Source** | https://github.com/Patbox/sgui |
| **Where** | `META-INF/jars/sgui-1.12.0+1.21.11.jar`, nested inside the Scriptify jar |
| **Modified** | No. Bundled verbatim from Maven. |

Every menu in Scriptify is an sgui screen. It is what lets a server draw a GUI for a vanilla client
without a resource pack, and it is the reason the mod works at all.

It is included by Fabric's Jar-in-Jar mechanism, which links against it rather than incorporating it.
Under LGPL-3.0 section 4 that is permitted for a work under a different licence, provided the library
is identified, its licence is supplied, and the user is able to replace it with a modified version.
All three hold: it is named here, LGPL-3.0 is linked above, and the nested jar can be swapped for
another build of sgui without touching Scriptify.

Scriptify's own MIT licence does **not** extend to sgui. sgui remains LGPL-3.0.

---

## Derived

### SkinRestorer

| | |
| --- | --- |
| **Copyright** | Copyright (c) 2021-2024 Lionarius |
| **Licence** | MIT |
| **Source** | https://github.com/Suiranoil/SkinRestorer |
| **Where** | `scriptify/nick/PlayerRefresh.java`, `scriptify/skin/MojangDirect.java`, `scriptify/skin/ElyBySkinSource.java` |

Changing a player's skin or name without a reconnect means rebuilding that player's entity for
everyone who can see them, in an order that leaves the server's entity tracker telling the truth.
SkinRestorer worked that sequence out first and it is the reference implementation for doing it.

- `PlayerRefresh.java` — the refresh sequence is adapted from SkinRestorer's
  `PlayerUtils.refreshPlayer`. This is the closest of the three; treat the file as carrying the MIT
  notice below.
- `MojangDirect.java` — goes to Mojang's public profile API directly when the server's own session
  service returns nothing, which is the same fallback and the same endpoint.
- `ElyBySkinSource.java` — uses Ely.by as a third source for the same reason SkinRestorer does.
  Written independently against the same public endpoint, over HTTPS rather than HTTP.

MIT requires the copyright notice and permission notice to travel with the code, so here it is in
full:

```
MIT License

Copyright (c) 2021-2024 Lionarius

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Credited

No code from these is present. They are listed because Scriptify's approach was informed by reading
them, and because the techniques are not obvious.

| Project | Author | Licence | What was learned |
| --- | --- | --- | --- |
| [Vanish](https://github.com/DrexHD/Vanish) | DrexHD | — | How to remove a player from the world completely rather than relying on the invisibility effect |
| [immortality-mc-mod](https://github.com/Thesupereevee1/immortality-mc-mod) | Thesupereevee1 | — | Letting hits land and knock back while cancelling the death. Scriptify defaults global immortality to off, which that mod does not |

---

## Required at runtime, not bundled

These are depended on and never redistributed. Nothing of theirs is inside the jar.

| Project | Licence | Relationship |
| --- | --- | --- |
| Minecraft | Mojang EULA | The game. Not redistributed in any form. |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) and [Fabric API](https://github.com/FabricMC/fabric) | Apache-2.0 | Declared dependencies, downloaded by the user |
| HeroBot | see its own page | Optional. Drives the bot bodies. Scriptify works without it and says so. |

---

## External services

Queried over the network at runtime. No code, no redistribution, and nothing is cached beyond the
session except a player's own original skin, which is stored so it can be given back.

| Service | Used for |
| --- | --- |
| Mojang session and profile API | Verifying accounts and fetching signed skin textures |
| [Ely.by](https://ely.by) | A second skin source when Mojang returns nothing |
| [NameMC](https://namemc.com) | Sampling real username shapes for the nickname generator |

---

## Corrections

If you wrote something listed here and the description is wrong, or you are not credited and should
be, please open an issue. It will be fixed in the next release.
