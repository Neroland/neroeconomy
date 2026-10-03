# Credits and commands

Credits are Core's `nerolandcore:credits` whole-unit currency. NeroEconomy supplies its persistent
provider; balances cannot become negative and every mutation is server-authoritative.

`/eco`, `/economy`, and `/neroeconomy` are three names for the same command tree; the examples below
use the short one.

Permission levels are the game's own: level 0 is every player, level 2 is an operator who may use
game-master commands, and level 3 is an operator who may use administrator commands.

Listings, buy offers, jobs and auctions are identified by a UUID. The id is shown when the row is
posted and in the `list` and `market mine` output.

## Player commands

Every player can use these.

| Command | What it does |
| --- | --- |
| `/eco balance` | Shows your balance. |
| `/eco pay <player\|uuid> <amount>` | Sends credits. The amount is a whole number of at least 1. The recipient is an online player's name, or the UUID of someone who has played on this server. You cannot pay yourself. |
| `/eco history` | Shows your own retained transaction rows: reason, amount, and date and time in UTC. At most `historyLimitPerPlayer` rows. |
| `/eco privacy logging <true\|false>` | `true` opts your account out of linked transaction rows; `false` opts back in. See [Privacy](Privacy-and-Erasure.md). |
| `/eco market mine` | Lists your own open Market Board listings, Player Shop listings, buy offers, jobs and auctions, with their ids. |
| `/eco market cancel <id>` | Cancels one of your own rows: a Market Board listing, a buy offer, a job nobody has claimed, or an auction with no bid. What it held in escrow goes to your claim. |

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
| `/eco gallery`, `/eco gallery clear` | 3 | Builds or removes the screenshot showcase; see below. |

The `admin` commands and the `gallery` commands also need `adminMutationsEnabled=true`; with it off
they are not available at all. An `admin` command never overflows a balance or takes it below zero.
Its only log line is written at debug level and holds the reason code: no player name, UUID or
amount.

For `pay`, `balance` and `admin`, a player is an online player's name, or the UUID of an online
player or of someone who has a player-data file in this world. A UUID that has never played here is
refused, so a command cannot open an account for it.

## Gallery (operators, creative mode)

`/eco gallery` builds a showcase scene around you for screenshots: a bank kiosk to the north, a row
of three stocked Player Shops to the east, a Trading Terminal dais to the south, and a Market Board
wall with sell listings and buy offers to the west, each 24 blocks from where you stand.

Both gallery commands need permission level 3, creative mode and `adminMutationsEnabled=true`.

Nothing the gallery places is real. Its shops and market rows are marked as demo rows:

- they hold no escrow and no credits are created for them;
- they can never be bought or filled, by anyone;
- only a creative-mode level-3 operator sees them at a terminal; other players' searches and the
  companion app never show them;
- they disappear without a claim when they expire or the gallery is cleared.

`/eco gallery clear` removes the gallery built at your position, so stand where you stood when you
built it. Within 32 blocks horizontally and from your feet to 12 blocks above, it removes:

- blocks of the kinds the gallery places (smooth quartz, quartz pillar, chiseled quartz, smooth
  stone, polished andesite, polished deepslate, chiseled deepslate, Bank Terminal, Trading Terminal
  and Market Board), whoever placed them;
- demo Player Shops, but never a real player's shop;
- entities carrying the gallery tag;
- every demo market row on the server.

Because other blocks of those kinds inside the area are removed too, build the gallery away from
anything you want to keep.

### Capture tooling (development only)

Contributors re-render the listing screenshots with a client command, `/necgallery capture [time]`,
which teleports into a flat `neroeconomy:capture` dimension, rebuilds the gallery there, hides the
HUD and shoots each cluster from a fixed camera position into `screenshots/neroeconomy/`. `time`
accepts `day`, `noon`, `night`, `midnight`, or a tick number, and
`/necgallery capture shot <name>` grabs the current view once.

`/necgallery`, the `neroeconomy:capture` dimension and the Fabric game tests exist only in a
development environment. They are not part of any released jar, so a released build adds no client
command and no dimension.

The client root is `/necgallery`, not `/neroeconomy`, because a client-side command that shares a
root name with a server command hides the server's subcommands.

Shots come out at the game window's resolution. Run `./gradlew compressScreenshots` afterwards to
cap every PNG at 1920 px wide and under 4 MB in place (this needs Pillow).
