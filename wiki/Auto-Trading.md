# Auto-trading

An **auto-trade rule** is a standing instruction to buy or sell an item. Rules keep working with
you offline and the server empty, because they trade through the same order book as everyone else.

## A rule

A rule is: an **item id** (or an **item tag**), a **side** (buy or sell), a **limit price**, a
**quantity per day**, a **total**, and where it works from.

- A **buy** rule's total is a *budget in credits*. The whole budget is **escrowed when you create
  the rule**, and only deleting the rule pays what is left back to your balance. A fill below your
  limit, an expired order and a pause all return credits to the rule's budget, never to your
  balance.
- A **sell** rule's total is a *quantity*. It lists what its inventory holds of the item, up to
  today's quantity, at your limit price.
- A rule places and refreshes **ordinary book orders**, so it is matched by the same engine as a
  player's order, pays the same listing fee and the same market fee, and never gets a price
  discount from another mod. Two rules trading with each other can only lose credits to the Reserve.
- An **item** buy rule keeps one resting buy order for what is left of today's quantity and budget.
  A **tag** buy rule has nothing to rest on the book, so it takes crossing sell orders of items in
  that tag, cheapest first.

### Where a rule works from

- **Your vault.** Buys are delivered there and sells are taken from there. A vault-backed rule keeps
  running with you offline and the server empty. Rules made from the companion app are vault-backed.
- **An Auto-Trader block.** See below.

### Status

A rule reports exactly one status:

| Status | Meaning |
| --- | --- |
| Running | An order is on the book, or today's quantity is done and it carries on tomorrow. |
| Filled | A sell rule that has sold its whole quantity. (It stays listed until you delete it.) |
| Out of budget | A buy rule whose budget cannot buy another unit, or a rule that cannot pay the listing fee. |
| Nothing to sell | A sell rule whose inventory holds none of what it sells. |
| Unreachable | The block the rule works from is in a chunk that is not loaded. |
| Price not met | The other side of the book is there, but not at your price. |
| Paused | Paused by you, or by your "pause all my rules" switch. |
| Daily cap | Your rules have filled as much value today as `ruleDailyValueCap` allows. |
| Storage full | A buy rule whose inventory has no room for what it buys. |
| Order limit | You already have as many open orders as the server allows. |

When a rule stops by itself you get an alert ("One of your auto-trade rules has paused"), and a
completed sell rule raises "One of your auto-trade rules is complete".

### Limits

| Limit | Key | Default |
| --- | --- | ---: |
| Rules per player | `maxRulesPerPlayer` | 8 |
| Credits' worth all your rules together may fill per economy day | `ruleDailyValueCap` | 10,000 |
| Rules evaluated per server tick | fixed | 64 |

Each rule's order counts toward your `maxListingsPerPlayer` / `maxBuyOffersPerPlayer` limits. Rules
run when a simulation step comes round and whenever the book changes for an item they trade, queue
up, and are taken in turn, so any number of rules cannot stall the server.

An Auto-Trader's rule does not run while its owner is in the game in creative or spectator mode;
see [Fair play](Fair-Play.md).

## The Auto-Trader block

The **Auto-Trader** sits next to a container, and is itself an ordinary vanilla container with
eighteen slots, so hoppers, ducts and drones can feed it:

- Slots 0-8 are **inputs**: any side may insert a tradeable stack, and nothing may extract. A sell
  rule lists the unmodified goods it finds there.
- Slots 9-17 are **outputs**: nothing may insert, any side may extract. A buy rule puts what it buys
  there.

The block never loads a chunk. When its chunk is unloaded the rule shows **Unreachable**, pauses and
its order leaves the book. The owner is recorded only in the world data, not in the block, so an
erasure reaches every Auto-Trader of a player even in unloaded chunks. If the block is broken, its
rule is deleted: the budget goes back to you, goods it had listed go to your vault, and the slots
drop like a chest.

## Commands

| Command | What it does |
| --- | --- |
| `/eco rule add buy item <item> <limitPrice> <perDay> <total>` | A buy rule for one item; `total` is a budget in credits. |
| `/eco rule add buy tag <tag> <limitPrice> <perDay> <total>` | A buy rule for any item in a tag (type the tag id without `#`). |
| `/eco rule add sell item <item> <limitPrice> <perDay> <total>` | A sell rule from your vault; `total` is a quantity. |
| `/eco rule add sell tag <tag> <limitPrice> <perDay> <total>` | A sell rule for any item in a tag. |
| `/eco rule list` | Lists your rules with ids, status and budget. |
| `/eco rule pause <id>`, `/eco rule resume <id>` | Pauses or resumes one rule. |
| `/eco rule pause-all <true\|false>` | One switch for all your rules. |
| `/eco rule delete <id>` | Deletes a rule and pays back a buy rule's unspent budget. |

`perDay` is 1 to 2304. The Economy Hub's Orders tab shows the same, with a tooltip on every
control.
