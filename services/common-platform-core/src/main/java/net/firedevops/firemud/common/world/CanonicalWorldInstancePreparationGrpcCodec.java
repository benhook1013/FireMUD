package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
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

  /** Encodes the separate exact original owner operation without caller-supplied Account proof. */
  public static PrepareCanonicalWorldInstanceRequest toRequest(
      Request request, OriginalOperationInput operationInput) {
    Objects.requireNonNull(operationInput, "operationInput");
    requireOperationBinding(request, operationInput);
    return toRequest(request).toBuilder()
        .setOriginalStartSessionOperation(
            net.firedevops.firemud.worldmanagement.v1.OriginalStartSessionOperationInput
                .newBuilder()
                .setOriginalPostAuthorizationTuple(
                    ByteString.copyFrom(operationInput.originalPostAuthorizationTuple()))
                .setGameSessionOwnerMutationId(
                    operationInput.gameSessionOwnerMutationId().toString())
                .setGameSessionOwnerAttemptId(operationInput.gameSessionOwnerAttemptId().toString())
                .setGameSessionOwnerFence(operationInput.gameSessionOwnerFence())
                .build())
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

  /** Decodes only the bounded original operation; it does not authenticate the peer or Account. */
  public static OriginalOperationInput fromOriginalOperationRequest(
      PrepareCanonicalWorldInstanceRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "PrepareCanonicalWorldInstanceRequest");
    if (!request.hasOriginalStartSessionOperation()) {
      throw new IllegalArgumentException("Exact original StartSession owner operation is required");
    }
    var operation = request.getOriginalStartSessionOperation();
    var input =
        new OriginalOperationInput(
            operation.getOriginalPostAuthorizationTuple().toByteArray(),
            parseCanonicalNonNilUuid(operation.getGameSessionOwnerMutationId(), "ownerMutationId"),
            parseCanonicalNonNilUuid(operation.getGameSessionOwnerAttemptId(), "ownerAttemptId"),
            operation.getGameSessionOwnerFence());
    requireOperationBinding(fromRequest(request), input);
    return input;
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

  /** Response echo preserving both the outer selector and exact original owner operation. */
  public static PrepareCanonicalWorldInstanceResponse toResponse(
      Request request,
      OriginalOperationInput operationInput,
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(operationInput, "operationInput");
    Objects.requireNonNull(evidence, "evidence");
    requireSameBinding(request, evidence.request(), evidence);
    ReadWorldCanonicalInstanceLifecycleResponse lifecycle =
        WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(evidence.request(), evidence);
    return PrepareCanonicalWorldInstanceResponse.newBuilder()
        .setRequest(toRequest(request, operationInput))
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
    if (response.getRequest().hasOriginalStartSessionOperation()) {
      throw new IllegalArgumentException(
          "Canonical World owner operation must be supplied for exact response validation");
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

  /** Validates the exact selector and original-operation request echo before lifecycle decode. */
  public static WorldCanonicalInstanceLifecycleEvidence fromResponse(
      Request request,
      OriginalOperationInput operationInput,
      WorldCanonicalInstanceLifecycleEvidence.Request expectedLifecycleRequest,
      PrepareCanonicalWorldInstanceResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(operationInput, "operationInput");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "PrepareCanonicalWorldInstanceResponse");
    if (response.hasError() || !response.hasRequest() || !response.hasLifecycle()) {
      throw new IllegalArgumentException(
          "Canonical World preparation response must echo the request and lifecycle");
    }
    if (!toRequest(request, operationInput).equals(response.getRequest())) {
      throw new IllegalArgumentException(
          "World changed the exact canonical preparation owner operation");
    }
    return fromResponse(
        request,
        expectedLifecycleRequest,
        response.toBuilder().setRequest(toRequest(request)).build());
  }

  /** Closed operation fields supplied by Game Session; Account proof is never accepted here. */
  public static final class OriginalOperationInput {
    private final byte[] originalPostAuthorizationTuple;
    private final UUID gameSessionOwnerMutationId;
    private final UUID gameSessionOwnerAttemptId;
    private final long gameSessionOwnerFence;

    public OriginalOperationInput(
        byte[] originalPostAuthorizationTuple,
        UUID gameSessionOwnerMutationId,
        UUID gameSessionOwnerAttemptId,
        long gameSessionOwnerFence) {
      Objects.requireNonNull(originalPostAuthorizationTuple, "originalPostAuthorizationTuple");
      if (originalPostAuthorizationTuple.length == 0
          || originalPostAuthorizationTuple.length
              > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
        throw new IllegalArgumentException("Original StartSession tuple is empty or oversized");
      }
      var decoded =
          StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
      if (!Arrays.equals(decoded.canonicalBytes(), originalPostAuthorizationTuple))
        throw new IllegalArgumentException("Exact canonical original StartSession tuple required");
      this.originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
      this.gameSessionOwnerMutationId = Objects.requireNonNull(gameSessionOwnerMutationId);
      this.gameSessionOwnerAttemptId = Objects.requireNonNull(gameSessionOwnerAttemptId);
      if (NIL_UUID.equals(gameSessionOwnerMutationId)
          || NIL_UUID.equals(gameSessionOwnerAttemptId)) {
        throw new IllegalArgumentException("Game Session owner identities must be non-nil");
      }
      if (gameSessionOwnerFence <= 0L) {
        throw new IllegalArgumentException("Game Session owner fence must be positive");
      }
      this.gameSessionOwnerFence = gameSessionOwnerFence;
    }

    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple.clone();
    }

    public UUID gameSessionOwnerMutationId() {
      return gameSessionOwnerMutationId;
    }

    public UUID gameSessionOwnerAttemptId() {
      return gameSessionOwnerAttemptId;
    }

    public long gameSessionOwnerFence() {
      return gameSessionOwnerFence;
    }
  }

  private static void requireOperationBinding(Request request, OriginalOperationInput input) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(input.originalPostAuthorizationTuple());
    var pre = tuple.preAuthorizationTuple();
    if (!request.targetNamespace().equals(pre.action().scope().targetNamespace())
        || !request.canonicalTenantId().equals(pre.action().scope().tenantId())
        || !request.controlPlaneRequestId().equals(pre.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "Original StartSession tuple differs from preparation selector");
    }
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
