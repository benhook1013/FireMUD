package net.firedevops.firemud.common.tenant;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable, canonical data for one terminal original tenant-creation outcome. Constructing this
 * value validates its data shape; it does not verify Account authority or grant permission.
 */
public record GameTenantCreationOriginalOutcomeEvidence(
    int schemaVersion,
    Binding binding,
    Outcome outcome,
    Optional<FreshTenantCreationEvidence> receipt) {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_STORED_BYTES = 8192;
  private static final String DOMAIN = "game-tenant-creation-original-outcome/v1";
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public GameTenantCreationOriginalOutcomeEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported original tenant creation outcome schema");
    }
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(receipt, "receipt");
    if (outcome == Outcome.CREATED) {
      FreshTenantCreationEvidence createdReceipt =
          receipt.orElseThrow(
              () -> new IllegalArgumentException("CREATED outcome requires its exact V30 receipt"));
      if (!binding.targetNamespace().equals(createdReceipt.targetNamespace())
          || !binding.creationRequestId().equals(createdReceipt.creationRequestId())
          || !binding.creationRequestDigest().equals(createdReceipt.requestDigest())) {
        throw new IllegalArgumentException(
            "Created tenant receipt does not match the original request binding");
      }
    } else if (receipt.isPresent()) {
      throw new IllegalArgumentException("NOT_CREATED outcome cannot contain a creation receipt");
    }
  }

  /** Canonical length-framed bytes for the complete binding, outcome, and optional V30 receipt. */
  public byte[] canonicalBytes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      writeFrame(output, DOMAIN);
      output.writeInt(schemaVersion);
      writeFrame(output, binding.targetNamespace());
      writeFrame(output, binding.creationRequestId().toString());
      writeFrame(output, binding.accountAuthorizationOperationId().toString());
      writeFrame(output, binding.initiatingAccountId().toString());
      writeFrame(output, binding.creationRequestDigest());
      writeFrame(output, binding.accountAuthorizationDigest());
      writeFrame(output, outcome.name());
      output.writeByte(receipt.isPresent() ? 1 : 0);
      if (receipt.isPresent()) {
        FreshTenantCreationEvidence createdReceipt = receipt.orElseThrow();
        output.writeInt(createdReceipt.schemaVersion());
        writeFrame(output, createdReceipt.targetNamespace());
        writeFrame(output, createdReceipt.creationRequestId().toString());
        writeFrame(output, createdReceipt.operationId().toString());
        writeFrame(output, createdReceipt.requestDigest());
        writeFrame(output, createdReceipt.canonicalTenantId().toString());
        output.writeLong(createdReceipt.sourceGameRowId());
        writeFrame(output, createdReceipt.sourceGameTenantKey());
        writeFrame(output, createdReceipt.provenanceKind());
        writeFrame(output, createdReceipt.evidenceDigest());
      }
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unable to encode original tenant creation outcome", impossible);
    }
    return bytes.toByteArray();
  }

  /** SHA-256 digest of {@link #canonicalBytes()}, independent of any authority decision. */
  public String evidenceDigest() {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  /** Strictly decodes bounded canonical bytes for an immutable Account-side settlement record. */
  public static GameTenantCreationOriginalOutcomeEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_STORED_BYTES) {
      throw new IllegalArgumentException("Stored original tenant outcome exceeds its byte bounds");
    }
    byte[] source = stored.clone();
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(source))) {
      if (!DOMAIN.equals(readFrame(input))) {
        throw new IllegalArgumentException("Stored original tenant outcome domain is unsupported");
      }
      int schemaVersion = input.readInt();
      Binding binding =
          new Binding(
              readFrame(input),
              parseCanonicalNonNilUuid(readFrame(input), "creationRequestId"),
              parseCanonicalNonNilUuid(readFrame(input), "accountAuthorizationOperationId"),
              parseCanonicalNonNilUuid(readFrame(input), "initiatingAccountId"),
              readFrame(input),
              readFrame(input));
      Outcome outcome = parseOutcome(readFrame(input));
      int receiptPresent = input.readUnsignedByte();
      if (receiptPresent > 1) {
        throw new IllegalArgumentException(
            "Stored original tenant outcome receipt flag is invalid");
      }
      Optional<FreshTenantCreationEvidence> receipt =
          receiptPresent == 0 ? Optional.empty() : Optional.of(readReceipt(input));
      if (input.available() != 0) {
        throw new IllegalArgumentException("Stored original tenant outcome has trailing bytes");
      }
      GameTenantCreationOriginalOutcomeEvidence decoded =
          new GameTenantCreationOriginalOutcomeEvidence(schemaVersion, binding, outcome, receipt);
      if (!Arrays.equals(source, decoded.canonicalBytes())) {
        throw new IllegalArgumentException("Stored original tenant outcome is not canonical");
      }
      return decoded;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Stored original tenant outcome is truncated", malformed);
    }
  }

  private static FreshTenantCreationEvidence readReceipt(DataInputStream input) throws IOException {
    return new FreshTenantCreationEvidence(
        input.readInt(),
        readFrame(input),
        parseCanonicalNonNilUuid(readFrame(input), "receipt.creationRequestId"),
        parseCanonicalNonNilUuid(readFrame(input), "receipt.operationId"),
        readFrame(input),
        parseCanonicalNonNilUuid(readFrame(input), "receipt.canonicalTenantId"),
        input.readLong(),
        readFrame(input),
        readFrame(input),
        readFrame(input));
  }

  private static Outcome parseOutcome(String value) {
    try {
      return Outcome.valueOf(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Stored original tenant outcome is not terminal", malformed);
    }
  }

  private static void writeFrame(DataOutputStream output, String value) throws IOException {
    int expectedLength = GameTenantCreationDigest.utf8ByteLength(value);
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length != expectedLength) {
      throw new IllegalArgumentException("Original tenant outcome contains invalid Unicode");
    }
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static String readFrame(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_STORED_BYTES) {
      throw new IllegalArgumentException("Stored original tenant outcome field exceeds its bounds");
    }
    byte[] encoded = input.readNBytes(length);
    if (encoded.length != length) {
      throw new IllegalArgumentException("Stored original tenant outcome field is truncated");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Stored original tenant outcome has malformed UTF-8", malformed);
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", malformed);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }

  private static void validateNamespace(String targetNamespace) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!DNS_LABEL.matcher(targetNamespace).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }

  public record Binding(
      String targetNamespace,
      UUID creationRequestId,
      UUID accountAuthorizationOperationId,
      UUID initiatingAccountId,
      String creationRequestDigest,
      String accountAuthorizationDigest) {
    public Binding {
      validateNamespace(targetNamespace);
      requireNonNil(creationRequestId, "creationRequestId");
      requireNonNil(accountAuthorizationOperationId, "accountAuthorizationOperationId");
      requireNonNil(initiatingAccountId, "initiatingAccountId");
      if (!GameTenantCreationDigest.isDigest(creationRequestDigest)) {
        throw new IllegalArgumentException(
            "creationRequestDigest must be a lowercase SHA-256 digest");
      }
      if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
        throw new IllegalArgumentException(
            "accountAuthorizationDigest must be a lowercase SHA-256 digest");
      }
    }
  }

  public enum Outcome {
    CREATED,
    NOT_CREATED
  }
}
