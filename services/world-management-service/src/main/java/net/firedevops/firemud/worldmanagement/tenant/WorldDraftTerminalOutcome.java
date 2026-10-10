package net.firedevops.firemud.worldmanagement.tenant;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;

/**
 * Immutable World-local definitive no-commit evidence; it is never an APPLIED result or permission.
 */
public final class WorldDraftTerminalOutcome {
  private static final String SCHEMA = "world-draft-terminal-outcome/v1";
  private static final String ABORTED = "DEFINITIVELY_ABORTED";

  /** Epoch observed while the V25 World owner row was locked for the no-commit decision. */
  public record ObservedEpoch(AffectedUnit unit, String epoch) {
    public ObservedEpoch {
      Objects.requireNonNull(unit, "unit");
      requireDecimal(epoch);
      if (!epoch.equals(unit.expectedEpoch())) {
        throw new IllegalArgumentException("Observed World epoch differs from the expected epoch");
      }
    }
  }

  private final WorldDraftTerminalOperation operation;
  private final List<ObservedEpoch> observedEpochs;
  private final byte[] canonicalBytes;
  private final String digest;
  private final OffsetDateTime recordedAt;

  private WorldDraftTerminalOutcome(
      WorldDraftTerminalOperation operation,
      List<ObservedEpoch> observedEpochs,
      byte[] canonicalBytes,
      String digest,
      OffsetDateTime recordedAt) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.observedEpochs = List.copyOf(observedEpochs);
    this.canonicalBytes = canonicalBytes.clone();
    this.digest = Objects.requireNonNull(digest, "digest");
    this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    requireCompleteEpochVector(operation, this.observedEpochs);
    if (!digest(this.canonicalBytes).equals(digest)
        || !Arrays.equals(this.canonicalBytes, encode(operation, this.observedEpochs))) {
      throw new IllegalArgumentException(
          "Stored World terminal outcome is not exact canonical evidence");
    }
  }

  public static WorldDraftTerminalOutcome create(
      WorldDraftTerminalOperation operation,
      List<ObservedEpoch> observedEpochs,
      OffsetDateTime recordedAt) {
    List<ObservedEpoch> copy = List.copyOf(observedEpochs);
    requireCompleteEpochVector(operation, copy);
    byte[] encoded = encode(operation, copy);
    return new WorldDraftTerminalOutcome(operation, copy, encoded, digest(encoded), recordedAt);
  }

  public static WorldDraftTerminalOutcome fromStored(
      WorldDraftTerminalOperation operation,
      byte[] canonicalBytes,
      String storedDigest,
      OffsetDateTime recordedAt) {
    List<ObservedEpoch> parsed = decode(operation, canonicalBytes);
    return new WorldDraftTerminalOutcome(
        operation, parsed, canonicalBytes, storedDigest, recordedAt);
  }

  public WorldDraftTerminalOperation operation() {
    return operation;
  }

  public List<ObservedEpoch> observedEpochs() {
    return List.copyOf(observedEpochs);
  }

  public String outcome() {
    return ABORTED;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  public OffsetDateTime recordedAt() {
    return recordedAt;
  }

  private static byte[] encode(
      WorldDraftTerminalOperation operation, List<ObservedEpoch> observedEpochs) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    frame(output, SCHEMA);
    frame(output, ABORTED);
    frame(output, operation.canonicalBytes());
    frame(output, Integer.toString(observedEpochs.size()));
    for (ObservedEpoch observed : observedEpochs) {
      AffectedUnit unit = observed.unit();
      frame(output, unit.owner().name());
      frame(output, unit.aggregateType());
      frame(output, unit.aggregateId());
      frame(output, unit.scopeType());
      frame(output, unit.scopeId());
      frame(output, unit.expectedEpoch());
      frame(output, observed.epoch());
    }
    return output.toByteArray();
  }

  private static List<ObservedEpoch> decode(
      WorldDraftTerminalOperation operation, byte[] canonicalBytes) {
    Objects.requireNonNull(canonicalBytes, "canonicalBytes");
    FrameReader reader = new FrameReader(canonicalBytes);
    reader.expect(SCHEMA);
    reader.expect(ABORTED);
    if (!Arrays.equals(reader.bytes(), operation.canonicalBytes())) {
      throw new IllegalArgumentException("World terminal outcome binds another operation");
    }
    int count = reader.positiveCount();
    List<ObservedEpoch> result = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      DraftCommitBinding.Owner owner = DraftCommitBinding.Owner.valueOf(reader.text());
      AffectedUnit unit =
          new AffectedUnit(
              owner, reader.text(), reader.text(), reader.text(), reader.text(), reader.text());
      result.add(new ObservedEpoch(unit, reader.text()));
    }
    reader.requireEnd();
    requireCompleteEpochVector(operation, result);
    return List.copyOf(result);
  }

  private static void requireCompleteEpochVector(
      WorldDraftTerminalOperation operation, List<ObservedEpoch> observedEpochs) {
    List<AffectedUnit> expected =
        operation.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    if (observedEpochs.size() != expected.size()) {
      throw new IllegalArgumentException(
          "Definitive World abort requires the complete affected set");
    }
    for (int index = 0; index < expected.size(); index++) {
      if (!expected.get(index).equals(observedEpochs.get(index).unit())
          || !expected.get(index).expectedEpoch().equals(observedEpochs.get(index).epoch())) {
        throw new IllegalArgumentException(
            "World abort epoch vector differs from exact Draft binding");
      }
    }
  }

  private static void frame(ByteArrayOutputStream output, String value) {
    frame(output, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(ByteArrayOutputStream output, byte[] value) {
    try {
      DataOutputStream data = new DataOutputStream(output);
      data.writeInt(value.length);
      data.write(value);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void requireDecimal(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical epoch decimal required");
    }
  }

  private static final class FrameReader {
    private final ByteBuffer buffer;

    private FrameReader(byte[] bytes) {
      buffer = ByteBuffer.wrap(bytes);
    }

    private byte[] bytes() {
      if (buffer.remaining() < Integer.BYTES) {
        throw new IllegalArgumentException("Truncated World terminal evidence");
      }
      int size = buffer.getInt();
      if (size <= 0 || size > buffer.remaining()) {
        throw new IllegalArgumentException("Invalid World terminal evidence frame size");
      }
      byte[] value = new byte[size];
      buffer.get(value);
      return value;
    }

    private String text() {
      byte[] bytes = bytes();
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
      } catch (CharacterCodingException invalidUtf8) {
        throw new IllegalArgumentException(
            "World terminal evidence contains invalid UTF-8", invalidUtf8);
      }
    }

    private int positiveCount() {
      String value = text();
      if (!value.matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException("Complete World terminal epoch vector required");
      }
      try {
        int count = Integer.parseInt(value);
        // Every epoch entry is seven nonempty length-framed strings. Bound allocation by the
        // remaining bytes before constructing an attacker-controlled collection size.
        if (count > buffer.remaining() / (7 * (Integer.BYTES + 1))) {
          throw new IllegalArgumentException("Incomplete World terminal epoch vector");
        }
        return count;
      } catch (NumberFormatException overflow) {
        throw new IllegalArgumentException("World terminal epoch vector is too large", overflow);
      }
    }

    private void expect(String expected) {
      if (!expected.equals(text())) {
        throw new IllegalArgumentException("Unsupported World terminal evidence schema/outcome");
      }
    }

    private void requireEnd() {
      if (buffer.hasRemaining()) {
        throw new IllegalArgumentException("Trailing World terminal evidence frames");
      }
    }
  }
}
