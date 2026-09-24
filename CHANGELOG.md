# Changelog

## [0.2.0-alpha.1] - 2026-09-24

EMI compatibility. No gameplay, id, tag or config change.

### Added

- **EMI support.** Every NeroEconomy recipe uses a vanilla recipe type, which EMI shows on its own, so
  nothing needed a plugin. The build now compiles against the community EMI Unofficial Port (Unstable),
  the only EMI build for Minecraft 26.x, and dev clients load it with `-PwithEmi` (default runs stay
  JEI-only). EMI stays optional.

## [0.1.0-alpha.1] - 2026-09-20

Minecraft **26.3** support. No gameplay, id, tag or config change.

### Added

- **Minecraft 26.3** as a new Stonecutter node on every loader — NeoForge `26.3.0.7-beta`,
  Forge `26.3-66.0.2` and Fabric (fabric-api `0.161.0+26.3`, NeoForm `26.3-1`) — built alongside
  26.1.2 and 26.2, so every release now ships **nine** loader × version jars.

### Changed

- VS Code run/debug configurations (`.vscode/launch.json`, `.vscode/tasks.json`) gain the three
  26.3 cells; the "Build all" task now builds all nine.
- CI (`multiloader.yml`, `publish.yml`) builds, attaches and publishes the 26.3 jars.
- Requires **Neroland Core 1.13.0** (was `1.10.0`) — the first Core release with a 26.3
  build. The loader range still derives from the pin (`[${nerolandcore_version},2.0)`).
- JEI pins moved to the newest published builds on each Minecraft version: `29.40.0.101` (26.1.2), `30.35.0.223` (26.2) and `31.3.0.18` (26.3). Compile-time API only — JEI remains a soft dependency and the shipped jar gains no hard requirement.

### 26.3 port notes

- Block classes build their codecs through Core's `BlockCodecs` (26.3 removed block-type codecs); `codec()` is kept without `@Override` so one source compiles on every version.
- Build: the shared `common/` Java source is now preprocessed by Stonecutter for every non-active node (`stonecutterProcessCommon`), so common code can carry `//? if >=26.3 {` blocks, and `common/src/main/resources-<mc>` overlay folders are merged over the shared resources for matching nodes (`mergeCommonResources`). The active node still compiles the raw `common/` folder.
- Build plugins aligned with Neroland Core: ModDevGradle `2.0.147` (the older 2.0.141 cannot set up NeoForge 26.3), ForgeGradle `7.0.40`, Stonecutter `0.9.8`.
- NeoForge metadata: the deprecated `logoFile` property is replaced by `iconFile` on 26.2+ (the logo is a square 256x256 PNG) while 26.1.2, whose FML only understands the old key, still gets `logoFile` — the key is chosen per cell when the manifest is expanded. This clears NeoForge 26.2+'s dev-only "uses the deprecated `logoFile` property" warning screen. The Forge manifest is unchanged: `logoFile` is still the only key Forge supports.

## [0.0.1-alpha.1] - 2026-08-06

First published build of the 0.1.0 feature set.

- Added the persistent Core credits provider, abstract accounts, atomic transfers, bounded
  pseudonymous history, opt-out, export, and shared erasure.
- Added Bank and Trading Terminals, offline Player Shops, and goods/credits escrowed Market Boards.
- Added deterministic Pricing APIs, modifiers, capped shocks, and execution re-pricing.
- Added escrowed contracts, deliveries, bounties, anti-snipe auctions, and durable claims.
- Added Credit Chips, meteor integration data, sci-fi assets, isolated networking, platform seams,
  anonymous telemetry, soft integrations, Core Link support, docs, and six-cell verification.

### Third-pass review fixes

- Hardened market escrow settlement and refund paths against partial and replayed intents.
- Hardened the player shop container against out-of-band slot access and stock/price desync.
- Tightened the privacy surface: erasure now cascades across every NeroEconomy store, and export
  and retention purges cover the same set.
- Clamped pricing so modifier and shock stacking cannot escape the configured multiplier bounds.
- Corrected CI and documentation: shared access transformer wired for Forge, bounded Minecraft
  compatibility ranges, mod icon declared in every manifest, dead mixin configs removed, Dependabot
  and publish/gallery/wiki workflows repointed at real branches and paths, and a craftable
  Credit Chip recipe added.
