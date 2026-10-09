package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionRequest;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionResponse;

/** Closed association transport reusing the immutable source and Version evidence codecs. */
public final class WorldAuthoredVersionIdentityGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 4096;
  public static final int MAX_RESPONSE_WIRE_BYTES = 32768;

  private WorldAuthoredVersionIdentityGrpcCodec() {}

  public static AssociateAuthoredWorldVersionRequest toRequest(
      WorldAuthoredVersionIdentityEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        AssociateAuthoredWorldVersionRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setSourceOperationId(request.sourceOperationId().toString())
            .setSourceEvidenceDigest(request.sourceEvidenceDigest())
            .setExpectedCanonicalVersionId(request.expectedCanonicalVersionId().toString())
            .setGameDesignVersionId(request.gameDesignVersionId())
            .setReadRequestId(request.readRequestId().toString())
            .build();
    requireClosed(wire, MAX_REQUEST_WIRE_BYTES);
    return wire;
  }

  public static WorldAuthoredVersionIdentityEvidence.Request fromRequest(
      AssociateAuthoredWorldVersionRequest wire) {
    requireClosed(wire, MAX_REQUEST_WIRE_BYTES);
    return new WorldAuthoredVersionIdentityEvidence.Request(
        wire.getSchemaVersion(),
        wire.getTargetNamespace(),
        WorldAuthoredVersionIdentityEvidence.parseUuid(wire.getCanonicalTenantId()),
        wire.getWorldSlug(),
        WorldAuthoredVersionIdentityEvidence.parseUuid(wire.getSourceOperationId()),
        wire.getSourceEvidenceDigest(),
        WorldAuthoredVersionIdentityEvidence.parseUuid(wire.getExpectedCanonicalVersionId()),
        wire.getGameDesignVersionId(),
        WorldAuthoredVersionIdentityEvidence.parseUuid(wire.getReadRequestId()));
  }

  public static AssociateAuthoredWorldVersionResponse toResponse(
      WorldAuthoredVersionIdentityEvidence.Result result) {
    Objects.requireNonNull(result, "result");
    var intake = result.sourceIntakeReceipt();
    var binding =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            intake.schemaVersion(),
            intake.targetNamespace(),
            intake.intakeRequestId(),
            intake.canonicalTenantId(),
            intake.worldSlug(),
            intake.sourceOperationId(),
            intake.sourceEvidenceDigest());
    var wire =
        AssociateAuthoredWorldVersionResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setOperationId(result.operationId().toString())
            .setSourceIntakeReceipt(
                WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(binding, intake))
            .setVersionStateEvidence(
                AuthoredWorldVersionStateGrpcCodec.toResponse(result.versionStateEvidence()))
            .build();
    requireClosed(wire, MAX_RESPONSE_WIRE_BYTES);
    return wire;
  }

  public static WorldAuthoredVersionIdentityEvidence.Result fromResponse(
      WorldAuthoredVersionIdentityEvidence.Request request,
      AssociateAuthoredWorldVersionResponse wire) {
    Objects.requireNonNull(request, "request");
    requireClosed(wire, MAX_RESPONSE_WIRE_BYTES);
    if (!wire.hasRequest()
        || !request.equals(fromRequest(wire.getRequest()))
        || !wire.hasSourceIntakeReceipt()
        || !wire.hasVersionStateEvidence()
        || !wire.getVersionStateEvidence().hasEvidence()) {
      throw new IllegalArgumentException("Complete association and exact request echo required");
    }
    var intakeWire = wire.getSourceIntakeReceipt();
    var intakeBinding =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            intakeWire.getSchemaVersion(),
            intakeWire.getTargetNamespace(),
            WorldAuthoredVersionIdentityEvidence.parseUuid(intakeWire.getIntakeRequestId()),
            WorldAuthoredVersionIdentityEvidence.parseUuid(intakeWire.getCanonicalTenantId()),
            intakeWire.getWorldSlug(),
            WorldAuthoredVersionIdentityEvidence.parseUuid(intakeWire.getSourceOperationId()),
            intakeWire.getExpectedSourceEvidenceDigest());
    var intake = WorldAuthoredSourceIntakeGrpcCodec.fromIntakeResponse(intakeBinding, intakeWire);
    // On immutable retry, decode the ORIGINAL stored read correlation, never rewrite it.
    var retained = wire.getVersionStateEvidence().getEvidence();
    var originalRequest =
        new net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence.Request(
            retained.getSchemaVersion(),
            retained.getTargetNamespace(),
            WorldAuthoredVersionIdentityEvidence.parseUuid(retained.getReadRequestId()),
            WorldAuthoredVersionIdentityEvidence.parseUuid(retained.getCanonicalTenantId()),
            retained.getWorldSlug(),
            WorldAuthoredVersionIdentityEvidence.parseUuid(retained.getSourceOperationId()),
            retained.getExpectedSourceEvidenceDigest(),
            retained.getVersionId());
    var version =
        AuthoredWorldVersionStateGrpcCodec.fromResponse(
            originalRequest, wire.getVersionStateEvidence());
    return new WorldAuthoredVersionIdentityEvidence.Result(
        request,
        WorldAuthoredVersionIdentityEvidence.parseUuid(wire.getOperationId()),
        intake,
        version);
  }

  private static void requireClosed(Message wire, int maximum) {
    Objects.requireNonNull(wire, "wire");
    if (!wire.getUnknownFields().asMap().isEmpty() || wire.getSerializedSize() > maximum) {
      throw new IllegalArgumentException("Unsupported association fields or size");
    }
  }
}
