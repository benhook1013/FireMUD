package net.firedevops.firemud.gamesession.dto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.springframework.lang.Nullable;

/** Explicit input for one immutable public-production realm catalog creation. */
public record CreateCanonicalRealmCatalogRequest(
    UUID requestId,
    String targetNamespace,
    UUID canonicalTenantId,
    UUID sourceIntakeOperationId,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    String stateScope,
    String characterCreationPolicy,
    @Nullable UUID playtestLifecycleId,
    @Nullable Long playtestStateGeneration) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @SuppressFBWarnings(
      value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
      justification =
          "The two nullable lifecycle fields are rejected when supplied; null is their required ABSENT state for initial public production.")
  public CreateCanonicalRealmCatalogRequest {
    requireNonNil(requestId, "requestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(sourceIntakeOperationId, "sourceIntakeOperationId");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    Objects.requireNonNull(realmSlug, "realmSlug");
    strictUtf8(realmSlug, "realmSlug");
    if (!RealmEntryPolicy.isCanonicalSlug(realmSlug)) {
      throw new IllegalArgumentException("realmSlug must be a canonical lower-case slug");
    }
    Objects.requireNonNull(realmDisplayName, "realmDisplayName");
    if (realmDisplayName.isBlank()
        || !realmDisplayName.equals(realmDisplayName.trim())
        || realmDisplayName.codePointCount(0, realmDisplayName.length()) > 100
        || strictUtf8(realmDisplayName, "realmDisplayName").length > 400) {
      throw new IllegalArgumentException(
          "realmDisplayName must be nonblank, trimmed, and within the catalog text bounds");
    }
    if (!visible || !publicProduction) {
      throw new IllegalArgumentException(
          "Initial canonical realm creation requires visible public production");
    }
    if (!"SHARED".equals(stateScope) && !"ISOLATED".equals(stateScope)) {
      throw new IllegalArgumentException("stateScope must be exactly SHARED or ISOLATED");
    }
    Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy");
    if (characterCreationPolicy.isBlank()
        || !characterCreationPolicy.equals(characterCreationPolicy.trim())
        || characterCreationPolicy.length() > 32) {
      throw new IllegalArgumentException(
          "characterCreationPolicy must be nonblank, trimmed, and at most 32 characters");
    }
    strictUtf8(characterCreationPolicy, "characterCreationPolicy");
    if (playtestLifecycleId != null || playtestStateGeneration != null) {
      throw new IllegalArgumentException(
          "Initial public-production creation cannot carry private/playtest lifecycle evidence");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
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
