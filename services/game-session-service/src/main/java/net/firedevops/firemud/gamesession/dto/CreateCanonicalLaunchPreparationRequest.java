package net.firedevops.firemud.gamesession.dto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import org.springframework.lang.Nullable;

/** Immutable input for source-qualified Game Design launch preparation; it grants no authority. */
public record CreateCanonicalLaunchPreparationRequest(
    String controlPlaneRequestId,
    UUID actingAccountUuid,
    String targetNamespace,
    UUID canonicalTenantId,
    UUID realmId,
    UUID catalogCreationRequestId,
    long catalogRevision,
    UUID sourceIntakeOperationId,
    long gameTemplateId,
    boolean requestedScriptPatchVersionPresent,
    @Nullable String requestedScriptPatchVersion,
    boolean sourceVersionIdPresent,
    @Nullable Long sourceVersionId,
    boolean targetVersionIdPresent,
    @Nullable Long targetVersionId,
    boolean requestedRuntimeFlagsJsonPresent,
    @Nullable String requestedRuntimeFlagsJson) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CreateCanonicalLaunchPreparationRequest {
    requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
    requireNonNil(actingAccountUuid, "actingAccountUuid");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
    requireNonNil(catalogCreationRequestId, "catalogCreationRequestId");
    requireNonNil(sourceIntakeOperationId, "sourceIntakeOperationId");
    if (catalogRevision <= 0L) {
      throw new IllegalArgumentException("catalogRevision must be positive");
    }
    if (gameTemplateId <= 0L) {
      throw new IllegalArgumentException("gameTemplateId must be positive");
    }
    requireOptional(
        requestedScriptPatchVersionPresent,
        requestedScriptPatchVersion,
        "requestedScriptPatchVersion");
    if (requestedScriptPatchVersionPresent && requestedScriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException(
          "requestedScriptPatchVersion must be nonblank when present");
    }
    requireOptional(sourceVersionIdPresent, sourceVersionId, "sourceVersionId");
    if (sourceVersionIdPresent && sourceVersionId <= 0L) {
      throw new IllegalArgumentException("sourceVersionId must be positive when present");
    }
    requireOptional(targetVersionIdPresent, targetVersionId, "targetVersionId");
    if (targetVersionIdPresent && targetVersionId <= 0L) {
      throw new IllegalArgumentException("targetVersionId must be positive when present");
    }
    requireOptional(
        requestedRuntimeFlagsJsonPresent, requestedRuntimeFlagsJson, "requestedRuntimeFlagsJson");
  }

  public AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest(
      CanonicalRealmCatalogSnapshot catalog, AuthoredWorldSourceEvidence source) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(source, "source");
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        targetNamespace,
        controlPlaneRequestId,
        canonicalTenantId,
        catalog.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        gameTemplateId,
        requestedScriptPatchVersionPresent,
        requestedScriptPatchVersion,
        sourceVersionIdPresent,
        sourceVersionId,
        targetVersionIdPresent,
        targetVersionId,
        requestedRuntimeFlagsJsonPresent,
        requestedRuntimeFlagsJson);
  }

  private static void requireOptional(boolean present, Object value, String name) {
    if (present != (value != null)) {
      throw new IllegalArgumentException(name + " presence does not match its value");
    }
    if (value instanceof String text) {
      strictUtf8(text, name);
    }
  }

  private static void requireText(String value, String name, int maxUtf8Bytes) {
    Objects.requireNonNull(value, name);
    byte[] bytes = strictUtf8(value, name);
    if (value.isBlank() || bytes.length > maxUtf8Bytes) {
      throw new IllegalArgumentException(name + " must be nonblank and bounded");
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
      throw new IllegalArgumentException(name + " must be well-formed UTF-8", exception);
    }
  }
}
