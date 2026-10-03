# Building against Neroland Core

Neroland Core is NeroEconomy's **only hard dependency**. It is consumed as a published Maven
artifact — there is no Core source checkout, submodule, or pinned git ref in this repository.

## Where the artifact comes from

`stonecutter.gradle` adds two repositories to every loader node, in this order:

1. **`mavenLocal()`** — for local development. Run `./gradlew publishToMavenLocal` in the Core repo
   and this build picks it up first, so you can iterate on Core and NeroEconomy together.
2. **GitHub Packages** (`NerolandCoreGitHubPackages`,
   `https://maven.pkg.github.com/Neroland/neroland-core`) — for CI and fresh clones, where
   `mavenLocal` is empty.

Credentials for GitHub Packages are read lazily: the Gradle properties `gpr.user` / `gpr.key`, and
failing that the environment variables `GITHUB_ACTOR` / `GITHUB_TOKEN`. Because the lookup is lazy
(`getOrNull()`), a local build that resolves Core from `mavenLocal` never needs a token at all. The
token needs only `read:packages`. In GitHub Actions the workflows grant `packages: read` and pass
`GITHUB_ACTOR` / `secrets.GITHUB_TOKEN`; if the Core package is not readable by this repository, use
an org PAT secret with `read:packages` instead.

## Which artifact and which version

One Core artifact per loader × Minecraft cell:

```text
za.co.neroland.nerolandcore:nerolandcore-<loader>-<mc>:<nerolandcore_version>
```

`<loader>` is `fabric`, `forge`, or `neoforge`; `<mc>` is the Stonecutter node's Minecraft version.
The version comes solely from **`nerolandcore_version` in `gradle.properties`** (`1.13.0` at the time
of writing; the property is authoritative) — bump it there and every cell moves together. `1.13.0`
is the first Core release with a Minecraft 26.3 build, and it carries the backup-and-restore
`SavedDataRecovery` guard the stores rely on.

That same property also floors the runtime dependency range in each manifest
(`[<nerolandcore_version>,2.0)` for Forge/NeoForge, `>=<nerolandcore_version> <2.0.0` for Fabric), so
the shipped jar can never load against a Core older than the one it was compiled against.

## Which Core APIs NeroEconomy consumes

| Core package | Used for |
| --- | --- |
| `config.ConfigSchema` / `ConfigValue` / `ConfigManager` | The whole of `config/neroeconomy.properties` — one schema, server-authoritative except `telemetryEnabled`. |
| `economy.CurrencyApi`, `CurrencyProvider`, `Currency`, `CoreCurrencies` | NeroEconomy registers itself as the **persistent provider** behind `CoreCurrencies.CREDITS`. It declares no competing currency of its own. |
| `economy.CurrencyEvents` | Balance-change listener that feeds the companion app's live `balance` event. |
| `data.PlayerDataErasure` | The shared per-player erasure hook. NeroEconomy registers seven participants: the ledger, shop ownership, obligations, the market, the export file, the alert limiter, and the Link module's in-memory caches — see `PRIVACY.md`. Obligations are registered before the market, because the obligation eraser books counterparty refunds into the market store. |
| `data.SavedDataRecovery` | Guarded `SavedData` access with backup and restore. All four world stores (`neroeconomy:ledger`, `neroeconomy:market`, `neroeconomy:obligations`, `neroeconomy:shop_owners`) are loaded through `SavedDataRecovery.get`, never vanilla `computeIfAbsent`. Core keeps a last-known-good copy of each as `neroeconomy_<store>_backup.dat` in the overworld `data` folder and restores from it when the primary file cannot be read. Every eraser calls `SavedDataRecovery.backupNow` straight after its purge, so an erased player does not survive in the backup. There is no local copy of the guard in this repository. |
| `progression.ProgressionGates`, `CoreGates` | Progression-aware behaviour: interplanetary auctions use Core's `CoreGates.REACHED_ORBIT` gate and degrade to local when it is not open. |
| `link.NeroLinkRegistry` + `LinkSnapshotProvider`, `LinkActionHandler`, `LinkModuleInfo`, `LinkEvent`, `LinkAlerts`, `LinkAlert`, `LinkActionResult` | The per-player companion-app module (`balance`, `transactions`, `market` sections; `trade` action; `balance` event on the registry's event bus) and its rate-limited alerts. Registered last and wrapped in `try/catch` so a Link failure can never block mod load. |
| `registry.RegistrationProvider` (+ `RegistryEntry`) | Loader-agnostic registration of blocks, items, block entities, and menu types. |
| `registry.BlockCodecs` | Block codecs that compile on every supported Minecraft version (26.3 removed block-type codecs). |
| `registry.CoreCreativeTab` | NeroEconomy's items appear in the shared Neroland creative tab. |

Core's backup refresh piggybacks on `SavedDataRecovery.get`. The ledger is held by reference and the
shop index is only fetched on shop activity, so the economy tick fetches both on its 30-second
market sweep to keep their backups current. Whether a primary file that is present but fails to
decode reaches Core's guard on Minecraft 26.x has not been confirmed at runtime, so treat the
backup files as a manual recovery aid as well.

No other Nero mod is a compile-time dependency. Siblings (NeroLogistics, NeroFactions, NeroEvents,
NeroQuests, NeroCreatures, Nerospace) are detected at runtime behind string-keyed interfaces and
declared only as optional/suggested in the manifests.
