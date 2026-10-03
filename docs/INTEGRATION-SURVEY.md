# Which mods could use NeroEconomy — integration survey, October 2026

Every sibling repository was read at its current head: its agent notes, README, public API
packages, data and tags, and every use of `CurrencyApi`, `ReputationApi` or the string
`neroeconomy`. Nothing was changed in any of them. Rates quoted below are derived from code at
default config; none was measured in game.

## Headlines

- **Three siblings already move credits or prices, and none of it is budgeted.** NeroColonies
  deposits credits when a colony sells its export buffer, NeroQuests deposits credits on quest
  completion, and NeroFactions registers a price modifier. All three go through seams that carry
  no caller identity and no limit.
- **One existing hook has never worked.** The meteor Credit Chip entry uses keys Core does not
  read, so the chip never drops.
- **Two siblings set the price floor for everything.** NeroAgriculture grows diamonds, nether
  stars and echo shards from wheat and power; Nerotech turns one raw ore into up to three ingots.
  Any NPC that buys those goods without a daily cap and elasticity is a money printer.
- **NeroLogistics only moves items through vanilla `Container`.** A block that exposes its
  inventory solely as a loader capability is invisible to its ducts and drones.
- **Four siblings are empty skeletons** (Events, Ruins, Security, Companion): nothing to integrate
  yet, only seams to leave open.

## Survey

| Mod | Built? (version) | What it could sell / buy / pay / charge | Seam needed here | Change needed there | Data-only possible? | Priority |
| --- | --- | --- | --- | --- | --- | --- |
| NeroColonies | Yes (0.3.0-beta.1) | **Pays today:** export-buffer sales at a fixed price per item (food 1, iron ingot 4, repeater 12, times a config multiplier). **Sell:** colony output. **Buy:** needs-list items. **Could pay:** stage and structure milestones. **Could charge:** research, stall fees | Budgeted payout with a reason code; read-only price query; commodity file; an Auto-Trader that accepts an extract-only inventory; a listener on Core threshold events | Swap the raw deposit in `ExportBuffer.sell` for the budgeted payout and the fixed value for the price query, and handle "the market cannot pay right now" | Partly (Auto-Trader against the beacon and stage rewards need nothing there) | **High** — already mints credits, from a zero-input source |
| NeroQuests | Yes (0.3.0-beta.1) | **Pays today:** 4,035 credits across 27 one-off quests (2,485 per player, 1,550 once per world). **Could pay:** daily contracts (planned). **Objectives:** sell, earn or invest N | Budgeted payout reachable without a class reference; per-player milestones as Core gates (`neroeconomy:first_sale` and friends); world-level threshold channels (sector index, shock) | Swap the raw deposit in `CurrencyReward.grant`; add a counted economy objective type; build daily contracts on the budgeted payout | Partly (`gate_open` and `custom_event` quests work with no change there) | **High** — the only other live faucet, and the planned home of the largest repeatable one |
| NeroFactions | Yes (0.3.0-beta.1) | **Live today:** standing discount (cap 15 %) and enemy surcharge (cap 25 %) on listings. **Could:** credit-priced terminal, treasuries, dues, reputation for selling speciality goods | Keep `api.PriceModifierRegistry`, `PriceContext`, `MarketListingView`, `AccountId` source-compatible; persist non-player account kinds; decide whether modifiers touch NPC and exchange quotes; a sale event | Wire the dormant faction account id; price the terminal in credits; award trade reputation from the sale event | Partly (faction trade tables are data) | **High** — compiled against this mod's API; a rename silently disables it |
| NeroAgriculture | Yes (0.3.0-beta.1) | **Sell:** tier fragments, biomass, fertilisers, biofuel, engineered food, alien produce. **Buy:** fertiliser inputs | Commodity keys that can see item components, or exclusion of component-keyed items; a "farmable" elasticity class for vanilla ores and gems | None for plain items. Its premium-goods provider is unused and priced in energy, not credits; replace it with tags | Yes for plain items | **High** — sets the floor for diamonds, nether stars and echo shards |
| Nerotech | Yes (0.4.0-beta.2) | **Sell:** dusts, coils, circuit boards, frames, fusion fuel cells. **Buy:** raw ore, scrap, shards, bio-fuel. **Could charge:** credits per operation | Commodity file; the arbitrage guard must read five custom recipe types and two code-side bonuses; threshold listener for shocks | None for commodities. Charging credits per operation needs code there | Yes | **High** — the ore multiplier (up to 3×) and the cheapest component paths |
| Nerospace | Yes (1.3.1) | **Sell:** planet ores and gems, alien materials, rocket fuel. **Could pay:** first visit to a planet (one-shot). **Could charge:** freight, launch, docking | Commodity file; off-world price modifier keyed by dimension; **fix the meteor chip entry** | None for commodities, the chip or one-shot payouts. Fees need a pre-launch veto hook there | Yes | **High** — the space sector's only goods, and the existing hook is broken |
| NeroCreatures | Yes (0.2.0-alpha.1) | **Sell:** 11 tagged drops. **Bounties:** nine hostile entity ids already match by string. **Auction-only:** apex trophy | Commodity file with tight daily caps; Reserve-funded system bounties | None | Yes | **High** — eleven goods with no price, no code needed |
| NeroLogistics | Yes (0.4.0-beta.1) | **Sell:** crafted parts only. **Could charge:** per-launch freight. **Could pay:** hauling against a posted job | The Auto-Trader's block entity must implement vanilla `WorldlyContainer`; a charge call with a reason code; a non-player fulfilment path for delivery jobs | A small compat class behind a mod-loaded check at the launch and delivery points | Yes for feeding the Auto-Trader | **High** for the container contract, Medium for fees |
| NeroNotes | Yes (0.3.0-beta.1) | **Charge:** per library copy at the Disk Exchanger, with a royalty to the composer. **Sell:** blank disks | A bridge that implements its pricing seam as a player-to-author transfer with a fee, never Reserve-funded | A way to install the bridge without a class reference; a price query for its screen | Blank disks only | **Medium** — a real sink with a seam already there, but needs a change on its side |
| NeroPower | Yes (0.1.0-alpha.1) | **Sell / buy:** fuel-cycle items and battery cells; buying spent fuel is a disposal sink | Commodity file; a component predicate or "fresh only" rule for fuel rods | None | Yes | **Medium** — real goods, alpha, gated behind Nerotech |
| NeroDecor | Yes (0.4.0-beta.1) | **Sell / buy:** building blocks | Commodity file by item id | Item tags mirroring its block tags would let commodities key on tags | Yes | **Low** — pure crafting pass-through |
| NeroEvents | Skeleton | Planned: price shocks, supply drops, paid events | A shock entry point reachable without a class reference; budgeted payout | Everything | No | **Low** until it has content; the shock seam exists and has no caller |
| NeroRuins | Skeleton | Planned: relics, salvage, vault keycards | Commodity loading that tolerates absent items; an auction-only flag | Everything | Once ids exist | **Low** until it has content |
| NeroSecurity | Skeleton | Planned: paid security tiers; guarding shops and vaults | Economy blocks stay usable by their owner regardless of any added restriction | Everything | No | **Low** — a sink with the highest lock-out risk |
| NeroCompanion | Skeleton | Planned: cosmetic skins bought with credits; carry bots hauling to market | Read-only price query; a debit with a reason code | Everything | No | **Low** |
| Neroland Core | Yes (1.14.0; this mod pins 1.13.0, no API difference) | — | See the Core asks below | Six small additions | — | **Medium** |
| Third-party mods | — | Anything carrying `c:ingots/*`, `c:gems/*`, `c:dusts/*`, `c:raw_materials/*`, `c:foods`, `c:seeds` | Commodity files keyed by `c:` tags load only when the tag has members | None | Yes | **Medium** — comes free with tag-keyed commodities |

## What each seam has to survive

### Faucets that exist today

| Source | Rate | Limit today |
| --- | --- | --- |
| NeroColonies export sales | 4 credits per iron ingot from a miner-and-refinery colony with no input; manual sell click | Buffer of 18 slots; chunk must be loaded; none on credits |
| NeroQuests rewards | 2,485 one-off per account; several objectives only check carried items, so one item set completes them on every alt | None |
| Credit Chip crafting | 100 credits per 2 copper, 1 gold, 2 redstone, 1 iron | None |
| Meteor Credit Chip (once fixed) | About 240–290 credits per meteor, roughly one meteor per 2.5 h per populated dimension | Natural meteors only; needs an online operator |

### Sources that become money printers if an NPC buys them uncapped

| Source | Derived rate | Control needed |
| --- | --- | --- |
| NeroAgriculture crop tower | Roughly 840 diamonds an hour per tower at baseline, 4,500 at maximum; similar for nether stars and echo shards; unattended | Per-account and global daily caps; steep elasticity; never buy resource seeds (they self-replicate) |
| Nerotech ore processing | Up to 3 ingots per raw ore; about 21,600 dust an hour from one maxed processor | NPC ingot buy price below a third of the raw-ore sell price; the guard applies the code-side +1 itself |
| Nerotech filter loop | 2 iron + 6 paper → 4 cartridges → up to 8 iron dust; the scrubber step is in code | Never buy dirty filters; treat the loop as a known recipe in the guard |
| NeroColonies trades | Miners and foresters create cobble, coal, raw iron and logs from nothing | Daily caps on those staples |
| NeroLogistics Auto-Crafter | Any commodity priced above its ingredients becomes a hands-off loop | The arbitrage guard prices by the cheapest recipe and by per-item yield |
| NeroCreatures drops | Non-player kills drop full loot; config multipliers go to 10× | Elasticity, not lore, sets the price of void essence |

### Things that must never be a commodity

- Items whose identity is in components, priced by item id alone: NeroAgriculture's
  `resource_fragment`, `resource_seed`, `engineered_food` and `alien_produce`; NeroPower's
  partly burnt `fuel_rod`; NeroLogistics storage cells with contents; NeroDecor painted variants.
- NeroNotes pressed disks: they carry the composer's UUID and a free-text title in item data.
  Listings, the vault and link snapshots must not serialise their components.
- NeroCreatures' apex trophy and NeroRuins' named relics: auction only.

## Suggested commodity files

Tier is relative value; "farmable" means an unattended source exists.

| File | Entries (tier, farmable) |
| --- | --- |
| `compat/nerotech.json` | `#c:dusts/copper` (cheap, yes), `#c:dusts/iron` (cheap, yes), `#c:dusts/gold` (mid, yes), `nerotech:nero_coil` (cheap, yes), `nerotech:circuit_board` (mid, yes), `nerotech:machine_frame` (cheap, yes), `nerotech:filter_cartridge` (cheap, sell-only), `nerotech:fusion_cell` (valuable), `nerotech:plasma_cell` (valuable), `nerotech:stellar_cell` (rare), `nerotech:antimatter_cell` (rare), `#nerotech:bio_fuels` (cheap, buy-side) |
| `compat/nerospace.json` | `#c:raw_materials/nerosium` (cheap), `nerospace:nerosium_dust` (cheap), `#c:ingots/nerosium` (cheap), `#c:raw_materials/nerosteel` (mid), `#c:ingots/nerosteel` (mid), `#c:gems/xertz_quartz` (mid), `#c:gems/cindrite` (valuable), `#c:gems/glacite` (valuable), `nerospace:rocket_fuel_canister` (mid, yes), `nerospace:alien_fragment` (mid), `nerospace:alien_tech_scrap` (valuable), `nerospace:alien_core` (rare) |
| `compat/neroagriculture.json` | `neroagriculture:territe_fragment` (cheap, yes), `forgite_fragment` (mid, yes), `orbite_fragment` (valuable, yes), `colonite_fragment` (valuable, yes), `voidite_fragment` (rare, yes), `biomass` (cheap), `crop_waste` (cheap), `fertiliser` (cheap, yes), `speed_fertiliser` and `yield_fertiliser` (mid), `biofuel_canister` (cheap, yes) |
| `compat/nerocreatures.json` | `salvaged_circuitry`, `ore_slurry`, `stalker_hide`, `stalker_sinew` (cheap, yes), `plasma_cell`, `worm_chitin`, `refined_crystal` (mid), `contraband` (mid, sell-only), `void_essence` (valuable, yes), `android_core` (valuable) |
| `compat/nerocolonies.json` | `#nerocolonies:colony_food/staple` (cheap, yes), the four upgrade modules (mid) |
| `compat/nerologistics.json` | `item_duct`, `universal_duct`, `network_module`, `item_cell_1k` (cheap), `drone`, `hyperspeed_card`, `item_cell_8k` (mid), `wireless_terminal`, `network_controller`, `item_cell_64k` (valuable) — empty cells only |
| `compat/neropower.json` | `uranium_pellet`, `reprocessed_pellet` (cheap, yes), `isotope_pellet` (mid), `battery_cell_basic` (mid), `battery_cell_elite` (valuable), `spent_fuel_rod` and `spent_isotope_pellet` (cheap, NPC buys only) |
| `compat/nerodecor.json` | `neon_blue`, `glass_cyan`, `xeno_bloom` (cheap), `hull_nero_alloy`, `panel_starsteel`, `fusion_lamp` (mid), `hull_void_crystal`, `void_rift` (valuable) |
| `compat/neronotes.json` | `blank_disk` (cheap, yes) |

## Ranked build list

### Built in this repository

1. **Budgeted payout and charge API with a reason code** — the replacement for raw
   `CurrencyApi.deposit`, drawn from the Reserve and counted per calling mod. Until callers move
   to it, raw deposits are counted against one shared daily budget.
2. **Data-driven commodities with `compat/<modid>.json`**, keyed by item id or tag, loaded only
   when the items exist, each with global and per-account daily caps and elasticity. Files for
   Nerotech, Nerospace, NeroAgriculture, NeroCreatures, NeroColonies, NeroLogistics, NeroPower,
   NeroDecor and NeroNotes.
3. **Arbitrage guard that reads the Nerotech recipe types** and applies the two code-side
   bonuses, pricing by the cheapest recipe and by per-item yield.
4. **Auto-Trader as a vanilla `WorldlyContainer`** so ducts, drones, hoppers and colony depots
   feed it on every loader.
5. **Read-only price and index queries, and economy events** (sale, purchase, fill, dividend).
6. **Per-player milestones as Core gates and world-level threshold channels**, so quest packs can
   key on economy progress with no code anywhere else.
7. **Price modifiers keep working, on the buyer's price only**; NPC and exchange quotes are
   excluded so a 15 % discount cannot beat the spread.
8. **System account kinds persisted** behind the existing `AccountId` seam.
9. **The meteor Credit Chip entry fixed**, with the chip's daily redemption cap.
10. **A reflective, string-keyed shock entry point** so an events mod can push a shock without
    compiling against this mod.
11. **Sector links**: exchange sectors follow traded volume of `c:` and sibling tags.

### Handed over (one document per mod)

| Mod | Ask |
| --- | --- |
| NeroColonies | Use the budgeted payout and the price query in the export sale; handle a short Reserve |
| NeroQuests | Use the budgeted payout for credit rewards; counted economy objective; daily contracts on the budget |
| NeroFactions | Build against the unchanged `api` package; wire the faction account id once system accounts persist; award trade reputation from the sale event |
| NeroLogistics | Optional freight fee and job fulfilment through a compat class; nothing needed for the Auto-Trader |
| NeroNotes | A way to install the pricing bridge by string key; a price query for the exchanger screen |
| Nerospace | Optional launch and freight fees through a pre-launch veto hook |
| Nerotech | Optional credit-charging machines; nothing needed for commodities |
| NeroAgriculture | Replace the unused premium-goods provider with tags; a harvest event |
| NeroCreatures, NeroPower, NeroDecor | Nothing required; optional item tags |
| NeroEvents, NeroRuins, NeroSecurity, NeroCompanion | The seams to call once they have content |
| Core | Caller identity or a reason on `CurrencyApi`; caught listener exceptions; alert module id for push categories; an item lookup seam; an offline gate check; an alert cap |
