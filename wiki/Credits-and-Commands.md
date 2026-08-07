# Credits and commands

Credits are Core's `nerolandcore:credits` whole-unit currency. NeroEconomy supplies its persistent
provider; balances cannot become negative and every mutation is server-authoritative.

`/eco`, `/economy`, and `/neroeconomy` are three names for the same command tree; the examples below
use the short one.

## Player commands

- `/eco balance` shows your balance; operators may add a player or UUID.
- `/eco pay <player|uuid> <amount>` atomically sends positive credits.
- `/eco history` shows only your retained reason, amount, and timestamp rows.
- `/eco privacy logging <true|false>` controls non-essential transaction recording.

## Administrator and data-subject commands

- `/eco admin give|take|set <player|uuid> <amount>` mutates without overflow or overdraft.
- `/eco export <player|uuid>` writes that subject's data as JSON under the world folder.
- `/eco erase <player|uuid>` invokes Core's shared erasure registry across Nero mods.

Administrative mutations require the configured permission and feature switch. Logs include mutation
type and amount, never player name, UUID, or transaction counterpart.

## Gallery capture (creative, contributors)

The listing screenshots are produced by a two-part harness, so they can be re-rendered identically
after any texture or model change instead of being hand-framed each time. Both halves are
creative-only and meant for a single-player development world.

- **The server half builds the scene.** `/neroeconomy gallery` lays out four demo clusters around
  you, each on its own compass bearing with its own backdrop wall: a bank kiosk (north), a row of
  three live player shops (east), a trading-terminal dais (south), and a market-board wall seeded
  with escrowed sell listings and standing buy offers (west). `/neroeconomy gallery clear` wipes the
  footprint again, so a rebuild can never stack duplicates; it cancels the demo shops through their
  ordinary teardown, so no listing outlives the block that owned it.
- **The client half drives the camera.** `/necgallery capture [time]` teleports into the flat
  `neroeconomy:capture` dimension, rebuilds the scene there, freezes time, weather, clouds and
  view-bob, hides the HUD, then shoots each cluster from a fixed pose and writes one PNG per shot
  into `screenshots/neroeconomy/`. `time` accepts `day`, `noon`, `night`, `midnight`, or a tick
  number. `/necgallery capture shot <name>` grabs the current view once instead, for a hand-framed
  shot.

The client root (`/necgallery`) is deliberately different from the server root: a client-side
command that shares a root name with a server command shadows the server subtree, and the client
parser then fails on the server-only child instead of forwarding it.

Shots come out at the game window's native resolution, which is well over the gallery size limits.
Run `./gradlew compressScreenshots` afterwards to cap every PNG at 1920 px wide and under 4 MB in
place (this one needs Pillow), then commit the folder — the gallery sync reads it directly.
