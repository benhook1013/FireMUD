package net.firedevops.firemud.common.account.sourceintake;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed preliminary source-scope wire mapping and exact Account echo validation. */
public final class SelectedOwnerIntakeSourceReadProtoCodec {
  public static final int MAX_WIRE_BYTES = 8 * 1024 * 1024;

  private SelectedOwnerIntakeSourceReadProtoCodec() {}

  public static SelectedOwnerIntakeSourcePermissionRequest toRequest(
      SelectedOwnerIntakeSourceReadEvidence.Request request) {
    Objects.requireNonNull(request, "source-read request is required");
    var wire =
        SelectedOwnerIntakeSourcePermissionRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setPurpose(request.purpose())
            .setPreliminarySourceScope(ByteString.copyFrom(request.scope().canonicalBytes()))
            .setPreliminaryScopeDigest(request.scope().digest())
            .build();
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static SelectedOwnerIntakeSourceReadEvidence.Request fromRequest(
      SelectedOwnerIntakeSourcePermissionRequest request) {
    requireKnownAndWithinBudget(request);
    DraftAuthorizationFenceBinding.canonicalUuid(request.getReadRequestId());
    var scope =
        SelectedOwnerIntakeSourceReadScope.fromStored(
            request.getPreliminarySourceScope().toByteArray());
    if (!scope.digest().equals(request.getPreliminaryScopeDigest()))
      throw invalid("Changed preliminary source-scope digest");
    return new SelectedOwnerIntakeSourceReadEvidence.Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        UUID.fromString(request.getReadRequestId()),
        request.getIntendedReader(),
        request.getPurpose(),
        scope);
  }

  public static SelectedOwnerIntakeSourcePermissionResponse toResponse(
      SelectedOwnerIntakeSourceReadEvidence.Request request) {
    var wire =
        SelectedOwnerIntakeSourcePermissionResponse.newBuilder()
            .setRequest(toRequest(request))
            .setPermitted(true)
            .build();
    requireWithinBudget(wire.getSerializedSize());
    return wire;
  }

  public static SelectedOwnerIntakeSourceReadEvidence fromResponse(
      SelectedOwnerIntakeSourceReadEvidence.Request request,
      SelectedOwnerIntakeSourcePermissionResponse response) {
    Objects.requireNonNull(request, "source-read request is required");
    requireKnownAndWithinBudget(response);
    if (!response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || !response.getPermitted()) {
      throw invalid("Exact permitted Account source-scope confirmation required");
    }
    return new SelectedOwnerIntakeSourceReadEvidence(request);
  }

  private static void requireKnownAndWithinBudget(Message wire) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw invalid("Missing or oversized selected-owner source-read message");
    if (!wire.getUnknownFields().asMap().isEmpty())
      throw invalid("Unknown selected-owner source-read fields");
  }

  private static void requireWithinBudget(int size) {
    if (size > MAX_WIRE_BYTES)
      throw invalid("Selected-owner source-read message exceeds the 8 MiB wire budget");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
