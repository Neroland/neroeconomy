package za.co.neroland.neroeconomy.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoreDataExportTest {
    @AfterEach void clear() {
        za.co.neroland.nerolandcore.data.PlayerDataExport.clearForTests();
    }

    @Test void registersUnderTheModId() {
        CoreDataExport.register();
        assertTrue(za.co.neroland.nerolandcore.data.PlayerDataExport.registeredIds().contains("neroeconomy"));
    }
}
