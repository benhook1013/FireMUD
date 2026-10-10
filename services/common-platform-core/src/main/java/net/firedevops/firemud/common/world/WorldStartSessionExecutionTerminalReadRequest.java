package net.firedevops.firemud.common.world;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;

/** Exact immutable identity and fresh correlation for one historical World terminal read. */
public final class WorldStartSessionExecutionTerminalReadRequest {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final UUID readRequestId;
  private final String targetNamespace;
  private final byte[] originalPostAuthorizationTuple;
  private final StartSessionPostAuthorizationExecutionTuple decodedOriginalTuple;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final UUID canonicalGameInstanceId;
  private final String preparationInputJson;
  private final String preparationInputDigest;

  public WorldStartSessionExecutionTerminalReadRequest(
      UUID readRequestId,
      String targetNamespace,
      byte[] originalPostAuthorizationTuple,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID canonicalGameInstanceId,
      String preparationInputJson) {
    this.readRequestId = requireNonNil(readRequestId, "readRequestId");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw invalid("targetNamespace must be one canonical DNS label");
    }
    this.targetNamespace = targetNamespace;
    if (originalPostAuthorizationTuple == null
        || originalPostAuthorizationTuple.length == 0
        || originalPostAuthorizationTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw invalid("Exact original StartSession post-authorization tuple is required");
    }
    this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    this.decodedOriginalTuple =
        StartSessionPostAuthorizationExecutionTuple.decode(this.originalPostAuthorizationTuple);
    var original = decodedOriginalTuple.preAuthorizationTuple();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(original.actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(original.targetOwner())) {
      throw invalid("Original tuple must identify the Game Session StartSession operation");
    }
    if (!targetNamespace.equals(original.action().scope().targetNamespace())) {
      throw invalid("targetNamespace differs from the exact original StartSession tuple");
    }

    this.accountWorldParticipationId =
        requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    this.accountWorldParticipationFence =
        requirePositive(accountWorldParticipationFence, "accountWorldParticipationFence");
    this.gameSessionOwnerAttemptId =
        requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    this.gameSessionOwnerFence = requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
    this.canonicalGameInstanceId =
        requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    if (this.readRequestId.equals(this.accountWorldParticipationId)
        || this.readRequestId.equals(this.gameSessionOwnerAttemptId)
        || this.readRequestId.equals(this.canonicalGameInstanceId)
        || this.readRequestId.equals(decodedOriginalTuple.reservationOwnerId())) {
      throw invalid("readRequestId must be fresh and distinct from retained operation identities");
    }
    this.preparationInputJson = requirePreparationInput(preparationInputJson);
    this.preparationInputDigest = digest(strictUtf8(this.preparationInputJson));
  }

  public UUID readRequestId() {
    return readRequestId;
  }

  public String targetNamespace() {
    return targetNamespace;
  }

  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  public StartSessionPostAuthorizationExecutionTuple decodedOriginalTuple() {
    return decodedOriginalTuple;
  }

  public UUID accountWorldParticipationId() {
    return accountWorldParticipationId;
  }

  public long accountWorldParticipationFence() {
    return accountWorldParticipationFence;
  }

  public UUID gameSessionOwnerAttemptId() {
    return gameSessionOwnerAttemptId;
  }

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
  }

  public UUID canonicalGameInstanceId() {
    return canonicalGameInstanceId;
  }

  public UUID canonicalTenantId() {
    return decodedOriginalTuple.preAuthorizationTuple().action().scope().tenantId();
  }

  public String controlPlaneRequestId() {
    return decodedOriginalTuple.controlPlaneRequestId();
  }

  public String preparationInputJson() {
    return preparationInputJson;
  }

  /** Derived from the exact input string; it is never a separate request authority field. */
  public String preparationInputDigest() {
    return preparationInputDigest;
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
    return value;
  }

  private static long requirePositive(long value, String field) {
    if (value <= 0L) {
      throw invalid(field + " must be a positive signed 64-bit integer");
    }
    return value;
  }

  private static String requirePreparationInput(String value) {
    Objects.requireNonNull(value, "preparationInputJson");
    if (value.isBlank()) {
      throw invalid("preparationInputJson must retain the exact original input");
    }
    if (strictUtf8(value).length > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw invalid("preparationInputJson exceeds the terminal byte limit");
    }
    return value;
  }

  private static byte[] strictUtf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException malformed) {
      throw invalid("preparationInputJson must be valid Unicode", malformed);
    }
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
