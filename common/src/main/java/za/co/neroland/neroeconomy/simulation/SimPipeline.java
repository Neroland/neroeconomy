package za.co.neroland.neroeconomy.simulation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * The ordered list of {@link SimStep} handlers that make up one simulation step. Handlers run in
 * the order they were registered. One that throws is caught, reported once per session and skipped
 * for that step; the pipeline never stops, so a broken handler cannot stall expiry or the Reserve.
 */
public final class SimPipeline {
    private final List<SimStep> handlers = new CopyOnWriteArrayList<>();
    /** Handlers whose failure has already been reported in this session. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * Adds a handler after the ones already registered. Register during mod start-up, before a
     * server runs. A name must be unique: it keys the handler's random streams.
     */
    public void register(SimStep handler) {
        if (handler == null || handler.name() == null || handler.name().isBlank())
            throw new IllegalArgumentException("Simulation handler needs a name");
        for (SimStep known : handlers)
            if (known.name().equals(handler.name()))
                throw new IllegalArgumentException("Duplicate simulation handler: " + handler.name());
        handlers.add(handler);
    }

    /** The registered handler names, in running order. */
    public List<String> names() { return handlers.stream().map(SimStep::name).toList(); }

    /**
     * Runs one step: every handler, in order. With {@code autonomousEconomy} off only housekeeping
     * handlers run.
     *
     * @param failures told the handler's name and the exception the first time a handler fails in
     *                 this session; may be null
     * @return how many handlers ran to the end
     */
    public int run(SimContext context, BiConsumer<String, RuntimeException> failures) {
        int completed = 0;
        for (SimStep handler : handlers) {
            if (!context.autonomous() && !handler.housekeeping()) continue;
            try {
                handler.run(context.forHandler(handler.name()), context.step());
                completed++;
            } catch (RuntimeException failure) {
                if (reported.add(handler.name()) && failures != null) failures.accept(handler.name(), failure);
            }
        }
        return completed;
    }

    /** A new session: every handler may be reported once again. */
    public void resetReports() { reported.clear(); }
}
