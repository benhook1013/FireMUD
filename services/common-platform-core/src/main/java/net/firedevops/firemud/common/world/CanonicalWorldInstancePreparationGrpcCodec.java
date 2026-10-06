package net.firedevops.firemud.common.world;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;

/** Closed transport mapping for the explicitly unwired canonical World preparation handoff. */
public final class CanonicalWorldInstancePreparationGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CanonicalWorldInstancePreparationGrpcCodec() {}

  /** Encodes the exact committed Game Session launch-association selector. */
  public static PrepareCanonicalWorldInstanceRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    return PrepareCanonicalWorldInstanceRequest.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setGameInstanceUuid(request.gameInstanceUuid().toString())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setLaunchDescriptorId(request.launchDescriptorId())
        .setExpectedDescriptorRequestDigest(request.expectedDescriptorRequestDigest())
        .setExpectedDescriptorResultDigest(request.expectedDescriptorResultDigest())
        .setExpectedReleaseAttestationEvidenceDigest(
            request.expectedReleaseAttestationEvidenceDigest())
        .build();
  }

  /** Decodes the exact selector through the existing validated Game Session request value. */
  public static Request fromRequest(PrepareCanonicalWorldInstanceRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "PrepareCanonicalWorldInstanceRequest");
    return new Request(
        parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
        request.getTargetNamespace(),
        parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
        request.getWorldSlug(),
        parseCanonicalNonNilUuid(request.getGameInstanceUuid(), "gameInstanceUuid"),
        request.getControlPlaneRequestId(),
        request.getLaunchDescriptorId(),
        request.getExpectedDescriptorRequestDigest(),
        request.getExpectedDescriptorResultDigest(),
        request.getExpectedReleaseAttestationEvidenceDigest());
  }

  /**
   * Encodes lifecycle evidence only when its inner request and complete pair match the selector.
   */
  public static PrepareCanonicalWorldInstanceResponse toResponse(
      Request request, WorldCanonicalInstanceLifecycleEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    requireSameBinding(request, evidence.request(), evidence);
    ReadWorldCanonicalInstanceLifecycleResponse lifecycle =
        WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(evidence.request(), evidence);
    return PrepareCanonicalWorldInstanceResponse.newBuilder()
        .setRequest(toRequest(request))
        .setLifecycle(lifecycle)
        .build();
  }

  /** Validates the exact outer echo and the complete inner lifecycle owner snapshot. */
  public static WorldCanonicalInstanceLifecycleEvidence fromResponse(
      Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request expectedLifecycleRequest,
      PrepareCanonicalWorldInstanceResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(expectedLifecycleRequest, "expectedLifecycleRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "PrepareCanonicalWorldInstanceResponse");
    if (response.hasError()) {
      throw new IllegalArgumentException("World rejected canonical instance preparation");
    }
    if (!response.hasRequest()) {
      throw new IllegalArgumentException("Canonical World preparation request echo is required");
    }
    if (!response.hasLifecycle()) {
      throw new IllegalArgumentException("Canonical World lifecycle response is required");
    }

    Request echoedRequest = fromRequest(response.getRequest());
    if (!request.equals(echoedRequest)) {
      throw new IllegalArgumentException("World changed the exact canonical preparation request");
    }
    requireSameBinding(request, expectedLifecycleRequest, null);

    WorldCanonicalInstanceLifecycleEvidence evidence =
        WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(
            expectedLifecycleRequest, response.getLifecycle());
    requireSameBinding(request, evidence.request(), evidence);
    return evidence;
  }

  private static void requireSameBinding(
      Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    if (!request.readRequestId().equals(lifecycleRequest.readRequestId())
        || !request.targetNamespace().equals(lifecycleRequest.targetNamespace())
        || !request.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !request.worldSlug().equals(lifecycleRequest.worldSlug())
        || !request.gameInstanceUuid().equals(lifecycleRequest.canonicalGameInstanceId())
        || !request.controlPlaneRequestId().equals(lifecycleRequest.controlPlaneRequestId())
        || !request
            .expectedDescriptorRequestDigest()
            .equals(lifecycleRequest.expectedDescriptorRequestDigest())
        || !request
            .expectedDescriptorResultDigest()
            .equals(lifecycleRequest.expectedDescriptorResultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(lifecycleRequest.expectedReleaseAttestationDigest())) {
      throw new IllegalArgumentException(
          "Canonical preparation selector differs from the World lifecycle request");
    }
    if (evidence != null
        && !request
            .launchDescriptorId()
            .equals(evidence.launchBinding().descriptor().launchDescriptorId())) {
      throw new IllegalArgumentException(
          "Canonical preparation selector differs from the complete launch descriptor");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Missing " + label);
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      if (field.getKey().isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + field.getKey().getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + field.getKey().getName());
      }
    }
  }
}
