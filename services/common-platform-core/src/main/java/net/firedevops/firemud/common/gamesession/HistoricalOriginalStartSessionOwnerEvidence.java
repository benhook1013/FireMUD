package net.firedevops.firemud.common.gamesession;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;

/**
 * Read-only historical evidence for one exact original StartSession attempt.
 *
 * <p>This carrier proves only the retained original tuple, attached Account projection, first
 * association selection, and descriptor/release binding. It does not represent a current owner
 * claim, authorization, lease, terminal outcome, hold, or admission decision.
 */
public final class HistoricalOriginalStartSessionOwnerEvidence {
  public static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private HistoricalOriginalStartSessionOwnerEvidence() {}

  /** Exact retained launch selector plus the actual original owner attempt and fence. */
  public record Request(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request associationSelector,
      UUID expectedOwnerAttemptId,
      long expectedOwnerFence) {
    public Request {
      Objects.requireNonNull(associationSelector, "associationSelector");
      requireNonNil(expectedOwnerAttemptId, "expectedOwnerAttemptId");
      if (expectedOwnerFence <= 0L) {
        throw new IllegalArgumentException("expectedOwnerFence must be positive");
      }
    }
  }

  /** Immutable association row projection. Mutable Game Instance state is intentionally absent. */
  public record LaunchAssociation(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID gameInstanceUuid,
      String controlPlaneRequestId,
      String launchDescriptorId,
      UUID playableStateNamespaceId,
      RealmEntryPolicy.StateScope playableStateScope,
      boolean publicProduction,
      long capturedStartingRowVersion,
      CompleteLaunchBindingEvidence launchBindingEvidence) {
    public LaunchAssociation {
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(gameInstanceUuid, "gameInstanceUuid");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(playableStateScope, "playableStateScope");
      Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireText(launchDescriptorId, "launchDescriptorId", 128);
      if (playableStateScope != RealmEntryPolicy.StateScope.SHARED || !publicProduction) {
        throw new IllegalArgumentException(
            "Historical StartSession association must retain public SHARED state");
      }
      if (capturedStartingRowVersion < 0L) {
        throw new IllegalArgumentException("capturedStartingRowVersion must not be negative");
      }
      AuthoredWorldLaunchDescriptorEvidence descriptor = launchBindingEvidence.descriptor();
      descriptor.requireValid();
      launchBindingEvidence.releaseAttestation().requireValid(descriptor);
      if (!targetNamespace.equals(descriptor.targetNamespace())
          || !canonicalTenantId.equals(descriptor.canonicalTenantId())
          || !worldSlug.equals(descriptor.worldSlug())
          || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
          || !launchDescriptorId.equals(descriptor.launchDescriptorId())) {
        throw new IllegalArgumentException(
            "Historical launch association differs from its complete descriptor binding");
      }
    }
  }

  /**
   * Complete immutable readback. The projection bytes are the exact retained Account projection;
   * accessors return copies to keep the carrier immutable.
   */
  public static final class Result {
    private final Request request;
    private final StartSessionPostAuthorizationExecutionTuple originalTuple;
    private final UUID ownerAttemptId;
    private final long ownerFence;
    private final byte[] accountRedemptionProjection;
    private final StartSessionTemplateAssociationReadEvidence.Result firstSelection;
    private final StartSessionLaunchDescriptorGrpcCodec.Resolved descriptorPin;
    private final LaunchAssociation launchAssociation;
    private final String templateAssociationRequestDigest;
    private final String templateAssociationResponseDigest;
    private final String descriptorPinRequestDigest;
    private final String descriptorPinResponseDigest;

    public Result(
        Request request,
        StartSessionPostAuthorizationExecutionTuple originalTuple,
        UUID ownerAttemptId,
        long ownerFence,
        byte[] accountRedemptionProjection,
        StartSessionTemplateAssociationReadEvidence.Result firstSelection,
        StartSessionLaunchDescriptorGrpcCodec.Resolved descriptorPin,
        LaunchAssociation launchAssociation,
        String templateAssociationRequestDigest,
        String templateAssociationResponseDigest,
        String descriptorPinRequestDigest,
        String descriptorPinResponseDigest) {
      this.request = Objects.requireNonNull(request, "request");
      this.originalTuple = Objects.requireNonNull(originalTuple, "originalTuple");
      this.ownerAttemptId = requireNonNil(ownerAttemptId, "ownerAttemptId");
      if (ownerFence <= 0L) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
      this.ownerFence = ownerFence;
      this.accountRedemptionProjection =
          Objects.requireNonNull(accountRedemptionProjection, "accountRedemptionProjection")
              .clone();
      this.firstSelection = Objects.requireNonNull(firstSelection, "firstSelection");
      this.descriptorPin = Objects.requireNonNull(descriptorPin, "descriptorPin");
      this.launchAssociation = Objects.requireNonNull(launchAssociation, "launchAssociation");
      this.templateAssociationRequestDigest =
          requireDigest(templateAssociationRequestDigest, "templateAssociationRequestDigest");
      this.templateAssociationResponseDigest =
          requireDigest(templateAssociationResponseDigest, "templateAssociationResponseDigest");
      this.descriptorPinRequestDigest =
          requireDigest(descriptorPinRequestDigest, "descriptorPinRequestDigest");
      this.descriptorPinResponseDigest =
          requireDigest(descriptorPinResponseDigest, "descriptorPinResponseDigest");
      requireCoherentEvidence();
      requirePinDigests();
    }

    public Request request() {
      return request;
    }

    public StartSessionPostAuthorizationExecutionTuple originalTuple() {
      return originalTuple;
    }

    public UUID ownerAttemptId() {
      return ownerAttemptId;
    }

    public long ownerFence() {
      return ownerFence;
    }

    public byte[] accountRedemptionProjection() {
      return accountRedemptionProjection.clone();
    }

    public StartSessionTemplateAssociationReadEvidence.Result firstSelection() {
      return firstSelection;
    }

    public StartSessionLaunchDescriptorGrpcCodec.Resolved descriptorPin() {
      return descriptorPin;
    }

    public LaunchAssociation launchAssociation() {
      return launchAssociation;
    }

    public String templateAssociationRequestDigest() {
      return templateAssociationRequestDigest;
    }

    public String templateAssociationResponseDigest() {
      return templateAssociationResponseDigest;
    }

    public String descriptorPinRequestDigest() {
      return descriptorPinRequestDigest;
    }

    public String descriptorPinResponseDigest() {
      return descriptorPinResponseDigest;
    }

    private void requireCoherentEvidence() {
      var selector = request.associationSelector();
      var action = originalTuple.preAuthorizationTuple().action();
      byte[] tupleBytes = originalTuple.canonicalBytes();
      if (!Arrays.equals(
              StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes).canonicalBytes(),
              tupleBytes)
          || !selector.targetNamespace().equals(action.scope().targetNamespace())
          || !selector.canonicalTenantId().equals(action.scope().tenantId())
          || !selector.controlPlaneRequestId().equals(originalTuple.controlPlaneRequestId())
          || !selector.gameInstanceUuid().equals(launchAssociation.gameInstanceUuid())
          || !selector.targetNamespace().equals(launchAssociation.targetNamespace())
          || !selector.canonicalTenantId().equals(launchAssociation.canonicalTenantId())
          || !selector.worldSlug().equals(launchAssociation.worldSlug())
          || !selector.controlPlaneRequestId().equals(launchAssociation.controlPlaneRequestId())
          || !selector.launchDescriptorId().equals(launchAssociation.launchDescriptorId())
          || !request.expectedOwnerAttemptId().equals(ownerAttemptId)
          || request.expectedOwnerFence() != ownerFence
          || !Arrays.equals(
              StartSessionAccountRedemptionProjection.fromOriginalTuple(originalTuple),
              accountRedemptionProjection)) {
        throw new IllegalArgumentException(
            "Historical StartSession tuple, projection, attempt, or association selector differs");
      }

      var selectionRequest = firstSelection.request();
      if (selectionRequest.schemaVersion()
              != StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION
          || !selector.targetNamespace().equals(selectionRequest.targetNamespace())
          || !Arrays.equals(selectionRequest.canonicalPostAuthorizationTuple(), tupleBytes)
          || !ownerAttemptId.equals(selectionRequest.ownerAttemptId())
          || ownerFence != selectionRequest.ownerFence()
          || !(selectionRequest.selection()
              instanceof StartSessionTemplateAssociationReadEvidence.InitialConfigured)
          || !selector.canonicalTenantId().equals(firstSelection.association().canonicalTenantId())
          || action.target().gameTemplateId() != firstSelection.association().templateId()
          || !selector.worldSlug().equals(firstSelection.association().worldSlug())) {
        throw new IllegalArgumentException(
            "Historical first selection differs from the complete original StartSession tuple");
      }

      var descriptorRequest = descriptorPin.associationRead().request();
      var firstAssociation = firstSelection.association();
      boolean exactReplay =
          descriptorRequest.selection()
                  instanceof StartSessionTemplateAssociationReadEvidence.ExactReplay replay
              && replay.canonicalVersionId().equals(firstAssociation.canonicalVersionId())
              && replay.selectedCommitId().equals(firstAssociation.selectedCommitId())
              && replay.publishWorkflowId().equals(firstAssociation.publishWorkflowId())
              && replay.associationDigest().equals(firstAssociation.associationDigest());
      if (!exactReplay
          || !selector.targetNamespace().equals(descriptorRequest.targetNamespace())
          || !Arrays.equals(descriptorRequest.canonicalPostAuthorizationTuple(), tupleBytes)
          || !ownerAttemptId.equals(descriptorRequest.ownerAttemptId())
          || ownerFence != descriptorRequest.ownerFence()
          || !(descriptorPin.outcome()
              instanceof StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome outcome)
          || !outcome.descriptor().equals(launchAssociation.launchBindingEvidence().descriptor())) {
        throw new IllegalArgumentException(
            "Historical descriptor pin differs from the immutable first selection or launch binding");
      }

      AuthoredWorldLaunchDescriptorEvidence descriptor =
          launchAssociation.launchBindingEvidence().descriptor();
      var releaseAttestation = launchAssociation.launchBindingEvidence().releaseAttestation();
      if (!selector.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
          || !selector.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
          || !selector
              .expectedReleaseAttestationEvidenceDigest()
              .equals(releaseAttestation.evidenceDigest())) {
        throw new IllegalArgumentException(
            "Historical descriptor or release attestation differs from its exact selector");
      }
    }

    private void requirePinDigests() {
      var associationRequest = firstSelection.request();
      var associationRequestWire =
          net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
              .toRequest(associationRequest)
              .toByteArray();
      var associationResponseWire =
          net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
              .toResponse(firstSelection)
              .toByteArray();
      var descriptorRequest = descriptorPin.associationRead().request();
      var descriptorRequestWire =
          StartSessionLaunchDescriptorGrpcCodec.toRequest(descriptorRequest).toByteArray();
      var descriptorResponseWire =
          StartSessionLaunchDescriptorGrpcCodec.toResponse(
                  descriptorRequest, descriptorPin.associationRead(), descriptorPin.outcome())
              .toByteArray();
      if (!templateAssociationRequestDigest.equals(sha256(associationRequestWire))
          || !templateAssociationResponseDigest.equals(sha256(associationResponseWire))
          || !descriptorPinRequestDigest.equals(sha256(descriptorRequestWire))
          || !descriptorPinResponseDigest.equals(sha256(descriptorResponseWire))) {
        throw new IllegalArgumentException(
            "Historical StartSession pin digests differ from their canonical retained evidence");
      }
    }
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
    return value;
  }

  private static void requireText(String value, String name, int maxLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must be non-blank and bounded");
    }
  }

  private static String requireDigest(String value, String name) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }

  public static String sha256(byte[] value) {
    Objects.requireNonNull(value, "value");
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
