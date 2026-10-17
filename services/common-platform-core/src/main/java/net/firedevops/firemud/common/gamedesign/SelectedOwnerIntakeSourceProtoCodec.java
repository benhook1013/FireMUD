package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceRequest;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceResponse;

/** Closed wire mapping for selected-owner source content and exact request-echo validation. */
public final class SelectedOwnerIntakeSourceProtoCodec {
  public static final int MAX_WIRE_BYTES = 16 * 1024 * 1024;

  private SelectedOwnerIntakeSourceProtoCodec() {}

  public static SelectedOwnerIntakeSourceRequest toRequest(
      SelectedOwnerIntakeSourceReadEvidence.Request request) {
    Objects.requireNonNull(request, "selected-owner source-read request is required");
    var wire =
        SelectedOwnerIntakeSourceRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setPurpose(request.purpose())
            .setPreliminarySourceScope(ByteString.copyFrom(request.scope().canonicalBytes()))
            .setPreliminaryScopeDigest(request.scope().digest())
            .build();
    requireWithinBudget(wire);
    return wire;
  }

  /** Decodes integrity metadata only; this does not authenticate or authorize source disclosure. */
  public static SelectedOwnerIntakeSourceReadEvidence.Request fromRequest(
      SelectedOwnerIntakeSourceRequest request) {
    requireKnownAndWithinBudget(request, "selected-owner source-read request");
    try {
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
    } catch (IllegalArgumentException exception) {
      throw invalid("Invalid selected-owner source-read request", exception);
    }
  }

  public static SelectedOwnerIntakeSourceResponse toResponse(
      SelectedOwnerIntakeSourceReadEvidence.Request request,
      SelectedOwnerIntakeSourceContent content) {
    Objects.requireNonNull(request, "selected-owner source-read request is required");
    Objects.requireNonNull(content, "selected-owner source content is required");
    if (!request.scope().equals(content.scope()))
      throw invalid("Selected source content is bound to a different preliminary scope");
    var checkedContent =
        SelectedOwnerIntakeSourceContent.fromStored(
            content.canonicalBytes(), request.scope(), content.digest());
    var wire =
        SelectedOwnerIntakeSourceResponse.newBuilder()
            .setRequest(toRequest(request))
            .setSelectedSourceContent(ByteString.copyFrom(checkedContent.canonicalBytes()))
            .setSelectedSourceDigest(checkedContent.digest())
            .build();
    requireWithinBudget(wire);
    return wire;
  }

  /** Validates the complete unchanged echo before accepting bound source content. */
  public static SelectedOwnerIntakeSourceContent fromResponse(
      SelectedOwnerIntakeSourceReadEvidence.Request request,
      SelectedOwnerIntakeSourceResponse response) {
    Objects.requireNonNull(request, "selected-owner source-read request is required");
    requireKnownAndWithinBudget(response, "selected-owner source-read response");
    if (!response.hasRequest()) throw invalid("Selected-owner response has no request echo");
    var echoedRequest = response.getRequest();
    requireKnownAndWithinBudget(echoedRequest, "selected-owner response request echo");
    if (!toRequest(request).equals(echoedRequest))
      throw invalid("Selected-owner response does not echo the complete request unchanged");

    var contentBytes = response.getSelectedSourceContent();
    if (contentBytes.isEmpty() || contentBytes.size() > SelectedOwnerIntakeSourceContent.MAX_BYTES)
      throw invalid("Missing or oversized selected-owner source content");
    try {
      return SelectedOwnerIntakeSourceContent.fromStored(
          contentBytes.toByteArray(), request.scope(), response.getSelectedSourceDigest());
    } catch (IllegalArgumentException exception) {
      throw invalid("Game Design returned invalid selected-owner source content", exception);
    }
  }

  private static void requireKnownAndWithinBudget(Message wire, String label) {
    if (wire == null || wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw invalid("Missing or oversized " + label);
    if (!wire.getUnknownFields().asMap().isEmpty()) throw invalid("Unknown fields in " + label);
  }

  private static void requireWithinBudget(Message wire) {
    if (wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw invalid("Selected-owner source message exceeds the 16 MiB wire budget");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
