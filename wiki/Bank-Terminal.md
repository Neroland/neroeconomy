# Bank Terminal

The Bank Terminal shows your balance, sends credits to another player, collects what you are owed,
and redeems Credit Chips. Everything is decided on the server; the screen holds no balance,
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
refunds) and delivers every item you are owed (purchases, returned stock, auction lots). Items that
do not fit in your inventory stay in your claim until you collect again. Claims have no time limit.
The same Collect button exists on the Trading Terminal and the Market Board.

## Credit Chips

Use a Credit Chip on the block to redeem it. See [Credit Chip](Credit-Chip.md).

## Using the screen

- The balance displays correctly at any size.
- Typing in the recipient or amount box only types: the inventory key and hotbar keys do nothing
  while a box has focus. Escape closes the screen and Tab moves to the next box.
- You must stay within 8 blocks of the terminal, in the same dimension. A spectator can open the
  screen but cannot pay or collect.
- Actions are rate-limited; see [Markets and terminals](Markets-and-Terminals.md).
