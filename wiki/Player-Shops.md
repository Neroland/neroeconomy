# Player Shops

The placer owns a Player Shop and controls one fixed-price offer. Stock is moved into the central
market escrow, so the listing continues selling when the owner logs out or the shop chunk unloads.
Only the owner can configure or cancel it; automation receives no inventory view.

A purchase revalidates listing, stock, buyer, quote tolerance, balance, and inventory capacity on the
server. The payment and durable goods claim form the commit point; rapid or replayed intents cannot
settle twice. Sold-out alerts are exposed through Core Link.

Breaking or cancelling returns unsold stock as a durable item claim. Player-data erasure removes
ownership and the player's associated market state through Core's shared hook.
