package com.emergentterrain.research;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only durable reservation log for paper identifiers.
 *
 * <p>A paper ID is consumed before its draft can reach any suppression path. Unlike the paper DB
 * and per-run markdown output, this log therefore retains suppressed, timed-out, and interrupted
 * draft IDs across restarts and output archival. The log is not research evidence and must never
 * enter publication or promotion decisions; it exists solely to preserve unique audit identities.
 */
public final class PaperIdAllocationLedger {
    private final Path root;

    public PaperIdAllocationLedger(Path root) {
        this.root = root;
    }

    /**
     * Reserve the next suffix above both durable allocations and the legacy DB/file floor.
     * The file lock also protects accidental overlapping JVMs sharing the same state directory.
     */
    public synchronized long reserve(UUID roomId, long persistedFloor) {
        if (roomId == null) throw new IllegalArgumentException("roomId is required");
        Path file = fileFor(roomId);
        String prefix = "P-" + roomId.toString().substring(0, 4) + "-";
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                long last = Math.max(Math.max(0L, persistedFloor), highestReserved(file, prefix));
                long next = Math.addExact(last, 1L);
                String entry = prefix + next + "\t" + Instant.now() + System.lineSeparator();
                channel.position(channel.size());
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(entry);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
                return next;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot durably reserve a paper ID in " + file, e);
        }
    }

    /** Visible for focused restart/legacy-floor tests. */
    long highestReserved(UUID roomId) {
        if (roomId == null) return 0L;
        return highestReserved(fileFor(roomId), "P-" + roomId.toString().substring(0, 4) + "-");
    }

    private Path fileFor(UUID roomId) {
        return root.resolve(roomId + ".paper-id-allocations.log");
    }

    private static long highestReserved(Path file, String prefix) {
        if (file == null || !Files.isRegularFile(file)) return 0L;
        Pattern line = Pattern.compile("^" + Pattern.quote(prefix) + "([0-9]+)(?:\\s|$)");
        long max = 0L;
        try {
            for (String value : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher matcher = line.matcher(value);
                if (matcher.find()) {
                    try {
                        max = Math.max(max, Long.parseLong(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        // A corrupt line cannot lower the already durable high-water mark.
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read paper-ID allocation ledger " + file, e);
        }
        return max;
    }
}
