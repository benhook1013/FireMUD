package net.firedevops.firemud.common.entity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed value checks only. Workload authentication remains with each transport owner. */
public final class CanonicalPlayerCreatedRuntimeReferenceEvidence {
  public static final int MAX_REQUEST_BYTES = 4096;
  public static final int MAX_RESPONSE_BYTES = 8192;
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> ORIGIN_FIELDS =
      Set.of(
          "targetNamespace",
          "creationOperationId",
          "mutationDigest",
          "terminalId",
          "terminalSha256");

  private CanonicalPlayerCreatedRuntimeReferenceEvidence() {}

  /** Canonical owner-storage value; it retains creation purpose and grants no authentication. */
  public static String storedOriginJson(PlayerCreatedActorOrigin origin) {
    requireOrigin(origin);
    var fields = new LinkedHashMap<String, String>();
    fields.put("targetNamespace", origin.getTargetNamespace());
    fields.put("creationOperationId", origin.getCreationOperationUuid());
    fields.put("mutationDigest", origin.getMutationDigest());
    fields.put("terminalId", origin.getTerminalUuid());
    fields.put("terminalSha256", origin.getTerminalSha256());
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields)),
          StandardCharsets.UTF_8);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException("Closed origin serialization failed", impossible);
    }
  }

  /** Reads the closed JSONB value; JSONB whitespace/key order is not source authority. */
  public static PlayerCreatedActorOrigin originFromStoredJson(String stored) {
    if (stored == null || stored.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
      throw invalid();
    }
    try {
      var root = JSON.readTree(stored);
      if (root == null || !root.isObject() || root.size() != ORIGIN_FIELDS.size()) throw invalid();
      for (String key : ORIGIN_FIELDS) {
        if (!root.has(key) || !root.get(key).isTextual()) throw invalid();
      }
      return requireOrigin(
          PlayerCreatedActorOrigin.newBuilder()
              .setTargetNamespace(root.get("targetNamespace").asText())
              .setCreationOperationUuid(root.get("creationOperationId").asText())
              .setMutationDigest(root.get("mutationDigest").asText())
              .setTerminalUuid(root.get("terminalId").asText())
              .setTerminalSha256(root.get("terminalSha256").asText())
              .build());
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  public static PlayerCreatedActorOrigin requireOrigin(PlayerCreatedActorOrigin origin) {
    if (origin == null
        || !origin.getUnknownFields().asMap().isEmpty()
        || !GrpcPeerIdentity.isValidNamespace(origin.getTargetNamespace())
        || !RAW_SHA256.matcher(origin.getMutationDigest()).matches()
        || !RAW_SHA256.matcher(origin.getTerminalSha256()).matches()) {
      throw invalid();
    }
    uuid(origin.getCreationOperationUuid());
    uuid(origin.getTerminalUuid());
    return origin;
  }

  public static PlayerCreatedActorRuntimeReferenceRequest requireRequest(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    if (request == null
        || request.getSchemaVersion() != 1
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasOrigin()
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())) {
      throw invalid();
    }
    requireOrigin(request.getOrigin());
    for (String id :
        java.util.List.of(
            request.getTenantUuid(),
            request.getAccountUuid(),
            request.getPlayableStateNamespaceUuid(),
            request.getGameInstanceUuid(),
            request.getCharacterUuid(),
            request.getCanonicalVersionUuid(),
            request.getRealmUuid())) {
      uuid(id);
    }
    return request;
  }

  public static PlayerCreatedActorRuntimeReferenceResponse requireResponse(
      PlayerCreatedActorRuntimeReferenceRequest request,
      PlayerCreatedActorRuntimeReferenceResponse response) {
    requireRequest(request);
    if (response == null
        || response.getSchemaVersion() != 1
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasSelector()
        || !request.equals(response.getSelector())
        || response.getRuntimeEntityId() <= 0
        || response.getProjectionGeneration() <= 0
        || response.getProjectionFence() <= 0
        || response.getOriginalCatalogRevision() <= 0
        || response.getOriginalPointerVersion() <= 0
        || response.getOriginalActiveLifecycleEpoch() <= 0
        || !PREFIXED_SHA256.matcher(response.getOriginalDescriptorDigest()).matches()
        || !PREFIXED_SHA256.matcher(response.getOriginalAuthoredBindingDigest()).matches()
        || !PREFIXED_SHA256.matcher(response.getOriginalPolicyContentDigest()).matches()) {
      throw invalid();
    }
    return response;
  }

  private static void uuid(String text) {
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text) || value.equals(new UUID(0L, 0L))) throw invalid();
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Closed original PLAYER_CREATED runtime evidence required");
  }
}
