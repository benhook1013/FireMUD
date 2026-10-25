package net.firedevops.firemud.common.account.sourceintake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;

/** Shared mechanics for owner-specific selected-source command request records. */
public final class SelectedOwnerIntakeCommandRequestSupport {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private SelectedOwnerIntakeCommandRequestSupport() {}

  public static void requireValidRequest(
      int schemaVersion,
      int expectedSchemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      Owner expectedOwner,
      String expectedSchema,
      String expectedPurpose,
      String invalidRequestMessage) {
    Objects.requireNonNull(
        originalAuthorizationBinding, "original Account authorization is required");
    Objects.requireNonNull(freezeEvidence, "complete World freeze evidence is required");
    if (schemaVersion != expectedSchemaVersion
        || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || !targetNamespace.equals(originalAuthorizationBinding.targetNamespace())
        || originalAuthorizationBinding.canonicalBytes().length == 0
        || originalAuthorizationBinding.canonicalBytes().length
            > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
        || originalAuthorizationBinding.owner() != expectedOwner
        || !expectedSchema.equals(originalAuthorizationBinding.schema())
        || !expectedPurpose.equals(originalAuthorizationBinding.purpose())
        || !targetNamespace.equals(freezeEvidence.request().targetNamespace())
        || !originalAuthorizationBinding
            .tenantId()
            .equals(freezeEvidence.request().canonicalTenantId())
        || !originalAuthorizationBinding
            .versionId()
            .equals(freezeEvidence.request().canonicalVersionId())
        || !originalAuthorizationBinding
            .selected()
            .equals(freezeEvidence.request().accountBinding().input().selection().selectedCommit())
        || !originalAuthorizationBinding
            .selected()
            .commitId()
            .toString()
            .equals(freezeEvidence.acknowledgement().appliedCommitId())) {
      throw invalid(invalidRequestMessage);
    }
    DraftAuthorizationFenceBinding.requireUuid(transportRequestId);
    if (NIL_UUID.equals(transportRequestId)
        || collidesWithRetainedIdentity(
            transportRequestId, originalAuthorizationBinding, freezeEvidence)) {
      throw invalid(invalidRequestMessage);
    }
  }

  public static UUID newTransportRequestId(
      SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    UUID correlation;
    do {
      correlation = UUID.randomUUID();
    } while (collidesWithRetainedIdentity(
        correlation, originalAuthorizationBinding, freezeEvidence));
    return correlation;
  }

  public static boolean sameRequest(
      int schemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      int otherSchemaVersion,
      String otherTargetNamespace,
      UUID otherTransportRequestId,
      SelectedOwnerIntakeAuthorizationBinding otherOriginalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence otherFreezeEvidence) {
    return schemaVersion == otherSchemaVersion
        && targetNamespace.equals(otherTargetNamespace)
        && transportRequestId.equals(otherTransportRequestId)
        && Arrays.equals(
            originalAuthorizationBinding.canonicalBytes(),
            otherOriginalAuthorizationBinding.canonicalBytes())
        && originalAuthorizationBinding.digest().equals(otherOriginalAuthorizationBinding.digest())
        && freezeEvidence.equals(otherFreezeEvidence);
  }

  public static int hashCodeFor(
      int schemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    return Objects.hash(
        schemaVersion,
        targetNamespace,
        transportRequestId,
        Arrays.hashCode(originalAuthorizationBinding.canonicalBytes()),
        originalAuthorizationBinding.digest(),
        freezeEvidence);
  }

  private static boolean collidesWithRetainedIdentity(
      UUID candidate,
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freeze) {
    if (candidate.equals(binding.operationId())
        || candidate.equals(binding.fenceId())
        || candidate.equals(binding.intakeRequestId())
        || candidate.equals(binding.selected().requestId())
        || candidate.equals(binding.selected().commitId())) {
      return true;
    }
    var account = freeze.request().accountBinding();
    var selection = account.input().selection();
    List<UUID> freezeIds =
        new ArrayList<>(
            List.of(
                account.operationId(),
                account.fenceId(),
                selection.fenceRequestId(),
                selection.fenceCommitId(),
                selection.selectedCommit().requestId(),
                selection.selectedCommit().commitId(),
                freeze.acknowledgement().intakeRequestId(),
                freeze.acknowledgement().publicationFence()));
    if (freezeIds.contains(candidate)) return true;
    try {
      UUID publicationRequestId = UUID.fromString(freeze.request().publicationRequestId());
      return publicationRequestId.toString().equals(freeze.request().publicationRequestId())
          && publicationRequestId.equals(candidate);
    } catch (IllegalArgumentException notUuid) {
      return false;
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
