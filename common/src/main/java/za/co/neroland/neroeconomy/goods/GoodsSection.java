package za.co.neroland.neroeconomy.goods;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.StoredStacks;

/**
 * The goods section of the economy store: the prototype table, and the one place item stacks are
 * turned into {@link Lot}s and back. Everything else that stores goods — the vault now, the order
 * book, offers and rules later — holds lots and stays free of the game's registries.
 *
 * <p><b>Stack to lot.</b> An unmodified stack becomes a lot with payload 0. Any other stack is
 * interned: the table keeps one copy of it at count 1 and the lot carries that copy's id. Two
 * stacks with the same item and the same components always get the same id.
 * {@link #lotOf(ItemStack)} never refuses, because the system must be able to book any stack it
 * already holds; {@link #tradeableLot} is the door for goods a player offers, and refuses the
 * {@code neroeconomy:untradeable} tag and anything over the encoded-size cap.
 *
 * <p><b>Lot to stack.</b> {@link #stackOf} rebuilds the stack. It answers empty for goods the game
 * cannot name right now — an item or a prototype from a mod that is not installed. Such a lot is
 * not shown and cannot be taken out, but it is never dropped: the lot stays where it is and the
 * prototype is kept in the saved form it was read in, so both come back with the mod.
 *
 * <p><b>Dropping prototypes.</b> A prototype is kept while any section reports a lot that names it
 * (see {@link LotHolder}). {@link #sweep} drops the rest; it runs at most once a minute, before a
 * recovery backup is taken, and at once after an erasure. A prototype can carry a custom name or
 * stored contents, which is why it is never exported and never outlives its last lot.
 */
public final class GoodsSection extends EconomySection {
    /** Resolved on first use: prototypes are item stacks, whose codec needs the game's registries. */
    public static final Codec<GoodsSection> CODEC = Codec.lazyInitialized(GoodsSection::codec);
    private static final long SWEEP_INTERVAL_MILLIS = 60_000L;

    private final Prototypes<ItemStack> table =
            new Prototypes<>(ItemStack::isSameItemSameComponents, ItemStack::hashItemAndComponents);
    /** Prototypes that could not be read as item stacks, exactly as they were saved. */
    private final Map<Integer, Dynamic<?>> unreadable = new LinkedHashMap<>();
    private long nextSweepAt;

    private final StackLimit limits = (item, payload) -> {
        if (payload != Lot.PRISTINE) {
            ItemStack prototype = table.get(payload);
            return prototype == null ? StackLimit.DEFAULT : prototype.getMaxStackSize();
        }
        Item value = item(item);
        return value == null ? StackLimit.DEFAULT : value.getDefaultMaxStackSize();
    };

    public GoodsSection() { }

    /** The goods section of the world's economy store. */
    public static GoodsSection get(MinecraftServer server) { return EconomyStore.get(server).goods(); }

    // --- stack to lot ------------------------------------------------------------------------

    /**
     * The lot for a stack the system already holds. Never refuses a real stack: a modified one is
     * interned. @return null for an empty stack
     */
    public synchronized Lot lotOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (MarketSavedData.pristine(stack)) return Lot.of(item, stack.getCount());
        int payload = table.find(stack);
        if (payload == Lot.PRISTINE) {
            payload = table.intern(stack.copyWithCount(1));
            setDirty();
        }
        return new Lot(item, stack.getCount(), payload);
    }

    /**
     * The lot for a stack a player is offering: as {@link #lotOf}, but null when the stack may not
     * be traded — it carries the {@code neroeconomy:untradeable} tag, or it is larger encoded than
     * {@code maxStackDataBytes}.
     */
    public synchronized Lot tradeableLot(ItemStack stack, HolderLookup.Provider registries) {
        return MarketSavedData.tradeable(stack, registries) ? lotOf(stack) : null;
    }

    /**
     * The lot a stack would become, without storing anything: for a pre-flight that must leave no
     * trace. A modified stack that is not in the table yet is given the id it would get, which
     * matches nothing stored. @return null for an empty stack
     */
    public synchronized Lot peekLot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (MarketSavedData.pristine(stack)) return Lot.of(item, stack.getCount());
        int known = table.find(stack);
        return new Lot(item, stack.getCount(), known != Lot.PRISTINE ? known : table.nextId());
    }

    // --- lot to stack ------------------------------------------------------------------------

    /**
     * The item stack a lot stands for; a fresh copy every time. Empty when the game cannot name
     * the goods right now, in which case the lot must be left exactly where it is.
     */
    public synchronized ItemStack stackOf(Lot lot) {
        if (lot == null) return ItemStack.EMPTY;
        if (lot.pristine()) {
            Item value = item(lot.item());
            return value == null ? ItemStack.EMPTY : new ItemStack(value, lot.count());
        }
        ItemStack prototype = table.get(lot.payload());
        if (prototype == null || prototype.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(prototype.getItem()).toString().equals(lot.item()))
            return ItemStack.EMPTY;
        return prototype.copyWithCount(lot.count());
    }

    /** Whether {@link #stackOf} can rebuild the lot right now. */
    public synchronized boolean showable(Lot lot) {
        if (lot == null) return false;
        if (lot.pristine()) return item(lot.item()) != null;
        ItemStack prototype = table.get(lot.payload());
        return prototype != null && !prototype.isEmpty()
                && BuiltInRegistries.ITEM.getKey(prototype.getItem()).toString().equals(lot.item());
    }

    /** The game's stack limit for any goods; {@link StackLimit#DEFAULT} for goods it cannot name. */
    public StackLimit limits() { return limits; }

    /** The registered item with this id, or null. Air is the registry's answer for "unknown". */
    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        Item value = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return value == null || value == Items.AIR ? null : value;
    }

    // --- dropping prototypes -----------------------------------------------------------------

    /**
     * Drops every prototype no section holds a lot for. Does nothing for a section that stands
     * alone, because it cannot know what refers to it.
     *
     * @return how many prototypes were dropped
     */
    public synchronized int sweep() {
        EconomyStore store = owner();
        if (store == null || (table.size() == 0 && unreadable.isEmpty())) return 0;
        Set<Integer> inUse = new HashSet<>();
        store.forEachLot(lot -> { if (!lot.pristine()) inUse.add(lot.payload()); });
        int dropped = table.retain(inUse);
        int before = unreadable.size();
        unreadable.keySet().retainAll(inUse);
        dropped += before - unreadable.size();
        if (dropped > 0) setDirty();
        return dropped;
    }

    /** {@link #sweep}, at most once a minute. {@code wallMillis} is the wall clock: this is a throttle. */
    public synchronized int sweepIfDue(long wallMillis) {
        if (wallMillis < nextSweepAt) return 0;
        nextSweepAt = wallMillis + SWEEP_INTERVAL_MILLIS;
        return sweep();
    }

    /** How many prototypes are stored, readable or not. */
    public synchronized int prototypeCount() { return table.size() + unreadable.size(); }

    /**
     * Nothing stored and no id ever handed out. A section that has handed out ids is kept even
     * when empty, so that an id is never used twice.
     */
    @Override public synchronized boolean isEmpty() {
        return table.size() == 0 && unreadable.isEmpty() && table.nextId() == 1;
    }

    // --- persistence -------------------------------------------------------------------------

    /** One saved prototype: the stack, or the saved form of one that could not be read. */
    private record Stored(ItemStack stack, Dynamic<?> raw) { }

    /** Never fails to decode: what is not a readable stack is kept as it was found. */
    private static final Codec<Stored> STORED = new Codec<>() {
        @Override public <T> DataResult<T> encode(Stored value, DynamicOps<T> ops, T prefix) {
            return value.stack() != null ? StoredStacks.CODEC.encode(value.stack(), ops, prefix)
                    : Codec.PASSTHROUGH.encode(value.raw(), ops, prefix);
        }

        @Override public <T> DataResult<Pair<Stored, T>> decode(DynamicOps<T> ops, T input) {
            ItemStack stack = StoredStacks.CODEC.parse(ops, input).result().orElse(null);
            Stored stored = stack == null || stack.isEmpty() ? new Stored(null, new Dynamic<>(ops, input))
                    : new Stored(stack, null);
            return DataResult.success(Pair.of(stored, ops.empty()));
        }

        @Override public String toString() { return "StoredPrototype"; }
    };

    private record Row(int id, Stored stored) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("id").forGetter(Row::id),
                STORED.fieldOf("stack").forGetter(Row::stored)).apply(instance, Row::new));
    }

    private static Codec<GoodsSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("next_id", 1).forGetter(section -> section.table.nextId()),
                Row.CODEC.listOf().optionalFieldOf("prototypes", List.of()).forGetter(GoodsSection::rows))
                .apply(instance, GoodsSection::fromRows));
    }

    private synchronized List<Row> rows() {
        List<Row> rows = new ArrayList<>(table.size() + unreadable.size());
        table.snapshot().forEach((id, stack) -> rows.add(new Row(id, new Stored(stack.copy(), null))));
        unreadable.forEach((id, raw) -> rows.add(new Row(id, new Stored(null, raw))));
        return rows;
    }

    private static GoodsSection fromRows(int nextId, List<Row> rows) {
        GoodsSection section = new GoodsSection();
        for (Row row : rows) {
            if (row.id() <= Lot.PRISTINE) continue;
            if (row.stored().stack() == null) {
                if (!section.table.contains(row.id())) section.unreadable.putIfAbsent(row.id(), row.stored().raw());
            } else if (!section.unreadable.containsKey(row.id())) {
                section.table.restore(row.id(), row.stored().stack().copyWithCount(1));
            }
        }
        int highest = nextId;
        for (int id : section.unreadable.keySet()) highest = Math.max(highest, id + 1);
        section.table.restoreNextId(highest);
        return section;
    }
}
