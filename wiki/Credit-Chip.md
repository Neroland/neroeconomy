# Credit Chip

`neroeconomy:credit_chip` is redeemed only by using it on a Bank Terminal. The server deposits the
configured value and consumes exactly one chip after a successful deposit.

## Crafting

One chip is crafted from two copper ingots, one gold ingot, two redstone dust, and one iron ingot:

```text
C G C
R I R
```

`C` copper ingot, `G` gold ingot, `R` redstone dust, `I` iron ingot. The chip is therefore
craftable on any server; the exotic meteor pool below is an extra, optional source.

Nerospace interoperability is data-only:
`data/neroeconomy/neroland/meteor_materials/exotic_credit_chip.json` places the chip in the exotic
meteor material pool with weight 30. NeroEconomy does not depend on Nerospace.
