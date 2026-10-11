package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.StartSessionLaunchDescriptorApplicationFailure;

/** Closed request/result mapping for the exact-replay StartSession descriptor projection. */
public final class StartSessionLaunchDescriptorGrpcCodec {
  public static final int RESPONSE_SCHEMA_VERSION = 1;
  private static final String EMPTY_RUNTIME_FLAGS = "{}";
  private static final Set<String> FAILURE_CODES =
      Set.of(
          "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED",
          "INVALID_TEMPLATE_CONFIGURATION",
          "SCRIPT_PATCH_OVERRIDE_CONFLICT",
          "SCRIPT_PATCH_NOT_READY",
          "RELEASE_BUNDLE_NOT_FOUND",
          "RELEASE_ATTESTATION_MISMATCH",
          "VERSION_STATE_EPOCH_STALE",
          "LAUNCH_REMAP_REQUIRED");

  private StartSessionLaunchDescriptorGrpcCodec() {}

  public sealed interface Outcome permits DescriptorOutcome, ApplicationFailure {}

  public record DescriptorOutcome(AuthoredWorldLaunchDescriptorEvidence descriptor)
      implements Outcome {
    public DescriptorOutcome {
      Objects.requireNonNull(descriptor, "descriptor");
    }
  }

  /** A persisted domain denial; transport and dependency failures are not represented here. */
  public record ApplicationFailure(String code, String message) implements Outcome {
    public ApplicationFailure {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
      if (!FAILURE_CODES.contains(code)
          || message.length() > 1024
          || !message.startsWith(code + ": ")) {
        throw new IllegalArgumentException("Known deterministic StartSession failure required");
      }
    }
  }

  /** Validated association evidence paired with exactly one descriptor outcome. */
  public record Resolved(Result associationRead, Outcome outcome) {
    public Resolved {
      Objects.requireNonNull(associationRead, "associationRead");
      Objects.requireNonNull(outcome, "outcome");
    }
  }

  /**
   * The descriptor endpoint accepts exact replay only; initial selection remains a separate read.
   */
  public static ReadStartSessionTemplateAssociationRequest toRequest(Request request) {
    requireExactReplay(request);
    return StartSessionTemplateAssociationReadGrpcCodec.toRequest(request);
  }

  /** Decode the reused association request and reject initial or absent selection modes. */
  public static Request fromRequest(ReadStartSessionTemplateAssociationRequest request) {
    Request decoded = StartSessionTemplateAssociationReadGrpcCodec.fromRequest(request);
    requireExactReplay(decoded);
    return decoded;
  }

  public static ResolveStartSessionLaunchDescriptorResponse toDescriptorResponse(
      Request expectedRequest,
      Result associationRead,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return toResponse(
        expectedRequest,
        associationRead,
        new DescriptorOutcome(Objects.requireNonNull(descriptor)));
  }

  public static ResolveStartSessionLaunchDescriptorResponse toFailureResponse(
      Request expectedRequest, Result associationRead, String code, String message) {
    return toResponse(expectedRequest, associationRead, new ApplicationFailure(code, message));
  }

  /** Encodes one complete result and runs the same closed decoder used by receiving callers. */
  public static ResolveStartSessionLaunchDescriptorResponse toResponse(
      Request expectedRequest, Result associationRead, Outcome outcome) {
    requireAssociationBinding(expectedRequest, associationRead);
    Objects.requireNonNull(outcome, "outcome");
    var builder =
        ResolveStartSessionLaunchDescriptorResponse.newBuilder()
            .setSchemaVersion(RESPONSE_SCHEMA_VERSION)
            .setAssociationRead(
                StartSessionTemplateAssociationReadGrpcCodec.toResponse(associationRead));
    if (outcome instanceof DescriptorOutcome descriptorOutcome) {
      requireDescriptorBinding(expectedRequest, associationRead, descriptorOutcome.descriptor());
      builder.setLaunchDescriptor(
          AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
              descriptorOutcome.descriptor()));
    } else if (outcome instanceof ApplicationFailure failure) {
      builder.setApplicationFailure(
          StartSessionLaunchDescriptorApplicationFailure.newBuilder()
              .setCode(failure.code())
              .setMessage(failure.message()));
    } else {
      throw new IllegalArgumentException("Unsupported StartSession descriptor outcome");
    }
    ResolveStartSessionLaunchDescriptorResponse response = builder.build();
    fromResponse(expectedRequest, response);
    return response;
  }

  /** Validates the complete nested association read and exact descriptor or stored denial. */
  public static Resolved fromResponse(
      Request expectedRequest, ResolveStartSessionLaunchDescriptorResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireExactReplay(expectedRequest);
    requireNoUnknownFields(response, "ResolveStartSessionLaunchDescriptorResponse");
    if (response.getSchemaVersion() != RESPONSE_SCHEMA_VERSION
        || !response.hasAssociationRead()
        || response.hasLaunchDescriptor() == response.hasApplicationFailure()) {
      throw new IllegalArgumentException(
          "Versioned StartSession descriptor response must contain one complete outcome");
    }
    Result associationRead =
        StartSessionTemplateAssociationReadGrpcCodec.fromResponse(
            expectedRequest, response.getAssociationRead());
    requireAssociationBinding(expectedRequest, associationRead);
    Outcome outcome;
    if (response.hasLaunchDescriptor()) {
      AuthoredWorldLaunchDescriptorEvidence descriptor =
          AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
              expectedDescriptorRequest(expectedRequest, associationRead.association()),
              response.getLaunchDescriptor());
      requireDescriptorBinding(expectedRequest, associationRead, descriptor);
      outcome = new DescriptorOutcome(descriptor);
    } else {
      var failure = response.getApplicationFailure();
      requireNoUnknownFields(failure, "StartSessionLaunchDescriptorApplicationFailure");
      outcome = new ApplicationFailure(failure.getCode(), failure.getMessage());
    }
    return new Resolved(associationRead, outcome);
  }

  private static void requireAssociationBinding(Request expectedRequest, Result associationRead) {
    requireExactReplay(expectedRequest);
    Objects.requireNonNull(associationRead, "associationRead");
    if (!expectedRequest.equals(associationRead.request())) {
      throw new IllegalArgumentException(
          "Association read differs from the exact StartSession request");
    }
  }

  private static void requireDescriptorBinding(
      Request request, Result associationRead, AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    descriptor.requireValid();
    var association = associationRead.association();
    if (!expectedDescriptorRequest(request, association).equals(descriptor.request())) {
      throw new IllegalArgumentException(
          "Launch descriptor request differs from the original StartSession tuple and association");
    }
    var release = associationRead.releaseBundle();
    if (release.getIsScriptOnly()
        || !release.getScriptPatchVersion().isEmpty()
        || descriptor.scriptPatchVersionPresent()
        || descriptor.remapSetIdPresent()
        || !EMPTY_RUNTIME_FLAGS.equals(descriptor.runtimeFlagsJson())
        || descriptor.versionId() != release.getVersionId()
        || descriptor.releaseBundleId() != release.getId()
        || !descriptor.publishedReleaseBundleRef().equals(release.getPublishedReleaseBundleRef())
        || !descriptor.generationConfigRevision().equals(release.getGenerationConfigRevision())
        || !descriptor.canonicalTenantId().toString().equals(release.getCanonicalTenantId())
        || !association.canonicalVersionId().toString().equals(release.getCanonicalVersionId())
        || !association.publishWorkflowId().equals(release.getPublishWorkflowId())) {
      throw new IllegalArgumentException(
          "Launch descriptor version or release differs from the exact association response");
    }
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request expectedDescriptorRequest(
      Request request, StartSessionTemplateAssociationReadEvidence.Association association) {
    var action = request.decodedTuple().preAuthorizationTuple().action();
    if (!action.scope().tenantId().equals(association.canonicalTenantId())
        || action.target().gameTemplateId() != association.templateId()
        || !request.targetNamespace().equals(association.targetNamespace())) {
      throw new IllegalArgumentException(
          "Association differs from the original authorized StartSession target");
    }
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        request.targetNamespace(),
        request.decodedTuple().controlPlaneRequestId(),
        association.canonicalTenantId(),
        association.worldSlug(),
        association.sourceOperationId(),
        association.sourceEvidenceDigest(),
        association.templateId(),
        false,
        null,
        false,
        null,
        false,
        null,
        true,
        EMPTY_RUNTIME_FLAGS);
  }

  private static void requireExactReplay(Request request) {
    Objects.requireNonNull(request, "request");
    if (!(request.selection() instanceof ExactReplay)) {
      throw new IllegalArgumentException("Exact Game Session-pinned association replay required");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
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
