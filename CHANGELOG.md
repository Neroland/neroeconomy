# Changelog

## [Unreleased]

Audit remediation: the fixes for the October 2026 audit. This pass changes command syntax, the
companion-app payloads and the export file, and adds config keys, so server operators should read
**Changed** before upgrading. `mod_version` stays at `0.2.0-alpha.1` until the release is cut.

### Added

- **Seeing and cancelling your own rows.** `/eco market mine` lists your open Market Board
  listings, shop listings, buy offers, jobs and auctions. `/eco market cancel <id>` cancels a board
  listing, a buy offer, an unclaimed job or an auction with no bid, and returns what it held in
  escrow to your claim.
- **Releasing a job.** `/eco contract|delivery|bounty release <id>` hands a claimed job back, and a
  claim now lapses by itself after `jobClaimTimeoutMinutes`.
- **Config keys** (defaults in brackets): `auctionMinIncrementPercent` (5),
  `jobClaimTimeoutMinutes` (120), `shopListingMaxIdleDays` (90), `maxListingsPerPlayer` (64),
  `maxBuyOffersPerPlayer` (32), `maxJobsPerPlayer` (16), `maxAuctionsPerPlayer` (8),
  `maxClaimStacksPerPlayer` (256), `maxStackDataBytes` (8192), `creditChipDailyLimit` (5),
  `systemJobsEnabled` (true) and `systemJobDailyPayoutCap` (5000). The wiki's configuration page
  lists every key with its bounds.
- **`neroeconomy:untradeable` item tag.** Empty by default. An item in it cannot be listed,
  auctioned, put in a shop, requested by a job or buy offer, or used to fill one.
- **Economy clock.** Every deadline and stored timestamp is measured on one persisted clock that
  never runs backwards and moves forward by at most 72 hours between two readings.
- **Recovery backups.** The four world stores load through Neroland Core's backup-and-restore
  guard, which keeps `neroeconomy_ledger_backup.dat`, `neroeconomy_market_backup.dat`,
  `neroeconomy_obligations_backup.dat` and `neroeconomy_shop_owners_backup.dat` beside the primary
  files and restores from them when a primary file cannot be read.
- **Companion app.** Snapshots carry the keys the shipped app reads, buy offers appear in the market
  list and can be filled from the app, a buy works while the player is logged out (the goods wait in
  their item claim), and a `balance` event is published when a balance changes.
- **API.** `PricingEngine.executionPriceWithinQuote` is the price rule purchases now settle under.

### Changed

- **Job posting syntax.** The target kind is now a word of its own, and ids are typed in full:
  - `contract post item|tag <id> <quantity> <payout>`
  - `delivery post item|tag <id> <quantity> <dimension> <x> <y> <z> <payout>`
  - `bounty post entity <entity type id> <payout> [quantity]`
  - `bounty post player <player> <payout> [quantity]`

  Tag ids are typed without the `#`. Targets are checked against the registries and limited to 64
  characters, and the delivery dimension must exist.
- **Player bounties** need permission level 2 as well as `playerBountiesEnabled`. The player a
  bounty is on cannot claim it.
- **Purchases never cost more than the price you confirmed.** A price that rose after the quote is
  refused; a price that fell is charged at the lower price. `executionPriceTolerance` no longer
  affects purchases; it only serves the older `PricingEngine.executionPriceAccepted` API method.
- **Only unmodified stacks fill a buy offer or fulfil a job.** Renamed, enchanted, filled or damaged
  stacks are refused, and the message says so.
- **Auctions** need each new bid to beat the standing bid by `auctionMinIncrementPercent` (default
  5 %), and always by at least 1 credit.
- **Per-account caps** apply to open board listings, buy offers, jobs, auctions and uncollected
  claim stacks, and a stack carrying more than `maxStackDataBytes` of data cannot be escrowed.
  Refunds, expiry and settlement are never blocked by a cap.
- **Credit Chips** are limited to `creditChipDailyLimit` redemptions per account per day (default
  5; `0` disables redemption).
- **Server-funded jobs** posted through `DeliveryJobApi.postSystem` can be switched off with
  `systemJobsEnabled` and are limited to `systemJobDailyPayoutCap` credits posted per day.
- **Idle shop listings** expire into the owner's item claim after `shopListingMaxIdleDays` (default
  90) without an owner interaction or a sale.
- **`startingBalance`** has a maximum of 1,000,000.
- **The gallery commands** `gallery` and `gallery clear` need permission level 3, creative mode and
  `adminMutationsEnabled`. The rows and shops they seed are demo rows: they hold no escrow, cannot
  be bought or filled, and are hidden from search and from the companion app. `gallery clear`
  removes only gallery blocks, demo rows and entities carrying the gallery tag.
- **Development tooling is no longer shipped.** The `/necgallery` client command registers only in
  a development environment, and the `neroeconomy:capture` dimension and the Fabric game tests are
  left out of every jar.
- **Player Shops.** A level-2 operator can break any shop, and anyone can break a shop that has no
  owner on record. Breaking, replacing or clearing a shop never loses stock: it goes to the owner's
  item claim.
- **Bank Terminal.** The recipient must be an online player or the UUID of someone who has played
  on the server, exactly as for `/eco pay`.
- **Request ids** are remembered for 10 minutes and survive a restart, so a replayed purchase,
  payment or job action is refused across a restart too.
- **Command output.** History rows show a date, and job, auction and `market mine` rows show the
  time remaining, instead of raw numbers and enum names.
- **Companion-app module (still schema 1).**
  - `transactions.nextCursor` is now a **string** and is left out on the last page. It used to be a
    number, with `-1` on the last page. This is a deliberate break: the shipped app reads a string.
  - `trade` requires `requestId`; a missing or malformed one is refused. `qty` must be a whole
    number from 1 to 64.
  - `trade` with `op: "sell"` and no `unitPrice` fills the buy offer named by `listingId`. With
    `unitPrice` it still posts a new listing.
  - A successful `trade` result carries `op`, `listingId`, `qty`, `price`, `total`, `filled` and
    `delivery` beside the balance object.
  - Error messages are plain sentences. They used to be result names such as `OUT_OF_STOCK`.
  - Snapshots and actions are rate-limited. A throttled snapshot returns the last one served with
    `throttled: true`.
  - Item names are base names, never a custom (renamed) name, and alerts are four fixed sentences
    with one stable id per kind, so a new alert replaces the previous one of its kind.
- **`/eco export` output.** The file keeps its ledger keys at the top level and adds
  `account_reference`, `credit_chip_redemptions`, `economy_time`, `wall_time`, `market`,
  `obligations` and `shops`. It is no longer cut at `historyLimitPerPlayer`. The other party of a
  transaction row is written as `counterparty` instead of that party's pseudonym, and times are
  economy-clock times. The file is written owner-only where the file system supports it.
- **Requires Neroland Core 1.13.0** (unchanged pin), now for its `SavedDataRecovery` backup guard
  as well.

### Removed

- The unused `ShopSettlement` class and the mod's local copy of `SavedDataRecovery` (Core's is used
  instead).
- The `syncModels` Gradle task, which called a script that does not exist.

### Fixed (audit)

Jobs, bounties and auctions:

- Contracts, deliveries and bounties could not be posted with a usable target, because the command
  rejected `:` and `#`. A posted job could never be fulfilled and its payout was locked until
  expiry.
- A job with a very long target could disconnect anyone who opened the jobs tab.
- A claimed job stayed locked to its claimant until it expired.
- Bids of one extra credit could extend an auction's anti-snipe window indefinitely.
- `playerBountiesEnabled` was described as permission-gated but had no gate.
- A player-target bounty could show the target as `player:<uuid>`; lists now show a fixed phrase.

Market and terminals:

- A buyer could be charged up to the execution tolerance above the price they confirmed.
- A shop owner could swap the item between a buyer's look and click; a purchase now carries the
  item the buyer saw and is refused if it changed.
- Buy offers and jobs matched by item id only, so a nearly broken tool, a renamed item or a filled
  container satisfied an offer for the plain item.
- A buy offer for an item id that does not exist held its credits in escrow until expiry.
- Board listings, buy offers, jobs and auctions had no cancel path and no per-account limit, and a
  listed stack had no size limit.
- Request ids were kept in memory only and were used up before the request was validated.
- Collecting a claim in creative mode with a full inventory destroyed the claim.
- Each open terminal ran several full market scans a second; a query now does one pass.

Player Shops:

- A shop removed by an explosion or a command kept its ownership row, so the next shop placed there
  belonged to the previous owner.
- After its owner's data was erased, a shop could not be broken by anyone.
- `/setblock`, `/fill`, `/clone` and structure placement over a shop deleted its listing and stock.
- Shop listings never expired, even for owners who had left.

Bank Terminal:

- The terminal paid any well-formed UUID and opened the recipient's account before checking funds,
  so a player with no money could create unlimited funded accounts and audit rows. It now refuses
  an unknown recipient and a short balance with a specific message and opens no account.

Screens and packets:

- Balances and prices above 32,767 displayed wrongly, and a shop priced at 32,768 or more could not
  be bought from.
- Typing the inventory key (default `e`) in a search, recipient or amount box closed the screen.
- No packet had a rate limit. Each player now has a budget (queries: burst of 10, 5 per second;
  value-moving actions: burst of 6, 2 per second), and an over-budget packet is dropped silently.
- Packet handlers did not check that the player was alive, not a spectator and in the block's
  dimension. Spectators can open a terminal but cannot act through it.
- Countdown timers used the client's clock, and received pages lingered after a screen closed.
- Thirteen hard-coded English strings and several raw enum names are now translatable.

Money supply:

- The Credit Chip was an uncapped, automatable source of credits; it now has a daily limit.
- The Credit Chip's meteor entry used keys Neroland Core does not read, so it never dropped from
  meteors. It now does when Nerospace is installed.
- Server-funded jobs were always enabled although documented as optional.
- `startingBalance` accepted values so large that no deposit could succeed.

Clock and persistence:

- Deadlines used the system clock: a forward jump expired everything at once and a backward jump
  stalled the expiry and retention sweeps.
- A store whose file could not be read was replaced by an empty one, with no backup.
- A crash shortly after a claim was collected could offer the same claim again. The economy stores
  are now saved within a second of a collect.
- An exception thrown by another mod's currency listener could interrupt a trade between the debit
  and the escrow write.

Privacy:

- `/eco export` covered the ledger only and stopped at `historyLimitPerPlayer` rows. It now covers
  the ledger, market, obligations and shops.
- An export contained the other party's pseudonym.
- Export files were only swept after 24 hours of uptime, so a server that restarted daily never
  deleted them. They are now swept at start as well, and written owner-only where possible.
- Erasure left bounties that targeted the erased player in place, did not clear the alert limiter,
  and walked the ledger twice.
- The alert limiter kept player-keyed entries across worlds, and alert ids embedded a timestamp, so
  a player's alert list grew without bound.
- Telemetry (still inert) would have included a player's UUID in the message of a failed
  export-file operation. UUID-shaped text is now scrubbed and file failures report the exception
  class only.
- `PRIVACY.md` named no contact and no responsible party, and described the logging opt-out, the
  erasure tombstone, retention of settled jobs and stored coordinates incorrectly or not at all.

Companion app:

- The market tab was always empty, history failed to load and every sell was refused, because the
  module and the app disagreed on field names.
- There were no live balance updates, no action worked for a logged-out player, and a retried buy
  without a request id became a second purchase.

Commands and packaging:

- Any creative-mode player could run `gallery`, which seeded real stock and credits into the live
  market, and `gallery clear`, which cleared a large area and discarded the entities in it.
- The `neroeconomy:capture` dimension was added to every world, and the `/necgallery` command and
  the game-test entry point shipped in release jars.

Documentation:

- Stale version, Core and Minecraft-matrix strings were corrected, and the wiki now gives the full
  command syntax, every config key, the companion-app payloads and an accurate privacy page.

## [0.2.0-alpha.1] - 2026-09-24

EMI compatibility. No gameplay, id, tag or config change.

### Added

- **EMI support.** Every NeroEconomy recipe uses a vanilla recipe type, which EMI shows on its own, so
  nothing needed a plugin. The build now compiles against the community EMI Unofficial Port (Unstable),
  the only EMI build for Minecraft 26.x, and dev clients load it with `-PwithEmi` (default runs stay
  JEI-only). EMI stays optional.

## [0.1.0-alpha.1] - 2026-09-20

Minecraft **26.3** support, together with the terminal overhaul and gallery tooling that landed
after 0.0.1-alpha.1. No item, block or tag id and no config key changed. An earlier version of this
entry said "No gameplay, id, tag or config change"; that was wrong, because block placement, the
screens and the command tree changed and a dimension was added, as listed below.

### Added

- **Minecraft 26.3** as a new Stonecutter node on every loader — NeoForge `26.3.0.7-beta`,
  Forge `26.3-66.0.2` and Fabric (fabric-api `0.161.0+26.3`, NeoForm `26.3-1`) — built alongside
  26.1.2 and 26.2, so every release now ships **nine** loader × version jars.
- **Terminal overhaul.** The Bank Terminal, Trading Terminal, Market Board and Player Shop face the
  player who places them and can be rotated and mirrored, with new multi-part models and textures.
  All four screens were rebuilt on a shared textured base with new layouts, labels, a selectable
  results list and board-specific controls.
- **`/neroeconomy` command root**, a third name for the `/eco` and `/economy` tree.
- **Gallery capture harness.** `/neroeconomy gallery` and `/neroeconomy gallery clear` build and
  remove a showcase scene, and the client command `/necgallery capture` photographs it for the
  listing gallery. A `compressScreenshots` Gradle task and the gallery workflow publish the shots.
- **`neroeconomy:capture` dimension**, a flat backdrop for the capture harness. It was added to
  every world by this release. A later release restricts the gallery commands and stops shipping
  the dimension and `/necgallery`.

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
