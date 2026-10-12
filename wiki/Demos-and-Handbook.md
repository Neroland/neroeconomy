# Demos and the Trader's Handbook

Every part of the economy has a screen, and every screen explains itself: a tooltip on every
control, a restating confirm step before credits move, an empty state that says what to do next,
and a **?** button on every tab that opens a demo.

## The Economy Hub

One **Economy Hub** screen holds the whole economy as tabs: **Wallet, Vault, Market, My orders,
Invest, Trade, Jobs, Auctions, Shop** and, for level-3 operators, **Admin**. Each block opens the hub
at its own tab:

| Block | Opens the hub at |
| --- | --- |
| Bank Terminal | Wallet |
| Trading Terminal | Market |
| Market Board | My orders |
| Exchange Terminal | Invest |
| Auto-Trader | its rule |
| Merchant Stall | Market, filtered to its House |

`/eco hub [tab]` opens the hub at a tab from anywhere. The Vault, direct trade and Auto-Trader
screens, which need item slots, are menus of their own, opened from the tab bar.

Pages are pushed by the server; the client never computes a price or a balance, and the screen
never sends anything the server trusts.

## "Show me" demos

Press **?** on any tab for a **Show me** walkthrough: a short, step-by-step tour of that tab on
sample data, with a box around what each step talks about and Back, Next and Close buttons.

- The demo is **client-side and scripted** over made-up values. It sends nothing to the server, so
  it cannot move a credit, place an order or reach the ledger.
- A **DEMO** banner is drawn on every frame and cannot be hidden, so a demo cannot be mistaken for
  your real data.
- Leaving a demo opened from the Handbook returns to the same Handbook page.

## The Trader's Handbook

The **Trader's Handbook** is an in-game book that teaches the economy in plain words. It has
chapters on credits, the market, buy orders, shops, player trades, auto-trade, investing, the vault,
Merchant Houses, jobs and auctions, fair play, and your data, each a few short pages, and each page
has an **Open demo** button that runs the matching tab's walkthrough.

- **How you get it.** Each player is given one the first time they join, unless the operator turns
  `giveHandbookOnFirstJoin` off. It is never given to a spectator, never twice, and never while the
  economy is frozen. With a full inventory it goes to the player's vault. The only thing stored is a
  per-account flag, which is exported with the account and erased with it.
- **Crafting.** It is craftable on any server as a shapeless recipe: one book and one gold nugget.
- **Reading.** The book is read on the client only; it has no menu and sends and reads nothing from
  the server.
- **Trader's Notes.** Four chapters (the market, shops, Merchant Houses, jobs) end in an extra page
  that is hidden until a quest opens it: the quest book's `handbook_page` reward (see
  [APIs and integrations](APIs-and-Integrations.md)) opens the notes one at a time, in that order.
  Each note is a player-scope Core progression gate, `neroeconomy:handbook/<id>`, so the client learns
  which notes to show from Core's ordinary gate sync and the book still reads nothing of its own from
  the server. Without a quest mod the notes simply stay hidden.

## The gallery

`/eco gallery` (level 3, creative mode, `adminMutationsEnabled=true`) builds one cluster per system
around you for screenshots and demonstrations: a bank kiosk, a Player Shop row, a Trading Terminal
dais, a Market Board wall, the exchange floor, an Auto-Trader, a jobs board, the bank vault, a direct
trade table, a Merchant Stall and an auction podium, each with a label stand. The clusters are laid
out on compass bearings out from where you stand, in tiers up to about 100 blocks away.

Nothing the gallery places is real. Its sample market rows and demo shops hold no escrow and no
credits, can never be bought, filled, matched or listed by anyone, and are shown only to a
creative-mode level-3 operator. They never reach the market, the Reserve, link snapshots or
telemetry. The Auto-Trader shows a sample loop on its label and runs no rule, and the exchange
tickers are sample prices.

`/eco gallery clear` removes the gallery. It remembers every block it placed and every label stand it
spawned, so a clear removes exactly those: a block only if it is still the block the gallery put
there, and a stand only if it still carries the gallery's tag. This works after a restart, and does
not touch anything else in the world.

### Capture tooling (development only)

Contributors re-render the listing screenshots with a client command, `/necgallery capture [time]`,
which teleports into a flat `neroeconomy:capture` dimension, rebuilds the gallery there, hides the
HUD and shoots each cluster from a fixed camera position into `screenshots/neroeconomy/`. `time`
accepts `day`, `noon`, `night`, `midnight`, or a tick number, and `/necgallery capture shot <name>`
grabs the current view once. `/necgallery`, the capture dimension and the Fabric game tests exist
only in a development environment; a released build adds no client command and no dimension.
