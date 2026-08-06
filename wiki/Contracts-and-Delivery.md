# Contracts and delivery

Supply contracts request a quantity of an item or common tag. Delivery jobs additionally require the
claimant to reach a server-verified dimension and position. Their state is open, claimed, then
fulfilled or expired.

The poster's payout enters escrow before a job becomes visible. Fulfillment removes the verified
goods into the issuer's durable claim and releases the payout to the claimant. Expiry refunds escrow.
The Trading Terminal provides tabs; equivalent `/eco contract` and `/eco delivery` commands exist.

NeroLogistics may post delivery jobs through a string-keyed runtime seam. Its absence is a no-op.
