package za.co.neroland.neroeconomy.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * String-keyed soft seam used by a logistics mod when present; otherwise no caller invokes it.
 *
 * <p>Nothing here references a foreign class: presence is decided at runtime through
 * {@link #logisticsPresent()} only where behaviour actually differs, so the mod compiles and runs
 * identically with or without a logistics mod installed.
 */
public final class DeliveryJobApi {
    /**
     * Creates the server-funded jobs posted through {@link #postSystem}. The obligation store
     * installs it during its own setup; until then system jobs are simply refused, because a job
     * whose payout has no funding source must never enter the store.
     */
    @FunctionalInterface
    public interface SystemIssuer {
        EconomyJob post(MinecraftServer server, JobType type, String itemOrTag, int quantity, String destination,
                long payout, long expiresAt);
    }

    private static volatile SystemIssuer systemIssuer;

    private DeliveryJobApi() { }

    public static EconomyJob post(ServerPlayer issuer, String itemOrTag, int quantity, String destination,
            long payout, long expiresAt) {
        if (issuer == null) return null;
        return ObligationSavedData.get(issuer.level().getServer()).postJob(issuer, JobType.DELIVERY,
                itemOrTag, quantity, destination, payout, expiresAt);
    }

    /**
     * Posts a delivery job with no player issuer, for automation that belongs to the server rather
     * than to anyone's account.
     *
     * <p>Funding differs from {@link #post}: there is no player wallet to escrow from, so the payout
     * is minted by the server account when the job settles. Servers that do not want minted payouts
     * should leave the issuer uninstalled, in which case this returns {@code null} and no job exists.
     */
    public static EconomyJob postSystem(MinecraftServer server, String itemOrTag, int quantity, String destination,
            long payout, long expiresAt) {
        SystemIssuer issuer = systemIssuer;
        if (server == null || issuer == null || itemOrTag == null || itemOrTag.isBlank() || quantity < 1
                || destination == null || payout < 1L || expiresAt <= System.currentTimeMillis()) {
            if (issuer == null) NeroEconomyCommon.LOGGER.debug(
                    "[NeroEconomy] System delivery job refused: no system issuer installed.");
            return null;
        }
        return issuer.post(server, JobType.DELIVERY, itemOrTag, quantity, destination, payout, expiresAt);
    }

    /** Installs the server-funded poster. Called once by the obligation store. */
    public static void setSystemIssuer(SystemIssuer issuer) { systemIssuer = issuer; }

    /** Runtime-only presence check; never resolve a logistics class to answer this. */
    public static boolean logisticsPresent() { return Services.PLATFORM.isModLoaded("nerologistics"); }
}
