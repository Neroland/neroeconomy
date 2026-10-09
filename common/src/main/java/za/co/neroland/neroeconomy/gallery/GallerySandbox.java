package za.co.neroland.neroeconomy.gallery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.economy.EconomySection;

/**
 * The gallery's sandbox: everything {@code /neroeconomy gallery} seeds, kept apart from the real
 * economy. It is a section of the store only so that {@code gallery clear} still knows what to
 * remove after a restart.
 *
 * <ul>
 *   <li><b>Sample board rows.</b> Never in the market or book sections: no seller, no escrow, no
 *       goods, no expiry. The market search shows them to a creative level-3 viewer only, and
 *       nothing can buy, fill, match or list them, because nothing looks a row up here by id.</li>
 *   <li><b>Recorded blocks.</b> Every block the builder placed, by position and block id, so a
 *       clear removes those and only those that are still the block the gallery put there.</li>
 *   <li><b>Recorded labels.</b> The label stands the builder spawned, by entity id, with the role
 *       the live refresh uses (a ticker, the Auto-Trader loop, or a fixed title).</li>
 * </ul>
 *
 * <p>This section is neither an escrow holder nor a lot holder, so it is never part of an
 * invariant, the supply report or the goods count; it holds no player identifier, so there is
 * nothing to erase or export. Every field is optional: a store saved before it existed loads it
 * empty.
 */
public final class GallerySandbox extends EconomySection {
    public static final Codec<GallerySandbox> CODEC = codec();
    /** The most blocks one gallery records; the builder places well under half of this. */
    public static final int MAX_BLOCKS = 8_192;
    /** The most label stands one gallery records. */
    public static final int MAX_LABELS = 64;

    /** Role of a label whose text never changes. */
    public static final String ROLE_TITLE = "title";
    /** Role of the label that shows the sample Auto-Trader loop. */
    public static final String ROLE_LOOP = "loop";
    /** Prefix of the role of a ticker label; the ticker's index follows. */
    public static final String ROLE_TICKER = "ticker/";

    /** A sample board row. It names no account and holds nothing. */
    public record Row(UUID id, boolean sell, String itemId, int quantity, long unitPrice) { }

    /** A label stand the gallery spawned, and what it shows. */
    public record Label(UUID entity, String role) { }

    /** What a clear handed back: the rows dropped, and the blocks and labels to take out of the world. */
    public record Cleared(int rows, Map<Long, String> blocks, List<Label> labels) {
        public Cleared {
            blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
            labels = List.copyOf(labels);
        }
    }

    private boolean built;
    private long builtAt;
    private final List<Row> rows = new ArrayList<>();
    private final Map<Long, String> blocks = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();

    public GallerySandbox() { }

    /** The id a sample row always has: stable across rebuilds and never one a real row could draw. */
    public static UUID rowId(int index) {
        return UUID.nameUUIDFromBytes(("neroeconomy:gallery/row/" + index).getBytes(StandardCharsets.UTF_8));
    }

    /** The ticker index a label role names, or -1 when it names none. */
    public static int tickerIndex(String role) {
        if (role == null || !role.startsWith(ROLE_TICKER)) return -1;
        try {
            return Integer.parseInt(role.substring(ROLE_TICKER.length()));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    // --- building ----------------------------------------------------------------------------

    /** Whether a gallery is standing (built and not cleared since). */
    public synchronized boolean built() { return built; }

    /** Economy time the standing gallery was built at; 0 when there is none. */
    public synchronized long builtAt() { return built ? builtAt : 0L; }

    /**
     * Marks a gallery as built at {@code now} and lays out the sample board rows. Seeding again
     * replaces the rows rather than adding to them.
     */
    public synchronized void seed(long now) {
        rows.clear();
        List<GallerySamples.BoardRow> samples = GallerySamples.BOARD_ROWS;
        for (int index = 0; index < samples.size(); index++) {
            GallerySamples.BoardRow sample = samples.get(index);
            rows.add(new Row(rowId(index), sample.sell(), sample.itemId(), sample.quantity(), sample.unitPrice()));
        }
        built = true;
        builtAt = now;
        setDirty();
    }

    /** Records one placed block. @return false when the record is full or the input is unusable */
    public synchronized boolean recordBlock(long pos, String block) {
        if (block == null || block.isEmpty()) return false;
        if (!blocks.containsKey(pos) && blocks.size() >= MAX_BLOCKS) return false;
        blocks.put(pos, block);
        setDirty();
        return true;
    }

    /** Records one label stand. @return false when the record is full or the input is unusable */
    public synchronized boolean recordLabel(UUID entity, String role) {
        if (entity == null || role == null || role.isEmpty() || labels.size() >= MAX_LABELS) return false;
        labels.add(new Label(entity, role));
        setDirty();
        return true;
    }

    // --- reading -----------------------------------------------------------------------------

    /** The sample rows of one side whose item id contains {@code needle} (lower case; empty matches all). */
    public synchronized List<Row> rows(boolean sell, String needle) {
        String match = needle == null ? "" : needle.toLowerCase(Locale.ROOT).trim();
        List<Row> out = new ArrayList<>();
        for (Row row : rows)
            if (row.sell() == sell && (match.isEmpty() || row.itemId().toLowerCase(Locale.ROOT).contains(match)))
                out.add(row);
        return out;
    }

    /** Every recorded block, by packed position. */
    public synchronized Map<Long, String> blocks() { return Collections.unmodifiableMap(new LinkedHashMap<>(blocks)); }

    /** Every recorded label stand. */
    public synchronized List<Label> labels() { return List.copyOf(labels); }

    // --- clearing ----------------------------------------------------------------------------

    /** Forgets the whole gallery and hands back what has to leave the world. */
    public synchronized Cleared clear() {
        Cleared cleared = new Cleared(rows.size(), blocks, labels);
        boolean changed = built || !rows.isEmpty() || !blocks.isEmpty() || !labels.isEmpty();
        built = false;
        builtAt = 0L;
        rows.clear();
        blocks.clear();
        labels.clear();
        if (changed) setDirty();
        return cleared;
    }

    @Override public synchronized boolean isEmpty() {
        return !built && rows.isEmpty() && blocks.isEmpty() && labels.isEmpty();
    }

    // --- persistence -------------------------------------------------------------------------

    private record RowData(String id, boolean sell, String item, int quantity, long price) {
        static final Codec<RowData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(RowData::id),
                Codec.BOOL.fieldOf("sell").forGetter(RowData::sell),
                Codec.STRING.fieldOf("item").forGetter(RowData::item),
                Codec.INT.fieldOf("quantity").forGetter(RowData::quantity),
                Codec.LONG.fieldOf("price").forGetter(RowData::price)).apply(instance, RowData::new));
    }

    private record BlockData(long pos, String block) {
        static final Codec<BlockData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("pos").forGetter(BlockData::pos),
                Codec.STRING.fieldOf("block").forGetter(BlockData::block)).apply(instance, BlockData::new));
    }

    private record LabelData(String entity, String role) {
        static final Codec<LabelData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("entity").forGetter(LabelData::entity),
                Codec.STRING.fieldOf("role").forGetter(LabelData::role)).apply(instance, LabelData::new));
    }

    private static Codec<GallerySandbox> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("built", false).forGetter(section -> section.built),
                Codec.LONG.optionalFieldOf("built_at", 0L).forGetter(section -> section.builtAt),
                RowData.CODEC.listOf().optionalFieldOf("rows", List.of()).forGetter(GallerySandbox::rowData),
                BlockData.CODEC.listOf().optionalFieldOf("blocks", List.of()).forGetter(GallerySandbox::blockData),
                LabelData.CODEC.listOf().optionalFieldOf("labels", List.of()).forGetter(GallerySandbox::labelData))
                .apply(instance, GallerySandbox::fromData));
    }

    private synchronized List<RowData> rowData() {
        List<RowData> out = new ArrayList<>(rows.size());
        for (Row row : rows)
            out.add(new RowData(row.id().toString(), row.sell(), row.itemId(), row.quantity(), row.unitPrice()));
        return out;
    }

    private synchronized List<BlockData> blockData() {
        List<BlockData> out = new ArrayList<>(blocks.size());
        blocks.forEach((pos, block) -> out.add(new BlockData(pos, block)));
        return out;
    }

    private synchronized List<LabelData> labelData() {
        List<LabelData> out = new ArrayList<>(labels.size());
        for (Label label : labels) out.add(new LabelData(label.entity().toString(), label.role()));
        return out;
    }

    private static GallerySandbox fromData(boolean built, long builtAt, List<RowData> rows, List<BlockData> blocks,
            List<LabelData> labels) {
        GallerySandbox section = new GallerySandbox();
        section.built = built;
        section.builtAt = Math.max(0L, builtAt);
        // Unreadable entries are dropped, and the bounds apply to what is read as to what is recorded.
        for (RowData row : rows) {
            UUID id = parse(row.id());
            if (id != null && row.quantity() > 0 && row.price() > 0L && !row.item().isEmpty())
                section.rows.add(new Row(id, row.sell(), row.item(), row.quantity(), row.price()));
        }
        for (BlockData block : blocks)
            if (section.blocks.size() < MAX_BLOCKS && !block.block().isEmpty())
                section.blocks.put(block.pos(), block.block());
        for (LabelData label : labels) {
            UUID entity = parse(label.entity());
            if (entity != null && section.labels.size() < MAX_LABELS && !label.role().isEmpty())
                section.labels.add(new Label(entity, label.role()));
        }
        return section;
    }

    private static UUID parse(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
