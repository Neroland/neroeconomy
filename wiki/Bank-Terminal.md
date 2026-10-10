# Bank Terminal

The Bank Terminal opens the Wallet tab of the Economy Hub. It shows your balance, sends credits to
another player, collects what you are owed, opens your [Vault](Vault.md), and redeems Credit Chips. Everything is decided on the server; the screen holds no balance,
recipient or price that the server trusts.

## Paying

Enter a recipient and a whole number of credits, then press Send.

- The recipient is an online player's name, or the UUID of someone who has played on this server.
  This is the same rule as `/eco pay`.
- The server refuses, with a message that says which, an amount below 1, an unknown recipient,
  yourself as the recipient, and an amount larger than your balance.
- A refused payment changes nothing. In particular, no account is opened for an unknown recipient.
- A payment is one atomic transfer. If the same request arrives twice, it is paid once.

## Collecting

The Collect button pays out every credit you are owed (market proceeds, job and auction payouts,
refunds). Claims have no time limit. Goods you are owed (purchases made while away, returned stock,
auction lots) are not collected here: they are already in your [Vault](Vault.md), which you open
from the same screen. The same Collect button exists on the Trading Terminal and the Market Board.

## The Vault

The Vault button opens a chest-style screen over your private store of goods, beside your inventory.
Deposit and withdraw by clicking, shift-clicking or dragging. See [Vault](Vault.md).

## Credit Chips

Use a Credit Chip on the block to redeem it. See [Credit Chip](Credit-Chip.md).

## Using the screen

- The balance displays correctly at any size.
- Typing in the recipient or amount box only types: the inventory key and hotbar keys do nothing
  while a box has focus. Escape closes the screen and Tab moves to the next box.
- You must stay within 8 blocks of the terminal, in the same dimension. A spectator can open the
  screen but cannot pay or collect.
- Actions are rate-limited; see [Markets and terminals](Markets-and-Terminals.md).
- Your starting balance is locked for the first `startingBalanceVestMinutes` of play time: it cannot be
  sent to another player until then. See [Fair play](Fair-Play.md).
