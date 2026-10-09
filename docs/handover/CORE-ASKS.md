# Neroland Core — what NeroEconomy needs

NeroEconomy pins Core 1.13.0 and builds against nothing newer; Core 1.14.0 has no API difference that
matters here. Every ask below is additive: no existing signature changes, so no sibling breaks. They are
ordered by how much they matter. NeroEconomy works around each one today; the workaround is stated.

| # | Ask | Why | Workaround in NeroEconomy 0.3.0 |
| --- | --- | --- | --- |
| 1 | Caller identity and a reason on `CurrencyApi` | Budgets per mod | Shared sibling budget; typed API |
| 2 | Catch listener exceptions on Core's buses | One bad listener breaks the rest | Wraps its own calls |
| 3 | Publish alert events under the alert's module id | The app's Economy notifications never fire | None |
| 4 | Gate checks and opens by UUID | Offline milestones | Pending set, opened at next join |
| 5 | A cap on stored alerts per player | Unbounded store | Raises few alerts |
| 6 | An item lookup seam | Goods in a sibling's storage are invisible | Vanilla `Container` only |
| 7 | A registry-aware backup encode | Backups of stores holding item stacks | Own encode |
| 8 | Detect a store that fails to parse on 26.x | Silent empty store | Own marker and freeze |
| 9 | Publisher identity on `ThresholdEvents` | Any code can fire any channel | Allow-list of source mods |
| 10 | One shared crash-reporter set-up | Every mod initialises its own | Owner's decision |

## 1. Caller identity and a reason on `CurrencyApi`

`CurrencyApi.deposit/withdraw/transfer(UUID, Currency, long)` carry no caller and no reason, so NeroEconomy
cannot tell a quest reward from a colony sale and has to count every untyped deposit against one shared
daily budget (`siblingDailyBudget`). NeroEconomy 0.3.0 adds its own typed API for mods that compile
against it, but a mod that only depends on Core has no way to say who it is.

Ask — overloads, with default bodies so existing providers keep compiling:

```java
// CurrencyApi
public static boolean deposit(UUID player, Currency currency, long amount, String sourceModId, String reason);
public static boolean withdraw(UUID player, Currency currency, long amount, String sourceModId, String reason);
// CurrencyProvider
default boolean deposit(UUID player, Currency currency, long amount, String sourceModId, String reason) {
    return deposit(player, currency, amount);
}
default boolean withdraw(UUID player, Currency currency, long amount, String sourceModId, String reason) {
    return withdraw(player, currency, amount);
}
```

`sourceModId` matches `[a-z0-9_.-]{1,64}`; `reason` matches `[a-z0-9_]{1,32}`. Neither is ever a player
identifier. NeroEconomy would then count each mod against its own budget with the same rules as its typed
API. Optional: `BalanceChange` gains the same two fields (a new record component breaks pattern users,
so a second record or a nullable side channel is safer).

## 2. Catch listener exceptions

`CurrencyEvents.fire`, `ThresholdEvents.fire` and `GateEvents` call listeners in a plain loop, so one
listener that throws stops every later one and propagates into the caller — for `CurrencyApi.deposit`,
into the middle of a payment. NeroEconomy wraps its own calls so the ledger result is still reported, but
other mods' listeners still miss events. Ask: catch `RuntimeException` per listener, log once per
listener class without the event payload (it can carry a UUID), and continue. Nerospace's
`PlanetVisitEvents` already does this.

## 3. Alert events under the alert's module id

`LinkAlerts.publish` sends every alert event as `LinkEvent.forPlayer("core", …)`, although the alert
records its own `moduleId`. The companion app routes push categories by the event's module, so
NeroEconomy's alerts arrive in Core's category and the Economy category never fires. Ask: publish under
`alert.moduleId()` (fall back to `core` when it is blank).

## 4. Gate checks and opens by UUID

`ProgressionGates.isOpen/open/tryOpen` take a `ServerPlayer`. A sale filled while the seller is offline
(a resting order, a trade from the companion app) cannot open `neroeconomy:first_sale` until the player
joins, so NeroEconomy keeps a small pending set of milestones per player (erasable, exported) and opens
them at the next join. Ask:

```java
public static boolean isOpen(MinecraftServer server, UUID player, Identifier gate);
public static boolean open(MinecraftServer server, UUID player, Identifier gate);   // PLAYER scope; TEAM resolved by stored team
```

The same applies to the auction rule that checks `nerolandcore:reached_orbit` for offline bidders.

## 5. A cap on stored alerts per player

`LinkAlerts` keeps every alert per player with no bound. Ask: a per-player cap (for example 50, oldest
acknowledged first) and a per-module cap, so a noisy module cannot fill the store.

## 6. An item lookup seam

Goods a player keeps in a sibling's storage (a NeroLogistics network, a colony depot) are only reachable
through vanilla `Container`. Ask: a Core registry of item sources, string-keyed by mod id, with
`count(server, player, itemId)` and `extract(server, player, itemId, n, simulate)`, so the economy and
quests can see stored goods without any class reference. Low priority: the Auto-Trader as a vanilla
`WorldlyContainer` covers automation today.

## 7. A registry-aware backup encode

`SavedDataRecovery.backupNow` encodes without registry access, so a store that contains item stacks
(NeroEconomy's vault, book and offers) cannot be backed up through it on 26.x. Ask: an overload that
takes the server's `RegistryAccess` (or encodes with `level.registryAccess()`).

## 8. Detect a store that fails to parse on 26.x

On 26.x a saved-data file whose codec fails comes back as a fresh empty store without Core's recovery
guard seeing an error, so the backup is never restored. NeroEconomy keeps its own marker store and
freezes the economy when the marker says data existed but the store came back empty. Ask: surface a
decode failure to `SavedDataRecovery` so it restores the backup and reports it.

## 9. Publisher identity on `ThresholdEvents`

`ThresholdEvents.fire` is public and the crossing carries no publisher, so any code can fire any channel.
NeroEconomy 0.3.0 accepts market-shock requests only on channels named after mods in its `shockSources`
allow-list, but cannot tell who actually fired them. Ask: an optional `sourceModId` on the crossing (a
second record type or a `fire(String sourceModId, ThresholdCrossing)` overload). Low priority: everything
in one server process is trusted code anyway.

## 10. One shared crash-reporter set-up

Every Nero mod bundles and initialises its own crash reporter; once a real DSN is configured, the global
initialisations collide. This is the owner's decision for the whole ecosystem; NeroEconomy follows
whatever Core settles on.

## Contact

Questions about these asks: info@neroland.co.za.
