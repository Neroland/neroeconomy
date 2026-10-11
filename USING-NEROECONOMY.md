# Building against NeroEconomy (for other mods)

NeroEconomy publishes a Maven artifact per loader × Minecraft cell so that another mod can compile
against its public API (`za.co.neroland.neroeconomy.api`) — paying or charging players through
`SiblingEconomy` / `EconomyCaller`, reading prices, registering price modifiers. NeroEconomy is
always an **optional** dependency of another mod: your mod must build, load and run with
NeroEconomy absent. This page is for mod developers; players and server operators do not need it.

## Coordinates

```text
za.co.neroland.neroeconomy:neroeconomy-<loader>-<mc>:<version>
```

- `<loader>` is `fabric`, `forge` or `neoforge`.
- `<mc>` is `26.1.2`, `26.2` or `26.3` — pick the cell that matches the Minecraft version of the
  node you are compiling.
- `<version>` is NeroEconomy's `mod_version` (`0.3.1-beta.1` is the first version published to Maven).

For example `za.co.neroland.neroeconomy:neroeconomy-neoforge-26.2:0.3.1-beta.1`. The POM declares
**no dependencies**: you get NeroEconomy's classes and nothing else, and your build already has
Neroland Core and the loader.

## Where the artifact comes from

1. **`mavenLocal()`** — run `./gradlew publishToMavenLocal` in this repository to install all nine
   cells into `~/.m2/repository/za/co/neroland/neroeconomy/`.
2. **GitHub Packages** — `https://maven.pkg.github.com/Neroland/neroeconomy`. The release workflow
   publishes all nine cells there for every release. Reading needs a token with `read:packages`.

Declare the GitHub Packages repository **only when credentials are present**, exactly as for Core.
A Maven repository with a null username fails the whole resolve ("Username must not be null!") the
moment Gradle consults it — it does not fall through to the next repository — so a tokenless local
build must not see it at all:

```groovy
repositories {
    mavenLocal()
    def gprUser = providers.gradleProperty('gpr.user').orElse(providers.environmentVariable('GITHUB_ACTOR')).getOrNull()
    def gprKey = providers.gradleProperty('gpr.key').orElse(providers.environmentVariable('GITHUB_TOKEN')).getOrNull()
    if (gprUser != null && gprKey != null) {
        maven {
            name = 'NeroEconomyGitHubPackages'
            url = 'https://maven.pkg.github.com/Neroland/neroeconomy'
            credentials {
                username = gprUser
                password = gprKey
            }
        }
    }
}
```

In GitHub Actions, grant the job `packages: read` and pass `GITHUB_ACTOR` / `secrets.GITHUB_TOKEN`
as environment variables; if the package is not readable by your repository, use an org PAT secret
with `read:packages` instead.

## Declaring the dependency

Use **`compileOnly`**, never `implementation`, `include`, `jarJar` or `shadow`:

```groovy
dependencies {
    // Optional: NeroEconomy's API on the compile classpath only. Never bundled, never required.
    compileOnly "za.co.neroland.neroeconomy:neroeconomy-<loader>-${mc}:${neroeconomy_version}"
}
```

Put `neroeconomy_version` in your `gradle.properties`. To test the integration in a dev client, add
the same coordinate to a runtime-only configuration behind a flag (for example `-PwithNeroEconomy`),
so the default dev run still proves your mod works without it.

## Declaring it in mod metadata — optional only

Never make NeroEconomy a required dependency. API version 1 has shipped since `0.3.0-beta.1`, so that
is a safe floor:

- **Fabric** (`fabric.mod.json`) — under `suggests`, never `depends`:

  ```json
  "suggests": {
    "neroeconomy": ">=0.3.0-beta.1"
  }
  ```

- **NeoForge** (`neoforge.mods.toml`) — `type = "optional"`:

  ```toml
  [[dependencies.yourmod]]
  modId = "neroeconomy"
  type = "optional"
  versionRange = "[0.3.0-beta.1,)"
  ordering = "AFTER"
  side = "BOTH"
  ```

- **Forge** (`mods.toml`) — `mandatory = false`:

  ```toml
  [[dependencies.yourmod]]
  modId = "neroeconomy"
  mandatory = false
  versionRange = "[0.3.0-beta.1,)"
  ordering = "AFTER"
  side = "BOTH"
  ```

## Guarding every call at runtime

Keep every reference to `za.co.neroland.neroeconomy` in **one bridge class** that nothing loads
unless NeroEconomy is present. Check `isModLoaded("neroeconomy")` through your platform helper
(Fabric `FabricLoader.getInstance().isModLoaded`, NeoForge/Forge `ModList.get().isLoaded`) before the
bridge class is touched, then check the API version:

```java
// Elsewhere: only touch the bridge when the mod is present.
if (Platform.isModLoaded("neroeconomy")) {
    NeroEconomyBridge.init();
}

// NeroEconomyBridge.java — the only class that imports za.co.neroland.neroeconomy.
final class NeroEconomyBridge {
    static EconomyCaller caller;

    static void init() {
        if (SiblingEconomy.API_VERSION < 1) {
            return; // older than the API this bridge was written for — stay dormant
        }
        caller = SiblingEconomy.claim("yourmod", Set.of("quest_reward"));
    }
}
```

`SiblingEconomy.API_VERSION` is a `static final int` constant, so `javac` copies the value you
compiled against into your class. The comparison above therefore records the API level your bridge
was written for; the floor in your mod metadata is what keeps an older NeroEconomy from loading
with your mod. If you need the value from the jar that is actually loaded, read it reflectively
inside the bridge: `SiblingEconomy.class.getField("API_VERSION").getInt(null)`.

`SiblingEconomy.claim` must run at common init, before a server starts. Each call is server-side
and returns an `EconomyResult`; pass a `requestKey` to make it idempotent, and never put a player
id or name in it. The public `api` package is add-only — it is never renamed or removed within
API version 1.

## Privacy

Pass the player id you already hold; nothing in the API returns another player's data. Do not log
player ids or names from your bridge. NeroEconomy's own records of your calls are covered by its
[privacy notice](PRIVACY.md) and by Core's shared erasure hook. Questions: info@neroland.co.za.
