package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;

/** Closed mapping and exact-readback validation for authored-world launch descriptor RPCs. */
public final class AuthoredWorldLaunchDescriptorGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  /** Exact owner read identity and the immutable resolve tuple whose result is being recovered. */
  public record GetRequest(
      UUID requestId,
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest,
      String expectedResultDigest) {
    public GetRequest {
      if (requestId == null || NIL_UUID.equals(requestId)) {
        throw new IllegalArgumentException(
            "A nonnil launch descriptor read request ID is required");
      }
      Objects.requireNonNull(expectedRequest, "expectedRequest");
      if (requestId.equals(expectedRequest.authoredWorldSourceOperationId())
          || requestId.toString().equals(expectedRequest.controlPlaneRequestId())) {
        throw new IllegalArgumentException(
            "Launch descriptor read request ID must be distinct from the original request");
      }
      if (expectedResultDigest == null || !SHA256.matcher(expectedResultDigest).matches()) {
        throw new IllegalArgumentException(
            "Expected launch descriptor result digest must be canonical SHA-256 text");
      }
    }
  }

  private AuthoredWorldLaunchDescriptorGrpcCodec() {}

  /** Encodes only the exact request fields owned by the immutable shared request value. */
  public static ResolveLaunchDescriptorRequest toResolveRequest(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    ResolveLaunchDescriptorRequest.Builder builder =
        ResolveLaunchDescriptorRequest.newBuilder()
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setGameTemplateId(request.gameTemplateId())
            .setControlPlaneRequestId(request.controlPlaneRequestId())
            .setWorldSlug(request.worldSlug())
            .setAuthoredWorldSourceOperationId(request.authoredWorldSourceOperationId().toString())
            .setExpectedAuthoredWorldSourceEvidenceDigest(
                request.authoredWorldSourceEvidenceDigest());
    if (request.requestedScriptPatchVersionPresent()) {
      builder.setRequestedScriptPatchVersion(request.requestedScriptPatchVersion());
    }
    if (request.sourceVersionIdPresent()) {
      builder.setSourceVersionId(request.sourceVersionId());
    }
    if (request.targetVersionIdPresent()) {
      builder.setTargetVersionId(request.targetVersionId());
    }
    if (request.requestedRuntimeFlagsJsonPresent()) {
      builder.setRequestedRuntimeFlagsJson(request.requestedRuntimeFlagsJson());
    }
    return builder.build();
  }

  /** Encodes an exact persisted owner read, kept distinct from the original resolve identity. */
  public static GetLaunchDescriptorRequest toGetRequest(GetRequest request) {
    Objects.requireNonNull(request, "request");
    AuthoredWorldLaunchDescriptorEvidence.Request expected = request.expectedRequest();
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(request.requestId().toString())
        .setCanonicalTenantId(expected.canonicalTenantId().toString())
        .setWorldSlug(expected.worldSlug())
        .setControlPlaneRequestId(expected.controlPlaneRequestId())
        .setExpectedRequestDigest(expected.requestDigest())
        .setExpectedResultDigest(request.expectedResultDigest())
        .build();
  }

  /** Validates the complete response and flat descriptor duplicates against the exact request. */
  public static AuthoredWorldLaunchDescriptorEvidence fromResolveResponse(
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest,
      ResolveLaunchDescriptorResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ResolveLaunchDescriptor response");
    if (response.hasError()) {
      throw new IllegalArgumentException("Game Design rejected launch descriptor resolution");
    }
    if (!response.hasLaunchDescriptor()) {
      throw new IllegalArgumentException("Launch descriptor resolution returned no descriptor");
    }
    return decodeDescriptor(expectedRequest, response.getLaunchDescriptor());
  }

  /** Validates an exact read echo, original request tuple, result digest, and duplicate fields. */
  public static AuthoredWorldLaunchDescriptorEvidence fromGetResponse(
      GetRequest request, GetLaunchDescriptorResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "GetLaunchDescriptor response");
    if (response.hasError()) {
      throw new IllegalArgumentException("Game Design rejected the exact launch descriptor read");
    }
    UUID echoedRequestId = parseCanonicalNonNilUuid(response.getRequestId(), "read request ID");
    if (!request.requestId().equals(echoedRequestId)) {
      throw new IllegalArgumentException("Launch descriptor read request ID echo changed");
    }
    if (!response.hasLaunchDescriptor()) {
      throw new IllegalArgumentException("Exact launch descriptor read returned no descriptor");
    }
    AuthoredWorldLaunchDescriptorEvidence evidence =
        decodeDescriptor(request.expectedRequest(), response.getLaunchDescriptor());
    if (!request.expectedResultDigest().equals(evidence.resultDigest())) {
      throw new IllegalArgumentException(
          "Launch descriptor result digest does not match the exact read request");
    }
    return evidence;
  }

  private static AuthoredWorldLaunchDescriptorEvidence decodeDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest, LaunchDescriptor descriptor) {
    requireNoUnknownFields(descriptor, "LaunchDescriptor");
    if (!descriptor.hasAuthoredWorldBinding()) {
      throw new IllegalArgumentException("Launch descriptor has no authored-world binding");
    }
    var binding = descriptor.getAuthoredWorldBinding();
    requireNoUnknownFields(binding, "AuthoredWorldLaunchDescriptorEvidence");

    AuthoredWorldLaunchDescriptorEvidence evidence;
    try {
      evidence =
          new AuthoredWorldLaunchDescriptorEvidence(
              binding.getSchemaVersion(),
              binding.getTargetNamespace(),
              binding.getControlPlaneRequestId(),
              parseCanonicalNonNilUuid(binding.getCanonicalTenantId(), "canonical tenant ID"),
              binding.getWorldSlug(),
              parseCanonicalNonNilUuid(
                  binding.getAuthoredWorldSourceOperationId(),
                  "authored-world source operation ID"),
              binding.getAuthoredWorldSourceEvidenceDigest(),
              binding.getGameTemplateId(),
              binding.hasRequestedScriptPatchVersion(),
              binding.hasRequestedScriptPatchVersion()
                  ? binding.getRequestedScriptPatchVersion()
                  : null,
              binding.hasSourceVersionId(),
              binding.hasSourceVersionId() ? binding.getSourceVersionId() : null,
              binding.hasTargetVersionId(),
              binding.hasTargetVersionId() ? binding.getTargetVersionId() : null,
              binding.hasRequestedRuntimeFlagsJson(),
              binding.hasRequestedRuntimeFlagsJson()
                  ? binding.getRequestedRuntimeFlagsJson()
                  : null,
              binding.getRequestDigest(),
              binding.getLaunchDescriptorId(),
              binding.getVersionId(),
              binding.hasScriptPatchVersion(),
              binding.hasScriptPatchVersion() ? binding.getScriptPatchVersion() : null,
              binding.getRuntimeFlagsJson(),
              binding.getGenerationConfigRevision(),
              binding.getVersionStateEpoch(),
              binding.getReleaseBundleId(),
              binding.getPublishedReleaseBundleRef(),
              binding.hasRemapSetId(),
              binding.hasRemapSetId() ? binding.getRemapSetId() : null,
              binding.getResultDigest());
      evidence.requireValid();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Authored-world launch evidence is invalid", exception);
    }

    if (!evidence.request().equals(expectedRequest)) {
      throw new IllegalArgumentException(
          "Launch descriptor does not match the exact authored-world resolve request");
    }
    requireFlatDuplicate(
        descriptor.getLaunchDescriptorId(), evidence.launchDescriptorId(), "launchDescriptorId");
    requireFlatDuplicate(
        descriptor.getCanonicalTenantId(),
        evidence.canonicalTenantId().toString(),
        "canonicalTenantId");
    requireFlatDuplicate(
        descriptor.getGameTemplateId(), evidence.gameTemplateId(), "gameTemplateId");
    requireFlatDuplicate(
        descriptor.getControlPlaneRequestId(),
        evidence.controlPlaneRequestId(),
        "controlPlaneRequestId");
    requireFlatDuplicate(descriptor.getVersionId(), evidence.versionId(), "versionId");
    requireFlatDuplicate(
        descriptor.getScriptPatchVersion(),
        evidence.scriptPatchVersionPresent() ? evidence.scriptPatchVersion() : "",
        "scriptPatchVersion");
    requireFlatDuplicate(
        descriptor.getRuntimeFlagsJson(), evidence.runtimeFlagsJson(), "runtimeFlagsJson");
    requireFlatDuplicate(
        descriptor.getGenerationConfigRevision(),
        evidence.generationConfigRevision(),
        "generationConfigRevision");
    requireFlatDuplicate(
        descriptor.getVersionStateEpoch(), evidence.versionStateEpoch(), "versionStateEpoch");
    requireFlatDuplicate(
        descriptor.getReleaseBundleId(), evidence.releaseBundleId(), "releaseBundleId");
    requireFlatDuplicate(
        descriptor.getPublishedReleaseBundleRef(),
        evidence.publishedReleaseBundleRef(),
        "publishedReleaseBundleRef");
    requireFlatDuplicate(
        descriptor.getRemapSetId(),
        evidence.remapSetIdPresent() ? evidence.remapSetId() : "",
        "remapSetId");
    return evidence;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static void requireFlatDuplicate(String actual, String expected, String field) {
    if (!Objects.equals(actual, expected)) {
      throw new IllegalArgumentException(
          "Flat launch descriptor " + field + " does not match evidence");
    }
  }

  private static void requireFlatDuplicate(long actual, long expected, String field) {
    if (actual != expected) {
      throw new IllegalArgumentException(
          "Flat launch descriptor " + field + " does not match evidence");
    }
  }
}
