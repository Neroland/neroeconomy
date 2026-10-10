# NeroEconomy 0.3 performance

Measured 2026-10-10 by `common/src/test/java/za/co/neroland/neroeconomy/economy/EconomyBenchmark.java`
(K3, prompt section 16.5). The benchmark uses plain `System.nanoTime` timing, not JMH. It runs the game's
own sections, simulation pipeline and runner in a JUnit JVM, without Minecraft. It is tagged `benchmark`,
so the normal test run leaves it out.

## How to rerun

```
gradlew :neoforge:26.2:test -Pbenchmarks --tests *EconomyBenchmark -i
```

The numbers are printed on lines that start with `[bench]`. The benchmark asserts only that it built the
world described below. It has no time budgets, so a slow machine cannot fail it.

## Machine

- 22 cores, Windows 11 amd64 (the developer's desktop, with an IDE and other work running).
- Java 25.0.3, Java HotSpot(TM) 64-Bit Server VM.
- The test JVM runs with `-Xmx512m`, the Gradle test default for this project.
- These are single runs on a desktop, not a quiet server, so read them as orders of magnitude. The
  medians are the useful figures. The worst cases include garbage-collection and JIT pauses.

## The world

| Part | Size |
|---|---|
| Player accounts | 200, each funded with 1,000,000 credits |
| Open orders on the book | 5,000 resting (3,200 asks from 500 credits up, 1,800 bids up to 200), spread over 20 items, so about 250 per item |
| Auto-trade rules | 500 (250 buy, 250 sell from vault goods), 2 or 3 per account |
| Exchange | the 12 shipped companies; all 200 accounts hold shares |
| Merchant Houses | 3 Houses with 4 commodities, plus their auctions, jobs and bounties |
| Trade settings | the shipped defaults (`TradeSettings.fromConfig()`) |
| Step length | 10-minute steps (the `simStepMinutes` default) |

## Results

| What | Result |
|---|---|
| One simulation step (whole pipeline: housekeeping, expiry, auto-trade, exchange prices, dividends and maturities, Houses, House deals); 200 steps timed after 50 warm-up steps | median **0.60 ms**, best 0.25 ms, p95 1.39 ms, worst 8.7 ms |
| 72-hour catch-up: 432 steps in a single call, three runs from the same snapshot | best **136 ms** (runs 239 / 185 / 136 ms), about 0.32 ms a step |
| Placing a resting order into the deep book | 23.5 µs on average over 5,000 placements |
| Matching: 200 buys of 24 units, each sweeping the cheapest asks (1,227 fills, 6.1 per buy) | median **66 µs** per buy, best 30 µs, p95 172 µs, worst 4.9 ms |
| A server tick's rule evaluation (all 500 rules queued, at most 64 a tick, 1,012 evaluations over 16 ticks) | median **1.2 ms** per tick, best 0.14 ms, worst 2.8 ms |

A server tick lasts 50 ms. The game runs the catch-up at `SimClock.DEFAULT_STEPS_PER_TICK` (2) steps a
tick, so a 72-hour catch-up costs roughly 0.6–1.2 ms a tick for about 216 ticks (11 seconds). It never
runs all at once. One step every 10 minutes is negligible. The heaviest recurring cost is the rule
queue: about 1 ms in a tick where many rules are waiting at once.

The first run of the same benchmark had no warm-up passes for rules and matching. Its worst cases were
18 ms for both, which was the JIT compiling cold code. A server pays that once, after a start.

## Encoded size of each store section

Each section is encoded with `EconomyStore.CODEC` to NBT, as the save does. Each table shows the raw
NBT size, the gzip size (a `.dat` file is gzip-compressed), and the size of the JSON form.

### As built (before any step; the Houses and deals sections are still empty)

| Section | NBT | gzip | JSON chars |
|---|---:|---:|---:|
| admin | 60 | 71 | 43 |
| book | 984,161 | 179,648 | 981,620 |
| exchange | 38,434 | 1,657 | 37,923 |
| ledger | 1,269,189 | 32,294 | 1,314,948 |
| rules | 160,333 | 39,665 | 159,005 |
| simulation | 135 | 137 | 118 |
| vault | 12,219 | 645 | 12,254 |
| **whole store** | **2,464,507** | **254,998** | **2,505,983** |

### After 250 steps (about 42 hours: Houses trading, deals running, rules on the book)

| Section | NBT | gzip | JSON chars |
|---|---:|---:|---:|
| admin | 60 | 71 | 43 |
| book | 1,089,822 | 198,126 | 1,087,698 |
| exchange | 58,966 | 6,956 | 52,625 |
| houses | 2,005 | 644 | 1,844 |
| ledger | 1,364,458 | 37,398 | 1,413,395 |
| obligations | 2,269 | 593 | 2,299 |
| rules | 192,901 | 51,103 | 192,338 |
| simulation | 135 | 138 | 118 |
| vault | 12,219 | 690 | 12,204 |
| **whole store** | **2,722,803** | **297,083** | **2,762,661** |

The book holds 5,506 orders at this point, because the rules' own orders have joined it. The ledger is
mostly audit rows: placements, escrow and fees, each with reason, amount, currency and time. Those rows
compress well. The book compresses least, because every order has its own id. On disk the store is
about 0.3 MB, and Core's recovery backup holds a second copy.

## What this does not measure

- **MSPT on a live server.** That depends on the game, other mods, the players' clients and the save
  thread, and it cannot be measured in a JUnit JVM. The owner's method is in `SMOKE-TEST.md` section 19
  (Performance at scale).
- **Saving the store to disk.** Codec encoding was timed only indirectly, through the copies the
  benchmark makes. Writing the file and Core's backup happen on the game's save path.
- **Item stacks.** Shop listings, auctions and the Vault's item rendering use the game's registries.
  The benchmark trades plain item ids.
