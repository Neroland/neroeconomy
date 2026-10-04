package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The companies listed on the exchange, as the price model sees them. The game answers from the
 * data packs ({@link StockData}); a test answers with definitions of its own ({@link #of}).
 */
public interface StockRegistry {
    /** The most companies that may be listed at once; a data pack that ships more has the rest skipped. */
    int MAX_LISTED = 64;

    /** Every listed company, in id order. Companies whose file says {@code "enabled": false} are not in it. */
    List<StockDefinition> stocks();

    /** The listed company with this id, or null. */
    StockDefinition get(String id);

    /**
     * False when the data could not be read at all, which is not the same as nothing being listed:
     * the exchange then leaves every company as it is instead of delisting them all.
     */
    default boolean available() { return true; }

    /** Nothing is listed. */
    StockRegistry EMPTY = of(List.of());

    /** The data could not be read: nothing is known, and nothing may be listed or delisted from it. */
    StockRegistry UNAVAILABLE = new StockRegistry() {
        @Override public List<StockDefinition> stocks() { return List.of(); }
        @Override public StockDefinition get(String id) { return null; }
        @Override public boolean available() { return false; }
    };

    /**
     * A fixed registry of {@code definitions}: the enabled ones, in id order, at most
     * {@link #MAX_LISTED} of them. A later definition with an id already seen replaces the earlier.
     */
    static StockRegistry of(Collection<StockDefinition> definitions) {
        Map<String, StockDefinition> byId = new LinkedHashMap<>();
        if (definitions != null)
            for (StockDefinition definition : definitions)
                if (definition != null && definition.enabled()) byId.put(definition.id(), definition);
        List<StockDefinition> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparing(StockDefinition::id));
        List<StockDefinition> listed = List.copyOf(sorted.size() > MAX_LISTED ? sorted.subList(0, MAX_LISTED) : sorted);
        Map<String, StockDefinition> index = new LinkedHashMap<>();
        for (StockDefinition definition : listed) index.put(definition.id(), definition);
        return new StockRegistry() {
            @Override public List<StockDefinition> stocks() { return listed; }
            @Override public StockDefinition get(String id) { return id == null ? null : index.get(id); }
        };
    }
}
