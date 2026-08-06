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
The version comes solely from **`nerolandcore_version` in `gradle.properties`** (currently `1.10.0`)
— bump it there and every cell moves together.

That same property also floors the runtime dependency range in each manifest
(`[<nerolandcore_version>,2.0)` for Forge/NeoForge, `>=<nerolandcore_version> <2.0.0` for Fabric), so
the shipped jar can never load against a Core older than the one it was compiled against.

## Which Core APIs NeroEconomy consumes

| Core package | Used for |
| --- | --- |
| `config.ConfigSchema` / `ConfigValue` / `ConfigManager` | The whole of `config/neroeconomy.properties` — one schema, server-authoritative except `telemetryEnabled`. |
| `economy.CurrencyApi`, `CurrencyProvider`, `Currency`, `CoreCurrencies` | NeroEconomy registers itself as the **persistent provider** behind `CoreCurrencies.CREDITS`. It declares no competing currency of its own. |
| `data.PlayerDataErasure` | The shared per-player erasure hook. NeroEconomy registers five participants (ledger, shop ownership, obligations, market, export files) — see `PRIVACY.md`. |
| `data.SavedDataRecovery` | Guarded `SavedData` access; every NeroEconomy world store is created through it rather than vanilla `computeIfAbsent`. |
| `progression.ProgressionGates` | Progression-aware behaviour (e.g. interplanetary auctions degrade to local when the orbit milestone is not reached). |
| `link.NeroLinkRegistry` + `LinkSnapshotProvider`, `LinkActionHandler`, `LinkModuleInfo`, `LinkAlerts`, `LinkAlert`, `LinkActionResult` | The per-player companion-app module (`balance`, `transactions`, `market` sections; `trade` action) and its rate-limited alerts. Registered last and wrapped in `try/catch` so a Link failure can never block mod load. |
| `registry.RegistrationProvider` (+ `RegistryEntry`) | Loader-agnostic registration of blocks, items, block entities, and menu types. |
| `registry.CoreCreativeTab` | NeroEconomy's items appear in the shared Neroland creative tab. |

No other Nero mod is a compile-time dependency. Siblings (NeroLogistics, NeroFactions, NeroEvents,
NeroQuests, NeroCreatures, Nerospace) are detected at runtime behind string-keyed interfaces and
declared only as optional/suggested in the manifests.
