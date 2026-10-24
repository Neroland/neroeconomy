#!/usr/bin/env python3
"""Generate one recipe-book unlock advancement per recipe-book recipe in this mod.

For every ``data/<namespace>/recipe/<name>.json`` in this repo whose type the vanilla recipe book shows (crafting, smelting,
blasting, smoking, campfire cooking, stonecutting, smithing), this writes
``data/<namespace>/advancement/recipes/<category>/<name>.json`` (same namespace as the recipe). The recipe unlocks the first time the player holds
one of its ingredients, so it shows in the recipe book and the crafting-table "new recipe" toast.

Trigger choice, first match wins: plain modded items (so a machine unlocks when the player first holds its
signature part, not on the first iron ingot), then the recipe's non-vanilla tags (one criterion per tag, any of
them unlocks it), then plain vanilla items, then vanilla tags. Any tag the recipe uses is safe to name here:
tags load before recipes and advancements, so if the tag were missing the recipe itself would not load.

Machine recipe types (``<modid>:<machine>``) are skipped: the recipe book never shows them, and recipe
viewers list them through the mod's own plugin.

The advancements carry only the ``inventory_changed`` criterion: an advancement that is already granted never
checks again, so a ``recipe_unlocked`` criterion adds nothing (and its field name differs across the Minecraft
lines this mod builds for).

Usage (from anywhere):
    python tools/gen_recipe_unlocks.py           # write the advancements
    python tools/gen_recipe_unlocks.py --check   # exit 1 if a file is missing, stale or orphaned (writes nothing)
"""

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DATA = REPO / "common" / "src" / "main" / "resources" / "data"

BOOK_TYPES = {
    "minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:crafting_transmute",
    "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking",
    "minecraft:stonecutting", "minecraft:smithing_transform", "minecraft:smithing_trim",
}


def flatten(value):
    if isinstance(value, list):
        out = []
        for v in value:
            out.extend(flatten(v))
        return out
    return [value] if isinstance(value, str) else []


def ingredients(recipe):
    """Every ingredient string of a recipe-book recipe, in file order."""
    out = []
    for value in recipe.get("key", {}).values():
        out.extend(flatten(value))
    for field in ("ingredients",):
        for value in recipe.get(field, []):
            out.extend(flatten(value))
    for field in ("ingredient", "input", "material", "base", "addition", "template"):
        if field in recipe:
            out.extend(flatten(recipe[field]))
    return out


def unique(values):
    seen = []
    for value in values:
        if value not in seen:
            seen.append(value)
    return seen


def triggers(recipe):
    """The ingredients that unlock the recipe: either one list of items, or a list of tags (any one unlocks)."""
    values = ingredients(recipe)
    items = [i for i in values if not i.startswith("#")]
    tags = [t for t in values if t.startswith("#")]
    for group in (
        [i for i in items if not i.startswith("minecraft:")],
        [t for t in tags if not t.startswith("#minecraft:")],
        items,
        tags,
    ):
        if group:
            return unique(group)
    return []


def criterion(items):
    return {
        "conditions": {"items": [{"items": items[0] if len(items) == 1 else items}]},
        "trigger": "minecraft:inventory_changed",
    }


def advancement(recipe_id, recipe):
    found = triggers(recipe)
    if not found:
        raise SystemExit(f"{recipe_id}: no ingredient to unlock it with")
    if found[0].startswith("#"):
        # An item predicate names one holder set, so each tag gets its own criterion, OR'ed together.
        names = ["has_ingredient"] if len(found) == 1 else [f"has_ingredient_{n}" for n in range(len(found))]
        criteria = {name: criterion([tag]) for name, tag in zip(names, found)}
    else:
        names = ["has_ingredient"]
        criteria = {"has_ingredient": criterion(found)}
    return {
        "parent": "minecraft:recipes/root",
        "criteria": criteria,
        "requirements": [names],
        "rewards": {"recipes": [recipe_id]},
    }


def namespaces():
    return sorted(d.name for d in DATA.iterdir() if (d / "recipe").is_dir())


def expected():
    out = {}
    skipped = []
    for ns in namespaces():
        recipes = DATA / ns / "recipe"
        for path in sorted(recipes.rglob("*.json")):
            recipe = json.loads(path.read_text(encoding="utf-8"))
            rel = path.relative_to(recipes).with_suffix("").as_posix()
            if recipe.get("type") not in BOOK_TYPES:
                skipped.append(f"{ns}:{rel}")
                continue
            category = recipe.get("category", "misc")
            target = DATA / ns / "advancement" / "recipes" / category / (rel + ".json")
            out[target] = json.dumps(advancement(f"{ns}:{rel}", recipe), indent=2) + "\n"
    return out, skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files, skipped = expected()
    existing = set()
    for ns in namespaces():
        unlocks = DATA / ns / "advancement" / "recipes"
        if unlocks.exists():
            existing |= set(unlocks.rglob("*.json"))
    problems = []
    for path, text in files.items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current != text:
            if args.check:
                problems.append(f"stale or missing: {path.relative_to(REPO)}")
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                with open(path, "w", encoding="utf-8", newline="\n") as out:
                    out.write(text)
    for path in sorted(existing - set(files)):
        problems.append(f"orphan (no recipe): {path.relative_to(REPO)}")
    for line in problems:
        print(line)
    if problems:
        return 1
    print(f"{len(files)} recipe unlocks {'ok' if args.check else 'written'}"
          + (f" ({len(skipped)} machine recipes skipped)" if skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
