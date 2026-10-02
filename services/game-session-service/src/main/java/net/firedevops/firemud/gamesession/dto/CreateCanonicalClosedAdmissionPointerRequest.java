package net.firedevops.firemud.gamesession.dto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Local audit inputs for one immutable initial canonical CLOSED admission pointer. */
public record CreateCanonicalClosedAdmissionPointerRequest(
    UUID requestId,
    String targetNamespace,
    UUID canonicalTenantId,
    UUID realmId,
    UUID catalogCreationRequestId,
    long catalogRevision,
    String actorPrincipal,
    String reason) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CreateCanonicalClosedAdmissionPointerRequest {
    requireNonNil(requestId, "requestId");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
    requireNonNil(catalogCreationRequestId, "catalogCreationRequestId");
    if (catalogRevision <= 0L) {
      throw new IllegalArgumentException("catalogRevision must be positive");
    }
    validateBoundedText(actorPrincipal, "actorPrincipal", 200, 800);
    validateBoundedText(reason, "reason", 500, 2000);
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void validateBoundedText(
      String value, String name, int maximumCodePoints, int maximumUtf8Bytes) {
    Objects.requireNonNull(value, name);
    byte[] bytes = strictUtf8(value, name);
    if (value.isBlank()
        || value.codePointCount(0, value.length()) > maximumCodePoints
        || bytes.length > maximumUtf8Bytes) {
      throw new IllegalArgumentException(name + " must be bounded and nonblank");
    }
  }

  private static byte[] strictUtf8(String value, String name) {
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
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(name + " must be well-formed UTF-8 text", exception);
    }
  }
}
