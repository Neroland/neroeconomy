package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Abuse rule "crash between stores": goods that move between the economy store and a live
 * inventory, a chunk or a block entity span two files. The game saves players, chunks and saved
 * data together, players first; if the mod wrote the store on its own a second after such a move,
 * a crash before the next game save would find the goods in the store and still in the player's
 * file (a deposit duplicated) or in neither (a withdrawal lost). So nothing in the mod may write
 * the store ahead of the game's own save.
 */
class SaveOrderAuditTest {
    private static final List<String> EARLY_SAVES = List.of("requestSave(", "scheduleSave(", "saveAndJoin(",
            ".save(", "saveAll(", "saveEverything(");

    @Test
    void nothingWritesTheEconomyStoreAheadOfTheGamesOwnSave() throws IOException {
        Path java = SourceTree.main().resolve("java");
        List<String> found = new ArrayList<>();
        for (Path file : SourceTree.files(java, path -> path.toString().endsWith(".java"))) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                for (String call : EARLY_SAVES)
                    if (line.contains(call) && !allowed(line))
                        found.add(java.relativize(file) + ":" + (i + 1) + " " + line);
            }
        }
        assertEquals(List.of(), found);
    }

    /** Writes that are not of the store: export files and Core's own backup are separate files. */
    private static boolean allowed(String line) {
        return line.contains("Files.") || line.contains("NbtIo.") || line.contains("backupNow(");
    }
}
