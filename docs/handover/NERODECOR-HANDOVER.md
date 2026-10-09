# NeroDecor — handover from NeroEconomy

Priority: **Low.** Nothing is required; one optional data change lets the economy price whole families of
blocks instead of single items.

## What needs nothing on your side

NeroEconomy ships `commodities/compat/nerodecor.json` with eight blocks by item id (neon, glass, bloom,
hull, panel, lamp and void blocks). It loads only when your items exist. Painted variants, whose look is
in item components, are never priced.

## Optional: item tags that mirror your block tags (data only)

Your blocks are grouped by **block** tags (`neroland:decor/glass`, `decor/hull`, `decor/luminous`,
`decor/neon`, `decor/panel`). Commodities key on **item** tags. Adding the matching item tags,

```
data/neroland/tags/item/decor/glass.json      { "replace": false, "values": [ ... the block items ... ] }
data/neroland/tags/item/decor/hull.json
data/neroland/tags/item/decor/luminous.json
data/neroland/tags/item/decor/neon.json
data/neroland/tags/item/decor/panel.json
```

lets NeroEconomy's data price `#neroland:decor/hull` once and cover every hull block you add later. It
also helps any other mod that filters items by tag. No NeroEconomy class is involved, and nothing changes
with NeroEconomy absent.

## Code

None. NeroDecor needs no compile dependency on NeroEconomy.
