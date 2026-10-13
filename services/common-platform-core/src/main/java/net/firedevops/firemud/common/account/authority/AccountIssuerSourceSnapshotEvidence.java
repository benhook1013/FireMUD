package net.firedevops.firemud.common.account.authority;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Immutable, closed Account issuer-source snapshot evidence. Shape validation alone does not
 * authenticate an Account response or authorize a Game Session projection update.
 */
public record AccountIssuerSourceSnapshotEvidence(
    UUID reconciliationOperationId,
    String targetNamespace,
    String callerWorkload,
    String issuerId,
    String issuerAuthGeneration,
    String sourceVersion,
    String outboxStreamKey,
    String lastCommittedOutboxSequence,
    Optional<String> sourceEvent) {
  public static final int SCHEMA_VERSION = 1;
  public static final String ISSUER_ID = "firemud-account-service";
  public static final String ISSUER_STREAM_KEY =
      AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER_ID;
  public static final int MAX_SOURCE_EVENT_BYTES = 64 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)");

  public AccountIssuerSourceSnapshotEvidence {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    validateCallerWorkload(targetNamespace, callerWorkload);
    requireNonNil(reconciliationOperationId, "reconciliationOperationId");
    if (!ISSUER_ID.equals(issuerId)) {
      throw new IllegalArgumentException("issuerId must be the supported Account issuer");
    }
    if (!ISSUER_STREAM_KEY.equals(outboxStreamKey)) {
      throw new IllegalArgumentException("outboxStreamKey must be the canonical issuer stream");
    }
    requirePositiveDecimal(issuerAuthGeneration, "issuerAuthGeneration");
    requirePositiveDecimal(sourceVersion, "sourceVersion");
    BigInteger sequence =
        requireNonnegativeDecimal(lastCommittedOutboxSequence, "lastCommittedOutboxSequence");
    sourceEvent = Objects.requireNonNull(sourceEvent, "sourceEvent presence is required");

    if (sequence.signum() == 0) {
      if (!"1".equals(issuerAuthGeneration) || !"1".equals(sourceVersion)) {
        throw new IllegalArgumentException(
            "sequence-zero issuer source must have generation and source version one");
      }
      if (sourceEvent.isPresent()) {
        throw new IllegalArgumentException("sequence-zero issuer source must omit sourceEvent");
      }
    } else {
      BigInteger generation = new BigInteger(issuerAuthGeneration);
      BigInteger version = new BigInteger(sourceVersion);
      if (generation.compareTo(BigInteger.ONE) <= 0 || version.compareTo(BigInteger.ONE) <= 0) {
        throw new IllegalArgumentException(
            "advanced issuer source must have generation and source version greater than one");
      }
      String canonicalEvent =
          sourceEvent.orElseThrow(
              () -> new IllegalArgumentException("positive issuer sequence requires sourceEvent"));
      byte[] eventBytes = strictUtf8(canonicalEvent, "sourceEvent");
      if (eventBytes.length == 0 || eventBytes.length > MAX_SOURCE_EVENT_BYTES) {
        throw new IllegalArgumentException("sourceEvent must be nonempty and at most 64 KiB");
      }
      verifyIssuerEvent(
          canonicalEvent, issuerAuthGeneration, sourceVersion, lastCommittedOutboxSequence);
    }
  }

  /** Returns fresh UTF-8 storage on every call so callers cannot mutate retained evidence. */
  public Optional<byte[]> sourceEventBytes() {
    return sourceEvent.map(value -> strictUtf8(value, "sourceEvent"));
  }

  public static void validateCallerWorkload(String targetNamespace, String callerWorkload) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    String expected = "spiffe://firemud/ns/" + targetNamespace + "/sa/game-session-service";
    var identity = GrpcPeerIdentity.parseUri(callerWorkload);
    if (identity.isEmpty()
        || !expected.equals(callerWorkload)
        || !identity.orElseThrow().isInNamespace(targetNamespace)
        || !identity.orElseThrow().isService("game-session-service")) {
      throw new IllegalArgumentException(
          "callerWorkload must be the canonical same-namespace Game Session SPIFFE URI");
    }
  }

  private static void verifyIssuerEvent(
      String canonicalEvent, String generation, String version, String sequence) {
    AccountAuthoritySourceEventV1Codec.SourceEvent verified =
        AccountAuthoritySourceEventV1Codec.verify(canonicalEvent);
    if (!(verified instanceof AccountAuthoritySourceEventV1Codec.IssuerEvent issuerEvent)) {
      throw new IllegalArgumentException("sourceEvent must use the closed issuer event schema");
    }
    if (!canonicalEvent.equals(issuerEvent.canonicalJson())) {
      throw new IllegalArgumentException("sourceEvent must use the exact canonical JSON encoding");
    }
    if (!issuerEvent.eventId().equals(issuerEvent.requestId())) {
      throw new IllegalArgumentException("issuer source eventId must equal requestId");
    }
    if (!ISSUER_ID.equals(issuerEvent.issuerId())
        || !ISSUER_STREAM_KEY.equals(issuerEvent.outboxStreamKey())
        || !generation.equals(issuerEvent.issuerAuthGeneration())
        || !version.equals(issuerEvent.sourceVersion())
        || !sequence.equals(issuerEvent.outboxSequence())) {
      throw new IllegalArgumentException(
          "issuer source event must exactly match the snapshot issuer checkpoint");
    }
  }

  private static BigInteger requirePositiveDecimal(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (!POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a positive canonical decimal string");
    }
    return new BigInteger(value);
  }

  private static BigInteger requireNonnegativeDecimal(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (!NONNEGATIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a nonnegative canonical decimal string");
    }
    return new BigInteger(value);
  }

  private static byte[] strictUtf8(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(field + " is not valid Unicode for UTF-8", malformed);
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
  }
}
