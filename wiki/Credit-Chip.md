# Credit Chip

`neroeconomy:credit_chip` is redeemed only by using it on a Bank Terminal. The server deposits
`creditChipValue` credits (100 by default) and consumes exactly one chip.

## Daily limit

One account can redeem `creditChipDailyLimit` chips per day (5 by default). When the limit is
reached the chip is refused with a message and stays in your hand; nothing is lost. Setting the key
to `0` turns redemption off altogether.

The day is a 24-hour period on the server's economy clock, which normally changes at 00:00 UTC.

## Crafting

One chip is crafted from two copper ingots, one gold ingot, two redstone dust, and one iron ingot:

```text
C G C
R I R
```

`C` copper ingot, `G` gold ingot, `R` redstone dust, `I` iron ingot. The chip is therefore
craftable on any server; the exotic meteor pool below is an extra, optional source.

## Meteor drop

Nerospace interoperability is data-only:
`data/neroeconomy/neroland/meteor_materials/exotic_credit_chip.json` registers the chip as an
`exotic`-tier meteor material with a weight of 30, using the keys Core reads (`tier` and
`weight_override`). With Nerospace installed, chips can therefore drop from meteors. NeroEconomy
does not depend on Nerospace.
