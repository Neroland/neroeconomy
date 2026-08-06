# Changelog

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
