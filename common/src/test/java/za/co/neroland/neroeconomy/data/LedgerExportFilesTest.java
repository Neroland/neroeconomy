package za.co.neroland.neroeconomy.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerExportFilesTest {
    private static final UUID SUBJECT = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test void reportableKeepsOnlyTheFailureClass() {
        IOException real = new NoSuchFileException("/srv/world/neroeconomy-exports/" + SUBJECT + ".json");
        IOException reported = LedgerExportFiles.reportable(real);
        assertEquals("Export file operation failed: java.nio.file.NoSuchFileException", reported.getMessage());
        assertFalse(reported.getMessage().contains(SUBJECT.toString()));
        assertFalse(reported.getMessage().contains("neroeconomy-exports"));
        // The original is not chained either: its message is exactly what must not travel.
        assertNull(reported.getCause());
        assertEquals("Export file operation failed: unknown", LedgerExportFiles.reportable(null).getMessage());
    }

    @Test void writeCreatesReplacesAndRestrictsTheFile(@TempDir Path world) throws IOException {
        Path root = world.resolve("neroeconomy-exports");
        Path output = root.resolve(SUBJECT + ".json");
        assertEquals(output, LedgerExportFiles.writeRestricted(root, output, "{\"balance\":1}"));
        assertEquals("{\"balance\":1}", Files.readString(output, StandardCharsets.UTF_8));
        // A second export replaces the first rather than failing on the existing file.
        LedgerExportFiles.writeRestricted(root, output, "{}");
        assertEquals("{}", Files.readString(output, StandardCharsets.UTF_8));
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(output);
        } catch (UnsupportedOperationException notPosix) {
            return; // Windows: no permission view, and the write above must simply have worked.
        }
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions);
        assertTrue(Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")));
    }
}
