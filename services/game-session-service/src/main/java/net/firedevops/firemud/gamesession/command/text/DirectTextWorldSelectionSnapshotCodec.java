package net.firedevops.firemud.gamesession.command.text;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.Snapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Internal storage payload only; decoding cannot construct a usable selection handle. */
final class DirectTextWorldSelectionSnapshotCodec {
  static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  private static final String SCHEMA = "direct-text-world-selection/v1";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private DirectTextWorldSelectionSnapshotCodec() {}

  static byte[] encode(Snapshot snapshot) {
    try {
      var output = new BoundedPayloadOutput();
      JSON.writeValue(output, new Envelope(SCHEMA, snapshot));
      return output.toByteArray();
    } catch (JacksonException invalid) {
      throw new IllegalArgumentException("World selection payload cannot be encoded", invalid);
    }
  }

  static Optional<Snapshot> decode(byte[] stored) {
    if (stored == null || stored.length == 0 || stored.length > MAX_PAYLOAD_BYTES) {
      return Optional.empty();
    }
    try {
      Envelope envelope = JSON.readValue(stored, Envelope.class);
      if (envelope == null || !SCHEMA.equals(envelope.schema())) {
        return Optional.empty();
      }
      // Accept only the codec's exact encoding, not coercions, repaired record defaults, alternate
      // timestamp/UUID forms or another character encoding. No parallel per-record schema needed.
      if (!Arrays.equals(stored, encode(envelope.snapshot()))) {
        return Optional.empty();
      }
      return Optional.of(envelope.snapshot());
    } catch (JacksonException | IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  private record Envelope(String schema, Snapshot snapshot) {
    private Envelope {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  private static final class BoundedPayloadOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void write(int value) {
      requireCapacity(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      requireCapacity(length);
      super.write(bytes, offset, length);
    }

    private void requireCapacity(int additionalBytes) {
      if (additionalBytes > MAX_PAYLOAD_BYTES - count) {
        throw new IllegalArgumentException("World selection payload exceeds 1 MiB");
      }
    }
  }
}
