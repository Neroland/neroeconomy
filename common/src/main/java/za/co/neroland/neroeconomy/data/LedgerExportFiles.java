package za.co.neroland.neroeconomy.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
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
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");

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

    /**
     * Writes one subject's export, replacing any earlier one. Where the file system has POSIX
     * permissions the directory and the file are restricted to the server's own user before any
     * content is written; elsewhere (Windows) the platform defaults apply, exactly as before.
     */
    public static Path write(MinecraftServer server, UUID player, String json) throws IOException {
        return writeRestricted(directory(server), file(server, player), json);
    }

    /** The file-system half of {@link #write}, separated so it can be tested against a plain directory. */
    static Path writeRestricted(Path root, Path output, String json) throws IOException {
        Files.createDirectories(root);
        try {
            Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
        } catch (UnsupportedOperationException | IOException bestEffort) {
            // Not a POSIX file system, or a directory this process may not re-mode: the file is still restricted.
        }
        try {
            if (Files.exists(output)) Files.setPosixFilePermissions(output, OWNER_FILE);
            else Files.createFile(output, PosixFilePermissions.asFileAttribute(OWNER_FILE));
        } catch (UnsupportedOperationException notPosix) {
            // No POSIX permission view on this file system; nothing to restrict.
        }
        Files.writeString(output, json, StandardCharsets.UTF_8);
        return output;
    }

    /** Erasure hook: an export must not outlive the ledger rows it was taken from. */
    public static void delete(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        try {
            Files.deleteIfExists(file(server, player));
        } catch (IOException | RuntimeException failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A data-subject export file could not be deleted.");
            NeroEconomyTelemetry.captureHandledException(reportable(failure), "export_erasure");
        }
    }

    /**
     * What telemetry is given for a failed export-file operation. The real exception's message holds
     * the file path, and the file name is the subject's UUID, so only the failure's class is kept.
     */
    public static IOException reportable(Throwable failure) {
        return new IOException("Export file operation failed: "
                + (failure == null ? "unknown" : failure.getClass().getName()));
    }

    /**
     * Retention sweep, run when the server binds and then daily: drops exports past the retention
     * window, capped well below the audit window. {@code now} is the <b>wall clock</b>, because it is
     * compared with file-system modification times rather than with economy timestamps.
     */
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
            NeroEconomyTelemetry.captureHandledException(reportable(failure), "export_retention");
        }
    }
}
