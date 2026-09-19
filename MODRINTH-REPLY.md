# Reply to Modrinth moderation

Paste this as a reply on the rejected project. Delete this file before publishing if you like — it is
not part of the mod.

---

Thanks for the review. Both points are addressed in the re-submitted version.

**Supported Minecraft versions**

The metadata was wrong and I have corrected it. The previous upload declared `>=1.21.11 <=26.2`,
which the mod cannot honour — it is built against 1.21.11 mappings and hooks internal server classes,
so it would fail to load on anything later.

`fabric.mod.json` now declares exactly `"minecraft": "1.21.11"`, and I have tagged the version as
1.21.11 only. Future Minecraft versions will get their own builds rather than a widened range.

**Proof of permissions**

Everything in the project that originates elsewhere is now documented in `THIRD-PARTY-NOTICES.md`,
which ships inside the jar and is in the repository. Summary:

1. **PvP Bot** by Stepan1411 — the bot half of the mod (`scriptify/bots/**`) is a fork of it,
   substantially rewritten and extended.
   Licence: **The Unlicense**, a public domain dedication.
   Licence file: https://raw.githubusercontent.com/Stepan1411/pvp-bot-fabric/main/LICENSE
   Repository: https://github.com/Stepan1411/pvp-bot-fabric

   The Unlicense grants that anyone is free to "copy, modify, publish, use, compile, sell, or
   distribute this software, either in source code form or as a compiled binary, for any purpose,
   commercial or non-commercial". This is implicit permission under the content's own licence.

   One thing worth flagging so it does not look like a discrepancy: PvP Bot's **Modrinth page**
   displays All Rights Reserved, which is the default when a project's licence field is left unset.
   The `LICENSE` file in its source repository is the Unlicense, and it is the Unlicense on `main`,
   on `master`, and on the `v0.0.15` release tag. The source repository is what was forked. I have
   credited it anyway, in the README and in the notices file, even though the Unlicense does not
   require attribution.

2. **sgui** by Patbox — bundled in the jar via Jar-in-Jar at
   `META-INF/jars/sgui-1.12.0+1.21.11.jar`, unmodified.
   Licence: **LGPL-3.0** — https://github.com/Patbox/sgui
   It is linked rather than incorporated, which LGPL-3.0 permits for a work under another licence
   provided the library is identified, its licence supplied, and the user able to replace it. All
   three are satisfied, and the notices file states plainly that sgui remains LGPL-3.0 and is not
   covered by Scriptify's MIT licence.

3. **SkinRestorer** by Lionarius — three files derive from it, most closely the sequence that
   rebuilds a player entity after a name or skin change.
   Licence: **MIT**, Copyright (c) 2021-2024 Lionarius — https://github.com/Suiranoil/SkinRestorer
   MIT requires the copyright and permission notice to travel with the code, so it is reproduced in
   full in `THIRD-PARTY-NOTICES.md`.

4. **Credited, no code taken** — Vanish by DrexHD and immortality-mc-mod by Thesupereevee1 informed
   the approach to two features. Listed for transparency.

5. **Not redistributed** — Minecraft, Fabric Loader, Fabric API and HeroBot are dependencies the user
   supplies. Nothing of theirs is in the jar.

The README now carries a Credits and third-party code section linking to the full notices, and
`THIRD-PARTY-NOTICES.md` is included in the jar so it travels with the download.

Happy to add anything further you need.
