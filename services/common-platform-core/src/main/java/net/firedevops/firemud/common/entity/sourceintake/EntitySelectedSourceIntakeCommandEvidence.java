package net.firedevops.firemud.common.entity.sourceintake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;

/** Exact Game Design command correlation and the immutable Entity receipt. */
public record EntitySelectedSourceIntakeCommandEvidence(
    Request request, EntityEmptySelectedSourceIntakeReceipt receipt) {
  public EntitySelectedSourceIntakeCommandEvidence {
    Objects.requireNonNull(request, "selected-source command request is required");
    Objects.requireNonNull(receipt, "committed Entity receipt is required");
    try {
      receipt.requireSameRequest(
          request.targetNamespace(),
          request.originalAuthorizationBinding(),
          request.freezeEvidence());
    } catch (RuntimeException mismatch) {
      throw new IllegalArgumentException(
          "Entity receipt differs from the complete selected-source command", mismatch);
    }
    if (!Arrays.equals(
            request.originalIntakeAuthorizationBinding(), receipt.authorizationBindingBytes())
        || !request.intakeAuthorizationDigest().equals(receipt.authorizationBindingDigest())) {
      throw new IllegalArgumentException(
          "Entity receipt differs from the original Account authorization bytes");
    }
  }

  /** Caller-generated transport correlation; all retained inputs remain the original values. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    public static final int SCHEMA_VERSION = 1;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public Request {
      Objects.requireNonNull(
          originalAuthorizationBinding, "original Account authorization is required");
      Objects.requireNonNull(freezeEvidence, "complete World freeze evidence is required");
      if (schemaVersion != SCHEMA_VERSION
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || !targetNamespace.equals(originalAuthorizationBinding.targetNamespace())
          || originalAuthorizationBinding.canonicalBytes().length == 0
          || originalAuthorizationBinding.canonicalBytes().length
              > SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          || originalAuthorizationBinding.owner() != Owner.ENTITY_MANAGEMENT
          || !"account-entity-intake-authorization/v1".equals(originalAuthorizationBinding.schema())
          || !"ENTITY_INTAKE_RETENTION".equals(originalAuthorizationBinding.purpose())
          || !targetNamespace.equals(freezeEvidence.request().targetNamespace())
          || !originalAuthorizationBinding
              .tenantId()
              .equals(freezeEvidence.request().canonicalTenantId())
          || !originalAuthorizationBinding
              .versionId()
              .equals(freezeEvidence.request().canonicalVersionId())
          || !originalAuthorizationBinding
              .selected()
              .equals(
                  freezeEvidence.request().accountBinding().input().selection().selectedCommit())
          || !originalAuthorizationBinding
              .selected()
              .commitId()
              .toString()
              .equals(freezeEvidence.acknowledgement().appliedCommitId())) {
        throw invalid();
      }
      DraftAuthorizationFenceBinding.requireUuid(transportRequestId);
      if (NIL_UUID.equals(transportRequestId)
          || collidesWithRetainedIdentity(
              transportRequestId, originalAuthorizationBinding, freezeEvidence)) {
        throw invalid();
      }
    }

    public static Request create(
        String namespace,
        SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
      Objects.requireNonNull(
          originalAuthorizationBinding, "original Account authorization is required");
      Objects.requireNonNull(freezeEvidence, "complete World freeze evidence is required");
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (collidesWithRetainedIdentity(
          correlation, originalAuthorizationBinding, freezeEvidence));
      return new Request(
          SCHEMA_VERSION, namespace, correlation, originalAuthorizationBinding, freezeEvidence);
    }

    public byte[] originalIntakeAuthorizationBinding() {
      return originalAuthorizationBinding.canonicalBytes();
    }

    public String intakeAuthorizationDigest() {
      return originalAuthorizationBinding.digest();
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Request other
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && transportRequestId.equals(other.transportRequestId)
          && Arrays.equals(
              originalIntakeAuthorizationBinding(), other.originalIntakeAuthorizationBinding())
          && intakeAuthorizationDigest().equals(other.intakeAuthorizationDigest())
          && freezeEvidence.equals(other.freezeEvidence);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          schemaVersion,
          targetNamespace,
          transportRequestId,
          Arrays.hashCode(originalIntakeAuthorizationBinding()),
          intakeAuthorizationDigest(),
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

    private static IllegalArgumentException invalid() {
      return new IllegalArgumentException("Invalid Entity selected-source command request");
    }
  }
}
