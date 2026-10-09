# NeroNotes — handover from NeroEconomy

Priority: **Medium.** The Disk Exchanger's pricing seam is a real credit sink with a royalty to the
composer, and NeroNotes already has the seam (`integration/ExchangerPricing`, installed through
`NeroNotesIntegrations.setExchangerPricing`). NeroEconomy cannot install a bridge into NeroNotes without
referencing a NeroNotes class, so the bridge lives on your side.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What changes on your side

### 1. A bridge that implements `ExchangerPricing` (added in 0.3.0)

One class in NeroNotes, compiled `compileOnly` against NeroEconomy's jar and loaded only behind
`Services.platform().isModLoaded("neroeconomy")`:

```java
EconomyCaller caller = SiblingEconomy.claim("neronotes", Set.of("library_copy"));
NeroNotesIntegrations.setExchangerPricing(new ExchangerPricing() {
    @Override public boolean chargeCopy(UUID player, int entryId, Optional<UUID> author) {
        long price = /* your config, e.g. libraryCopyPrice */;
        if (price <= 0) return true;
        EconomyResult result = author.filter(a -> !a.equals(player))
                .map(a -> caller.transfer(server, player, a, price, "library_copy", null))
                .orElseGet(() -> caller.charge(server, player, price, "library_copy", null));
        return result.ok();
    }
});
```

- **With a composer:** `transfer` moves the price from the copier to the composer in one step, less a
  fee to NeroEconomy's Reserve (`siblingTransferFeePercent`, 5 % by default, rounded up, at least 1).
  It is never paid by the Reserve, so copying cannot create credits.
- **Anonymous or erased composer, or copying your own entry:** `charge` sends the whole price to the
  Reserve. Never try to recover an author.
- Unvested starting credits cannot pay a composer (`FUNDS_LOCKED`); they can pay a `charge`.
- On refusal, the Exchanger shows its own translated refusal; you may add
  `Component.translatable(result.translationKey())` for the reason.

| Reason code | When |
| --- | --- |
| `library_copy` | Copying a library entry at the Disk Exchanger |

### 2. Show the split on the Exchanger screen (added in 0.3.0)

`caller.feeFor(price)` returns the fee, so the screen can restate "you pay *price*; the composer receives
*price − fee*" before the player confirms. Never show the composer's name or UUID to the copier.

## What needs nothing on your side

- **Blank disks** are a commodity in NeroEconomy's `commodities/compat/neronotes.json`.
- **Pressed disks** (`neronotes:custom_disk`) carry the composer's UUID and a free-text title in item
  data. They are in NeroEconomy's `neroeconomy:never_commodity` tag. For full safety, also add
  `neronotes:custom_disk` to the item tag `neroeconomy:untradeable` from your own data
  (`data/neroeconomy/tags/item/untradeable.json` with `"replace": false`): NeroEconomy then refuses them
  on listings, in the vault and in link snapshots, so their components are never stored or sent. That is
  a data-only change and is safe with NeroEconomy absent.

## Privacy

The author UUID is passed to NeroEconomy only to route the royalty. The composer sees the credit in their
own history as a payment with source `neronotes/library_copy`; neither side is shown the other.
