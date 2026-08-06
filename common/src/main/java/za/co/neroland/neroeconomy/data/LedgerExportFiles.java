package za.co.neroland.neroeconomy.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/**
 * Lifecycle for the on-disk data-subject export files. An export is a one-off deliverable, so it is
 * bounded by its own short retention window and is deleted outright when the subject is erased.
 */
public final class LedgerExportFiles {
    private static final String DIRECTORY = "neroeconomy-exports";
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int MAX_RETENTION_DAYS = 7;

    private LedgerExportFiles() { }

    public static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).normalize();
    }

    /** Resolves one subject's export path, refusing anything that escapes the export directory. */
    public static Path file(MinecraftServer server, UUID player) {
        Path root = directory(server);
        Path output = root.resolve(player + ".json").normalize();
        if (!output.startsWith(root)) throw new IllegalStateException("Unsafe export path");
        return output;
    }

    /** Erasure hook: an export must not outlive the ledger rows it was taken from. */
    public static void delete(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        try {
            Files.deleteIfExists(file(server, player));
        } catch (IOException | RuntimeException failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A data-subject export file could not be deleted.");
            NeroEconomyTelemetry.captureHandledException(failure, "export_erasure");
        }
    }

    /** Daily sweep: drops exports past the retention window, capped well below the audit window. */
    public static void purgeExpired(MinecraftServer server, long now) {
        if (server == null) return;
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = now - (days <= 0 ? 0L : Math.min(days, MAX_RETENTION_DAYS) * MILLIS_PER_DAY);
        Path root = directory(server);
        if (!Files.isDirectory(root)) return;
        try {
            List<Path> files;
            try (Stream<Path> listing = Files.list(root)) { files = listing.toList(); }
            for (Path file : files) {
                if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".json")) continue;
                if (Files.getLastModifiedTime(file).toMillis() > cutoff) continue;
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException failure) {
            NeroEconomyTelemetry.captureHandledException(failure, "export_retention");
        }
    }
}
