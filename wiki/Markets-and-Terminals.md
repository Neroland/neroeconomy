# Markets and terminals

The Trading Terminal searches reachable Player Shop and Market Board stock. Quotes are indicative:
the server applies deterministic modifiers and active capped shocks again when the purchase executes.
A price movement beyond the configured tolerance rejects the intent.

The Market Board publishes two escrow-backed offer types:

- Sell offer: the held goods leave inventory when posted. Buyers pay into a durable seller claim.
- Buy offer: the full credit amount leaves the buyer balance when posted. A seller fills it from their
  main hand, creating a durable goods claim for the buyer and proceeds claim for the seller.

Offers are paged and filterable. Expiry returns remaining goods or credits. Replayed payloads are
bounded and idempotent. Claims can be collected at a Market Board or Bank Terminal.
