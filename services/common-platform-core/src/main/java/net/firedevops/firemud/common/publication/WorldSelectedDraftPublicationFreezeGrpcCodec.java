package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeRequest;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezePhase;

/** Closed World selected-freeze wire mapping and exact request/acknowledgement validation. */
public final class WorldSelectedDraftPublicationFreezeGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldSelectedDraftPublicationFreezeGrpcCodec() {}

  public static BeginVersionPublicationFreezeRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    return BeginVersionPublicationFreezeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setCanonicalVersionId(request.canonicalVersionId().toString())
        .setPublicationRequestId(request.publicationRequestId())
        .setExpectedVersionStateEpoch(request.expectedVersionStateEpoch())
        .setRequestDigest(request.requestDigest())
        .setAccountPublicationAuthorizationBinding(
            ByteString.copyFrom(request.accountPublicationAuthorizationBinding()))
        .build();
  }

  /** Decode only after the receiver authenticates the exact same-namespace Game Design peer. */
  public static Request fromRequest(BeginVersionPublicationFreezeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "BeginVersionPublicationFreezeRequest");
    try {
      return new Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          canonicalUuid(request.getCanonicalTenantId()),
          canonicalUuid(request.getCanonicalVersionId()),
          request.getPublicationRequestId(),
          request.getExpectedVersionStateEpoch(),
          request.getRequestDigest(),
          boundedBytes(request.getAccountPublicationAuthorizationBinding().toByteArray()));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("World selected-freeze request is invalid", invalid);
    }
  }

  /** Builds only the complete committed owner-freeze acknowledgement tuple. */
  public static BeginVersionPublicationFreezeResponse toResponse(Acknowledgement acknowledgement) {
    Objects.requireNonNull(acknowledgement, "acknowledgement");
    Request request = acknowledgement.request();
    return BeginVersionPublicationFreezeResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setCanonicalVersionId(request.canonicalVersionId().toString())
        .setPublicationRequestId(request.publicationRequestId())
        .setExpectedVersionStateEpoch(request.expectedVersionStateEpoch())
        .setRequestDigest(request.requestDigest())
        .setAccountPublicationAuthorizationBinding(
            ByteString.copyFrom(request.accountPublicationAuthorizationBinding()))
        .setPublicationFence(acknowledgement.publicationFence().toString())
        .setOwnerFreezePhase(toWirePhase(acknowledgement.ownerFreezePhase()))
        .setAppliedCommitId(acknowledgement.appliedCommitId())
        .setContentDigest(acknowledgement.contentDigest())
        .setDigestSchemaVersion(acknowledgement.digestSchemaVersion())
        .setVersionStateEpoch(acknowledgement.versionStateEpoch())
        .build();
  }

  /** Checks exact request echo and the entire FROZEN acknowledgement tuple. */
  public static WorldSelectedDraftPublicationFreezeEvidence fromResponse(
      Request expected, BeginVersionPublicationFreezeResponse response) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "BeginVersionPublicationFreezeResponse");
    if (response.getSchemaVersion() != expected.schemaVersion()
        || !response.getTargetNamespace().equals(expected.targetNamespace())
        || !response.getCanonicalTenantId().equals(expected.canonicalTenantId().toString())
        || !response.getCanonicalVersionId().equals(expected.canonicalVersionId().toString())
        || !response.getPublicationRequestId().equals(expected.publicationRequestId())
        || response.getExpectedVersionStateEpoch() != expected.expectedVersionStateEpoch()
        || response.getVersionStateEpoch() != expected.expectedVersionStateEpoch()
        || !response.getRequestDigest().equals(expected.requestDigest())
        || !Arrays.equals(
            response.getAccountPublicationAuthorizationBinding().toByteArray(),
            expected.accountPublicationAuthorizationBinding())) {
      throw new IllegalArgumentException("World changed the exact selected-freeze request");
    }
    final Acknowledgement acknowledgement;
    try {
      acknowledgement =
          new Acknowledgement(
              expected,
              response.getVersionStateEpoch(),
              canonicalUuid(response.getPublicationFence()),
              fromWirePhase(response.getOwnerFreezePhase()),
              response.getAppliedCommitId(),
              response.getContentDigest(),
              response.getDigestSchemaVersion());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("World freeze acknowledgement is invalid", invalid);
    }
    return new WorldSelectedDraftPublicationFreezeEvidence(expected, acknowledgement);
  }

  private static WorldSelectedDraftPublicationFreezePhase toWirePhase(OwnerFreezePhase phase) {
    return switch (phase) {
      case FROZEN ->
          WorldSelectedDraftPublicationFreezePhase
              .WORLD_SELECTED_DRAFT_PUBLICATION_FREEZE_PHASE_FROZEN;
    };
  }

  private static OwnerFreezePhase fromWirePhase(WorldSelectedDraftPublicationFreezePhase phase) {
    return switch (phase) {
      case WORLD_SELECTED_DRAFT_PUBLICATION_FREEZE_PHASE_FROZEN -> OwnerFreezePhase.FROZEN;
      case WORLD_SELECTED_DRAFT_PUBLICATION_FREEZE_PHASE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("World owner freeze phase must be FROZEN");
    };
  }

  private static byte[] boundedBytes(byte[] value) {
    if (value.length == 0
        || value.length
            > WorldSelectedDraftPublicationFreezeEvidence.Request.MAX_ACCOUNT_BINDING_BYTES) {
      throw new IllegalArgumentException("Account publication binding is absent or oversized");
    }
    return value;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value) {
    if (value == null) throw new IllegalArgumentException("Canonical UUID required");
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil UUID required");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Canonical non-nil UUID required", invalid);
    }
  }
}
