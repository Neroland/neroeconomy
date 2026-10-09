package za.co.neroland.neroeconomy.text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The repository's {@code common/src/main} on disk, for the tests that audit sources and resources
 * rather than behaviour. Found by walking up from the working directory, which is a loader node.
 */
public final class SourceTree {
    private SourceTree() { }

    public static Path main() throws IOException {
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path candidate = at.resolve("common/src/main");
            if (Files.isDirectory(candidate.resolve("java"))) return candidate;
        }
        throw new IOException("common/src/main not found above " + System.getProperty("user.dir"));
    }

    /** Every regular file under {@code root} that {@code filter} accepts, in a stable order. */
    public static List<Path> files(Path root, Predicate<Path> filter) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(filter).sorted().toList();
        }
    }
}
