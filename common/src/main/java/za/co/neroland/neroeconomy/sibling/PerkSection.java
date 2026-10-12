package za.co.neroland.neroeconomy.sibling;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * The perks a quest pack's {@code mod_hook} rewards grant through {@link QuestHooks}: for now one
 * flag per player, a waiver of their next listing fee. A flag, never a credit: nothing here mints
 * currency, and a player holds at most one waiver at a time however many quests they complete.
 * Personal: erased with the player ({@link #forgetPlayer}), exported ({@link #exportFor}), at most
 * {@link #MAX_PLAYERS} rows; when full, a new waiver is not recorded. The field is optional, so a
 * store saved before this section existed loads it empty.
 */
public final class PerkSection extends EconomySection {
    public static final Codec<PerkSection> CODEC = codec();
    public static final int MAX_PLAYERS = 100_000;

    private final Set<UUID> feeWaivers = new LinkedHashSet<>();

    public PerkSection() { }

    /** Grants {@code player} a waiver of their next listing fee. @return true when they did not hold one */
    public synchronized boolean grantFeeWaiver(UUID player) {
        if (player == null || feeWaivers.contains(player) || feeWaivers.size() >= MAX_PLAYERS) return false;
        feeWaivers.add(player);
        setDirty();
        return true;
    }

    /** Whether {@code player} holds a waiver of their next listing fee. */
    public synchronized boolean hasFeeWaiver(UUID player) {
        return player != null && feeWaivers.contains(player);
    }

    /** Spends {@code player}'s waiver. @return whether they held one */
    public synchronized boolean useFeeWaiver(UUID player) {
        if (player == null || !feeWaivers.remove(player)) return false;
        setDirty();
        return true;
    }

    public synchronized int size() { return feeWaivers.size(); }

    // --- privacy -----------------------------------------------------------------------------

    /** Erasure entry point, registered first in {@code NeroEconomyCommon.init()}. Tolerates an unknown player. */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).perks().forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    public synchronized void forgetPlayer(UUID player) {
        if (player != null && feeWaivers.remove(player)) setDirty();
    }

    /** The subject's own row for the data export: whether a listing-fee waiver waits for them. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        if (hasFeeWaiver(player)) out.addProperty("fee_waiver_pending", true);
        return out;
    }

    @Override public synchronized boolean isEmpty() { return feeWaivers.isEmpty(); }

    // --- persistence -------------------------------------------------------------------------

    private static Codec<PerkSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("fee_waivers", List.of()).forGetter(PerkSection::savedWaivers))
                .apply(instance, PerkSection::fromSaved));
    }

    private synchronized List<String> savedWaivers() {
        List<String> out = new ArrayList<>(feeWaivers.size());
        for (UUID player : feeWaivers) out.add(player.toString());
        return out;
    }

    private static PerkSection fromSaved(List<String> waivers) {
        PerkSection section = new PerkSection();
        for (String text : waivers) {
            if (section.feeWaivers.size() >= MAX_PLAYERS) break;
            try {
                section.feeWaivers.add(UUID.fromString(text));
            } catch (IllegalArgumentException malformed) {
                // skipped
            }
        }
        return section;
    }
}
