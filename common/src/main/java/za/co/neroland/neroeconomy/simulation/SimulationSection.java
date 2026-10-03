package za.co.neroland.neroeconomy.simulation;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * The simulation section of the economy store: where the simulation has got to, and the world's
 * secret.
 *
 * <ul>
 *   <li><b>Last step.</b> The index of the last step that ran to the end, saved after every step,
 *       so a restart in the middle of a catch-up carries on where it stopped. The step length it
 *       was counted in is saved beside it, so changing the length rebases the index.</li>
 *   <li><b>The secret.</b> 32 bytes from {@link SecureRandom}, made once per world. It is the key
 *       of every simulation random stream. It is never derived from the world seed, never logged,
 *       never exported and never sent to a client or the companion app; it is only ever handed to
 *       {@link SimRandom} inside the server.</li>
 *   <li><b>Handler state.</b> Named whole numbers a handler may keep between steps. A stage that
 *       needs more than that adds its own optional field to this section's codec, or its own
 *       section.</li>
 * </ul>
 *
 * <p>Nothing here is about a player: there is nothing to erase and nothing to export.
 */
public final class SimulationSection extends EconomySection {
    public static final Codec<SimulationSection> CODEC = codec();
    public static final int SECRET_BYTES = 32;
    /** The most handler-state entries kept; a handler that needs more needs its own section. */
    public static final int MAX_STATE_ENTRIES = 4096;
    public static final int MAX_STATE_KEY_LENGTH = 128;

    private long lastStep = SimClock.NEVER;
    private long stepMillis;
    private byte[] secret;
    private final Map<String, Long> state = new LinkedHashMap<>();

    public SimulationSection() { }

    /** The simulation section of the world's economy store. */
    public static SimulationSection get(MinecraftServer server) { return EconomyStore.get(server).simulation(); }

    // --- the clock ---------------------------------------------------------------------------

    /** The last completed step, or {@link SimClock#NEVER}. */
    public synchronized long lastStep() { return lastStep; }

    /** The step length {@link #lastStep} is counted in; 0 before the first step. */
    public synchronized long stepMillis() { return stepMillis; }

    /**
     * Makes {@code length} the step length the index is counted in, rebasing the index if the
     * length has changed since it was saved.
     */
    public synchronized void useStepLength(long length) {
        if (length <= 0L || length == stepMillis) return;
        lastStep = SimClock.rebase(lastStep, stepMillis, length);
        stepMillis = length;
        setDirty();
    }

    /** Records how far the simulation has got: after a step, a skip forward, or the very first start. */
    public synchronized void setLastStep(long step) {
        if (step == lastStep) return;
        lastStep = step;
        setDirty();
    }

    // --- the secret --------------------------------------------------------------------------

    /**
     * The world's secret, made on first use. Package-private on purpose: only the simulation hands
     * it to {@link SimRandom}. The array returned is a copy.
     */
    synchronized byte[] secret() {
        if (secret == null) {
            secret = new byte[SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            setDirty();
        }
        return secret.clone();
    }

    /** Test seam: a fixed secret, so two runs can be compared. Never called by the game. */
    synchronized void secretForTest(byte[] fixed) {
        secret = fixed == null ? null : fixed.clone();
    }

    // --- handler state -----------------------------------------------------------------------

    /** A number a handler saved under {@code key}, or {@code fallback}. */
    public synchronized long state(String key, long fallback) {
        Long value = key == null ? null : state.get(key);
        return value == null ? fallback : value;
    }

    /**
     * Saves a number under {@code key}. Keys are fixed names chosen by the handler
     * ({@code "house.restock"}) — never an account, a player or anything else personal.
     *
     * @return false when the key is not usable or the table is full
     */
    public synchronized boolean setState(String key, long value) {
        if (key == null || key.isBlank() || key.length() > MAX_STATE_KEY_LENGTH) return false;
        if (!state.containsKey(key) && state.size() >= MAX_STATE_ENTRIES) return false;
        Long old = state.put(key, value);
        if (old == null || old != value) setDirty();
        return true;
    }

    public synchronized boolean clearState(String key) {
        if (key == null || state.remove(key) == null) return false;
        setDirty();
        return true;
    }

    /** Nothing has run and no secret exists yet. */
    @Override public synchronized boolean isEmpty() {
        return lastStep == SimClock.NEVER && secret == null && state.isEmpty();
    }

    // --- persistence -------------------------------------------------------------------------

    private record StateRow(String key, long value) {
        static final Codec<StateRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("key").forGetter(StateRow::key),
                Codec.LONG.fieldOf("value").forGetter(StateRow::value)).apply(instance, StateRow::new));
    }

    // Every field is optional with a safe default: a store saved before the simulation existed
    // loads as one that has never run a step and has no secret yet.
    private static Codec<SimulationSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("last_step", SimClock.NEVER).forGetter(section -> section.lastStep),
                Codec.LONG.optionalFieldOf("step_millis", 0L).forGetter(section -> section.stepMillis),
                Codec.STRING.optionalFieldOf("secret", "").forGetter(SimulationSection::secretHex),
                StateRow.CODEC.listOf().optionalFieldOf("state", List.of()).forGetter(SimulationSection::stateRows))
                .apply(instance, SimulationSection::fromData));
    }

    private synchronized String secretHex() { return secret == null ? "" : HexFormat.of().formatHex(secret); }

    private synchronized List<StateRow> stateRows() {
        List<StateRow> rows = new ArrayList<>(state.size());
        state.forEach((key, value) -> rows.add(new StateRow(key, value)));
        return rows;
    }

    private static SimulationSection fromData(long lastStep, long stepMillis, String secretHex, List<StateRow> rows) {
        SimulationSection section = new SimulationSection();
        section.lastStep = lastStep < 0L ? SimClock.NEVER : lastStep;
        section.stepMillis = Math.max(0L, stepMillis);
        try {
            byte[] decoded = HexFormat.of().parseHex(secretHex);
            // A secret of the wrong size is not used; a new one is made on first use instead.
            if (decoded.length == SECRET_BYTES) section.secret = decoded;
        } catch (IllegalArgumentException malformed) {
            section.secret = null;
        }
        for (StateRow row : rows) {
            if (row.key() == null || row.key().isBlank() || row.key().length() > MAX_STATE_KEY_LENGTH) continue;
            if (section.state.size() >= MAX_STATE_ENTRIES) break;
            section.state.put(row.key(), row.value());
        }
        return section;
    }
}
