# Bounties and auctions

Bounties use string entity-type identifiers, allowing NeroCreatures targets without a binary
dependency. Kill credit is verified from the server damage source. Player targets remain disabled by
default and configurable.

Auctions are a Trading Terminal tab because the same live sync, claim store, and server-authoritative
intent channel are already required there. The lot is escrowed at creation. Each high bid is escrowed
and the previous high bidder receives an atomic durable refund. A bid inside the configured
anti-snipe window extends the end. Settlement gives the winner a lot claim and the seller proceeds.

Interplanetary auction requests use Core's `reached_orbit` progression gate and degrade to local
scope when that gate is unavailable or unopened.
