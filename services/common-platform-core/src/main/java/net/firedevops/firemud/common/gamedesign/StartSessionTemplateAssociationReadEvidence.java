package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;

/** Closed, public evidence returned by the standalone StartSession template-association read. */
public final class StartSessionTemplateAssociationReadEvidence {
  public static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private StartSessionTemplateAssociationReadEvidence() {}

  public sealed interface Selection permits InitialConfigured, ExactReplay {}

  /** Resolve only the owner-proved current configured reference, never a caller version. */
  public record InitialConfigured() implements Selection {}

  /** Reuse one exact selection already pinned by Game Session to the original control-plane ID. */
  public record ExactReplay(
      UUID canonicalVersionId,
      UUID selectedCommitId,
      String publishWorkflowId,
      String associationDigest)
      implements Selection {
    public ExactReplay {
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireNonNil(selectedCommitId, "selectedCommitId");
      requireText(publishWorkflowId, "publishWorkflowId", 256);
      requireDigest(associationDigest, "associationDigest");
    }
  }

  /** Exact authenticated StartSession identity plus the actual Game Session owner attempt/fence. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] canonicalPostAuthorizationTuple,
      UUID ownerAttemptId,
      long ownerFence,
      Selection selection) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported template association read schema version");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Game Session namespace is required");
      }
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(ownerAttemptId, "ownerAttemptId");
      if (ownerFence <= 0L) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
      Objects.requireNonNull(selection, "selection");
      if (canonicalPostAuthorizationTuple == null
          || canonicalPostAuthorizationTuple.length == 0
          || canonicalPostAuthorizationTuple.length
              > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
        throw new IllegalArgumentException("Complete bounded StartSession tuple is required");
      }
      canonicalPostAuthorizationTuple = canonicalPostAuthorizationTuple.clone();
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(canonicalPostAuthorizationTuple);
      if (!Arrays.equals(tuple.canonicalBytes(), canonicalPostAuthorizationTuple)
          || !targetNamespace.equals(
              tuple.preAuthorizationTuple().action().scope().targetNamespace())) {
        throw new IllegalArgumentException(
            "Exact canonical StartSession tuple and namespace are required");
      }
    }

    @Override
    public byte[] canonicalPostAuthorizationTuple() {
      return canonicalPostAuthorizationTuple.clone();
    }

    public StartSessionPostAuthorizationExecutionTuple decodedTuple() {
      return StartSessionPostAuthorizationExecutionTuple.decode(canonicalPostAuthorizationTuple);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(canonicalPostAuthorizationTuple, that.canonicalPostAuthorizationTuple)
          && ownerAttemptId.equals(that.ownerAttemptId)
          && ownerFence == that.ownerFence
          && selection.equals(that.selection);
    }

    @Override
    public int hashCode() {
      return 31
              * Objects.hash(
                  schemaVersion,
                  targetNamespace,
                  readRequestId,
                  ownerAttemptId,
                  ownerFence,
                  selection)
          + Arrays.hashCode(canonicalPostAuthorizationTuple);
    }
  }

  /** Public projection only; the persisted canonical association bytes are deliberately private. */
  public record Association(
      UUID canonicalTenantId,
      long templateId,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      String publishWorkflowId,
      String publicationSelectionDigest,
      String associationDigest,
      String targetNamespace,
      UUID intakeRequestId,
      UUID worldOperationId,
      UUID sourceOperationId,
      String worldSlug,
      String sourceEvidenceDigest,
      ByIdReadRequest worldReadRequest,
      PublicReceipt worldReceipt) {
    public Association {
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      if (templateId <= 0L) throw new IllegalArgumentException("templateId must be positive");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireNonNil(selectedCommitId, "selectedCommitId");
      requireText(publishWorkflowId, "publishWorkflowId", 256);
      requireDigest(publicationSelectionDigest, "publicationSelectionDigest");
      requireDigest(associationDigest, "associationDigest");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical target namespace is required");
      }
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(worldOperationId, "worldOperationId");
      requireNonNil(sourceOperationId, "sourceOperationId");
      requireText(worldSlug, "worldSlug", 128);
      requireDigest(sourceEvidenceDigest, "sourceEvidenceDigest");
      Objects.requireNonNull(worldReadRequest, "worldReadRequest");
      Objects.requireNonNull(worldReceipt, "worldReceipt");
      if (worldReadRequest.schemaVersion() != SCHEMA_VERSION
          || !targetNamespace.equals(worldReadRequest.targetNamespace())
          || !canonicalTenantId.equals(worldReadRequest.canonicalTenantId())
          || !intakeRequestId.equals(worldReadRequest.intakeRequestId())
          || !targetNamespace.equals(worldReceipt.targetNamespace())
          || !canonicalTenantId.equals(worldReceipt.canonicalTenantId())
          || !intakeRequestId.equals(worldReceipt.intakeRequestId())
          || !worldOperationId.equals(worldReceipt.operationId())
          || !sourceOperationId.equals(worldReceipt.sourceOperationId())
          || !worldSlug.equals(worldReceipt.worldSlug())
          || !sourceEvidenceDigest.equals(worldReceipt.sourceEvidenceDigest())) {
        throw new IllegalArgumentException(
            "Public World intake receipt differs from the exact stored association");
      }
      // Re-encode through the established owner codec to ensure its complete public receipt stays
      // closed and satisfies the original by-ID request correlation.
      WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(worldReadRequest, worldReceipt);
    }

    public static Association fromStoredProjection(
        UUID canonicalTenantId,
        long templateId,
        UUID canonicalVersionId,
        UUID selectedCommitId,
        String publishWorkflowId,
        String publicationSelectionDigest,
        String associationDigest,
        String targetNamespace,
        UUID intakeRequestId,
        UUID worldOperationId,
        UUID sourceOperationId,
        String worldSlug,
        String sourceEvidenceDigest,
        ReadAuthoredWorldSourceIntakeByIdRequest worldReadRequest,
        ReadAuthoredWorldSourceIntakeByIdResponse worldReadResponse) {
      ByIdReadRequest typedRequest =
          WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdRequest(worldReadRequest);
      PublicReceipt typedReceipt =
          WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdResponse(typedRequest, worldReadResponse);
      return new Association(
          canonicalTenantId,
          templateId,
          canonicalVersionId,
          selectedCommitId,
          publishWorkflowId,
          publicationSelectionDigest,
          associationDigest,
          targetNamespace,
          intakeRequestId,
          worldOperationId,
          sourceOperationId,
          worldSlug,
          sourceEvidenceDigest,
          typedRequest,
          typedReceipt);
    }
  }

  public record Result(
      Request request,
      Association association,
      PublishedReleaseBundle releaseBundle,
      WorldPublishedStartLocationEvidence worldPublishedStartLocationEvidence,
      long phaseEpoch) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Generated protobuf release bundles are immutable value messages.")
    public Result {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(association, "association");
      Objects.requireNonNull(releaseBundle, "releaseBundle");
      Objects.requireNonNull(
          worldPublishedStartLocationEvidence, "worldPublishedStartLocationEvidence");
      if (phaseEpoch <= 0L) throw new IllegalArgumentException("Positive phase epoch is required");
      StartSessionPostAuthorizationExecutionTuple tuple = request.decodedTuple();
      var action = tuple.preAuthorizationTuple().action();
      if (!request.targetNamespace().equals(association.targetNamespace())
          || !action.scope().tenantId().equals(association.canonicalTenantId())
          || action.target().gameTemplateId() != association.templateId()
          || !association
              .canonicalTenantId()
              .toString()
              .equals(releaseBundle.getCanonicalTenantId())
          || !association
              .canonicalVersionId()
              .toString()
              .equals(releaseBundle.getCanonicalVersionId())
          || !association.publishWorkflowId().equals(releaseBundle.getPublishWorkflowId())) {
        throw new IllegalArgumentException(
            "Association and release differ from the exact authorized StartSession target");
      }
      var worldSelector = worldPublishedStartLocationEvidence.request();
      if (!request.targetNamespace().equals(worldSelector.targetNamespace())
          || !association.canonicalTenantId().equals(worldSelector.canonicalTenantId())
          || !association.canonicalVersionId().equals(worldSelector.canonicalVersionId())
          || !association.publishWorkflowId().equals(worldSelector.publishWorkflowId())
          || !association.selectedCommitId().toString().equals(worldSelector.appliedCommitId())) {
        throw new IllegalArgumentException(
            "World published selector differs from exact association and release identity");
      }
      requireNoUnknownFields(releaseBundle, "PublishedReleaseBundle");
      requireCompleteRelease(releaseBundle, worldPublishedStartLocationEvidence);
      if (request.selection() instanceof ExactReplay replay
          && (!replay.canonicalVersionId().equals(association.canonicalVersionId())
              || !replay.selectedCommitId().equals(association.selectedCommitId())
              || !replay.publishWorkflowId().equals(association.publishWorkflowId())
              || !replay.associationDigest().equals(association.associationDigest()))) {
        throw new IllegalArgumentException(
            "Exact replay differs from the originally pinned result");
      }
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Generated protobuf release bundles are immutable value messages.")
    public PublishedReleaseBundle releaseBundle() {
      return releaseBundle;
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) throw new IllegalArgumentException(field + " must be non-nil");
  }

  private static void requireText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be nonblank and bounded");
    }
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a canonical SHA-256 digest");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<com.google.protobuf.Descriptors.FieldDescriptor, Object> field :
        message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType()
          != com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
      if (field.getKey().isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + field.getKey().getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + field.getKey().getName());
      }
    }
  }

  private static void requireCompleteRelease(
      PublishedReleaseBundle bundle, WorldPublishedStartLocationEvidence worldEvidence) {
    if (bundle.getId() <= 0L
        || bundle.getVersionId() <= 0L
        || bundle.getVersionNumber() <= 0
        || !bundle.hasManifestSchemaVersion()) {
      throw new IllegalArgumentException("Complete selector-bearing published release is required");
    }
    List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts =
        bundle.getArtifactDigestsList().stream()
            .map(
                value ->
                    new AuthoredWorldReleaseAttestationEvidence.Artifact(
                        value.getUsageKey(),
                        value.getArtifactKind(),
                        value.getImmutableObjectKey(),
                        value.getContentDigest(),
                        value.getContentType(),
                        value.getArtifactSchemaVersion()))
            .toList();
    List<GameDesignPublicationTerminalEvidence.Participant> participants =
        bundle.getParticipantDigestsList().stream()
            .map(
                value ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        value.getParticipantKey(),
                        value.getScopeValue(),
                        null,
                        value.getAppliedCommitId(),
                        value.getContentDigest(),
                        value.getDigestSchemaVersion(),
                        value.hasAbilitySchemaDigest() ? value.getAbilitySchemaDigest() : null,
                        null,
                        null))
            .toList();
    new GameDesignPublicationTerminalEvidence.ReleaseContent(
        UUID.fromString(bundle.getCanonicalTenantId()),
        UUID.fromString(bundle.getCanonicalVersionId()),
        bundle.getPublishedReleaseBundleRef(),
        bundle.getVersionNumber(),
        bundle.getAttestationSchemaVersion(),
        bundle.getPublishWorkflowId(),
        bundle.getManifestHash(),
        bundle.getManifestSchemaVersion(),
        artifacts,
        bundle.getRequiredManifestAssetKeysList(),
        participants,
        bundle.getCommandDefinitionsList(),
        bundle.getGenerationConfigRevision(),
        worldEvidence);
  }
}
