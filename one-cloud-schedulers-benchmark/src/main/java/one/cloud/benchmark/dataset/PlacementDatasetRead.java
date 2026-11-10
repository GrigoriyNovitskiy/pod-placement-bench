package one.cloud.benchmark.dataset;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import com.google.protobuf.CodedInputStream;

import one.cloud.benchmark.dataset.PlacementDatasetProtos.Record;

public final class PlacementDatasetRead {
    private static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;

    private PlacementDatasetRead() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length == 0 || arguments.length > 2) {
            throw new IllegalArgumentException("Usage: runPlacementDatasetRead --args='SNAPSHOT.pb [OPERATIONS.pb]'");
        }
        final long start = System.nanoTime();
        final var dataset = arguments.length == 1 ? PlacementDataset.load(Path.of(arguments[0]))
                : PlacementDataset.load(Path.of(arguments[0]), Path.of(arguments[1]));
        System.out.printf(Locale.ROOT, "{\"resources\":%d,\"hosts\":%d,\"placements\":%d,\"operations\":%d,\"load_ms\":%.3f}%n",
                dataset.alloc.length - 1, dataset.hostCount, dataset.snapshot.resource.length,
                dataset.operations.resource.length, (System.nanoTime() - start) / 1_000_000.0);
    }

    public static long read(Path path, Consumer<Record> consumer) throws IOException {
        try (final var stream = new BufferedInputStream(Files.newInputStream(path), 1024 * 1024)) {
            final var input = CodedInputStream.newInstance(stream);
            input.setSizeLimit(MAX_RECORD_BYTES + 10);
            input.setRecursionLimit(16);
            long count = 0;
            while (!input.isAtEnd()) {
                consumer.accept(readRecord(input));
                count++;
            }
            return count;
        }
    }

    private static Record readRecord(CodedInputStream input) throws IOException {
        final int length = input.readRawVarint32();
        if (length <= 0 || length > MAX_RECORD_BYTES) {
            throw new IOException("Invalid protobuf record length");
        }
        final int limit = input.pushLimit(length);
        final var record = Record.parseFrom(input);
        if (input.getBytesUntilLimit() != 0 || record.getPayloadCase() == Record.PayloadCase.PAYLOAD_NOT_SET) {
            throw new IOException("Incomplete protobuf record");
        }
        input.popLimit(limit);
        input.resetSizeCounter();
        return record;
    }
}
