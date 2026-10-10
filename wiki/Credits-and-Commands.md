# Credits and commands

Credits are Core's `nerolandcore:credits` whole-unit currency. NeroEconomy supplies its persistent
provider; balances cannot become negative and every mutation is server-authoritative.

`/eco`, `/economy`, and `/neroeconomy` are three names for the same command tree; the examples below
use the short one.

Permission levels are the game's own: level 0 is every player, level 2 is an operator who may use
game-master commands, and level 3 is an operator who may use administrator commands.

Orders, offers, rules, jobs and auctions are identified by a UUID. The id is shown when the row is
posted and in the `list`, `mine` and `market mine` output.

## Player commands

Every player can use these.

| Command | What it does |
| --- | --- |
| `/eco balance` | Shows your balance. |
| `/eco pay <player\|uuid> <amount>` | Sends credits. The amount is a whole number of at least 1. The recipient is an online player's name, or the UUID of someone who has played on this server. You cannot pay yourself, and a starting balance that has not yet vested cannot be paid (see [Fair play](Fair-Play.md)). |
| `/eco history` | Shows your own retained transaction rows: reason, amount, and date and time in UTC. At most `historyLimitPerPlayer` rows. |
| `/eco privacy logging <true\|false>` | `true` opts your account out of linked transaction rows; `false` opts back in. See [Privacy](Privacy-and-Erasure.md). |
| `/eco hub [tab]` | Opens the Economy Hub, at a tab if given (`wallet`, `vault`, `market`, `orders`, `invest`, `trade`, `jobs`, `auctions`, `shop`; `admin` for level 3). |
| `/eco market mine` | Lists your own open orders, Player Shop listings, jobs and auctions, with their ids. |
| `/eco market cancel <id>` | Cancels one of your own rows: an order, a job nobody has claimed, or an auction with no bid. Goods go back to your [Vault](Vault.md) and escrowed credits to your claim. |

## Orders, offers and direct trade

See [Trading](Trading.md) for the rules.

| Command | What it does |
| --- | --- |
| `/eco order sell <unitPrice> [quantity]` | Sells the stack in your main hand on the order book. |
| `/eco order sell vault <item> <unitPrice> <quantity>` | Sells from your Vault. |
| `/eco order buy <item> <quantity> <unitPrice>` | Places a buy order and escrows the credits. |
| `/eco order cancel <id>`, `/eco order mine` | Cancels an order, or lists your own. |
| `/trade <player>` | Asks a player to trade directly. Also `/eco trade <player>`. |
| `/trade accept [player]`, `/trade decline [player]`, `/trade mute <true\|false>` | Answers a request, or turns requests and offers off or on. |
| `/eco offer send <player> hand\|vault\|credits ...` | Sends an offer, optionally asking for something back. |
| `/eco offer list`, `accept <id>`, `decline <id>`, `cancel <id>`, `mute <true\|false>` | Answers, takes back or mutes offers. |

## Auto-trade rules

See [Auto-trading](Auto-Trading.md).

| Command | What it does |
| --- | --- |
| `/eco rule add buy\|sell item\|tag <target> <limitPrice> <perDay> <total>` | Creates a rule. |
| `/eco rule list`, `pause <id>`, `resume <id>`, `delete <id>`, `pause-all <true\|false>` | Manages your rules. |

## The exchange

See [Exchange and investing](Exchange-and-Investing.md).

| Command | What it does |
| --- | --- |
| `/eco invest list` | Lists the companies. |
| `/eco invest buy <stock> <credits>`, `sell <stock> <units\|all>` | Buys and sells shares and the index fund. |
| `/eco invest portfolio` | Shows your holdings. |
| `/eco invest bond buy <1d\|3d\|7d> <credits>`, `bond redeem <id>` | Buys or cashes in a Savings Bond. |
| `/eco invest practice ...` | The same with play credits, plus `reset`. |

## Contracts and deliveries

See [Contracts and delivery](Contracts-and-Delivery.md) for the rules.

| Command | What it does |
| --- | --- |
| `/eco contract post item <item id> <quantity> <payout>` | Posts a supply contract for an item, for example `minecraft:iron_ingot`. |
| `/eco contract post tag <tag id> <quantity> <payout>` | Posts a supply contract for any item in an item tag. Type the tag id without `#`, for example `minecraft:planks`. |
| `/eco delivery post item <item id> <quantity> <dimension> <x> <y> <z> <payout>` | Posts a delivery job: the goods must be handed over at the destination. |
| `/eco delivery post tag <tag id> <quantity> <dimension> <x> <y> <z> <payout>` | The same, for an item tag. |
| `/eco contract claim <id>`, `/eco delivery claim <id>` | Claims an open job. |
| `/eco contract fulfill <id>`, `/eco delivery fulfill <id>` | Fulfils a job you have claimed, from your inventory. |
| `/eco contract release <id>`, `/eco delivery release <id>` | Gives up a job you have claimed, so anyone can claim it again. |
| `/eco contract list`, `/eco delivery list` | Lists open and claimed jobs (the first 20) with target, quantity, payout, state and time remaining. |

`quantity` is 1 to 2304. `payout` is at least 1 credit and is taken from your balance when you post.

## Bounties

See [Bounties and auctions](Bounties-and-Auctions.md) for the rules.

| Command | Level | What it does |
| --- | --- | --- |
| `/eco bounty post entity <entity type id> <payout> [quantity]` | 0 | Posts a bounty on an entity type, for example `minecraft:zombie`. `quantity` is the number of kills needed, 1 if left out. |
| `/eco bounty post player <player> <payout> [quantity]` | 2 | Posts a bounty on an online player. Also needs `playerBountiesEnabled`. |
| `/eco bounty claim <id>` | 0 | Reserves a bounty, so that only your kills count. |
| `/eco bounty release <id>` | 0 | Gives up a bounty you have claimed. |
| `/eco bounty list` | 0 | Lists open and claimed bounties (the first 20). |

## Auctions

| Command | What it does |
| --- | --- |
| `/eco auction create <quantity> <minimumBid> <minutes> [interplanetary]` | Auctions `quantity` (1 to 64) of the stack in your main hand for `minutes` (1 to 10080, which is seven days). `interplanetary` is `true` or `false`, `false` if left out. |
| `/eco auction bid <id> <amount>` | Places a bid. A bid below the minimum is refused with the lowest amount that would be accepted. |
| `/eco auction list` | Lists running auctions (the first 20) with the high bid and the time remaining. |

## Operator commands

| Command | Level | What it does |
| --- | --- | --- |
| `/eco balance <player\|uuid>` | 2 | Shows another player's balance. |
| `/eco admin give <player\|uuid> <amount>` | 3 | Adds credits. |
| `/eco admin take <player\|uuid> <amount>` | 3 | Removes credits; refused if the balance is too low. |
| `/eco admin set <player\|uuid> <amount>` | 3 | Sets the balance. |
| `/eco export <player\|uuid>` | 3 | Writes that player's data as a JSON file under the world folder. Accepts an online player's name or any UUID. |
| `/eco erase <player\|uuid>` | 3 | Erases that player through Core's shared erasure registry, across every Nero mod. Accepts an online player's name or any UUID. |
| `/eco admin supply`, `stocks`, `houses`, `reload-check`, `health [accept]`, `sim [step [n]]` | 3 | Reports and controls; see [Configuration](Configuration-and-Administration.md). |
| `/eco admin market close\|open` | 3 | Closes or reopens the market. |
| `/eco gallery`, `/eco gallery clear` | 3 | Builds or removes the screenshot showcase; see [Demos](Demos-and-Handbook.md#the-gallery). |

The commands that change the economy (`admin give|take|set`, `admin market`, `admin sim step`,
`admin health accept`) and the `gallery` commands also need `adminMutationsEnabled=true`; with it off
they are not available at all. The reports (`admin supply`, `stocks`, `houses`, `reload-check`,
`health`, `sim`) are always available to level 3. An `admin` command never overflows a balance or takes it below zero.
Its only log line is written at debug level and holds the reason code: no player name, UUID or
amount.

For `pay`, `balance` and `admin`, a player is an online player's name, or the UUID of an online
player or of someone who has a player-data file in this world. A UUID that has never played here is
refused, so a command cannot open an account for it.
