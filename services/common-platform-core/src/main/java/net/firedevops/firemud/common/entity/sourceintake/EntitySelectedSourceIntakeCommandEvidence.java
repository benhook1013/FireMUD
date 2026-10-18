package net.firedevops.firemud.common.entity.sourceintake;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeCommandRequestSupport;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
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

    public Request {
      SelectedOwnerIntakeCommandRequestSupport.requireValidRequest(
          schemaVersion,
          SCHEMA_VERSION,
          targetNamespace,
          transportRequestId,
          originalAuthorizationBinding,
          freezeEvidence,
          Owner.ENTITY_MANAGEMENT,
          "account-entity-intake-authorization/v1",
          "ENTITY_INTAKE_RETENTION",
          "Invalid Entity selected-source command request");
    }

    public static Request create(
        String namespace,
        SelectedOwnerIntakeAuthorizationBinding originalAuthorizationBinding,
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
      Objects.requireNonNull(
          originalAuthorizationBinding, "original Account authorization is required");
      Objects.requireNonNull(freezeEvidence, "complete World freeze evidence is required");
      return new Request(
          SCHEMA_VERSION,
          namespace,
          SelectedOwnerIntakeCommandRequestSupport.newTransportRequestId(
              originalAuthorizationBinding, freezeEvidence),
          originalAuthorizationBinding,
          freezeEvidence);
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
          && SelectedOwnerIntakeCommandRequestSupport.sameRequest(
              schemaVersion,
              targetNamespace,
              transportRequestId,
              originalAuthorizationBinding,
              freezeEvidence,
              other.schemaVersion,
              other.targetNamespace,
              other.transportRequestId,
              other.originalAuthorizationBinding,
              other.freezeEvidence);
    }

    @Override
    public int hashCode() {
      return SelectedOwnerIntakeCommandRequestSupport.hashCodeFor(
          schemaVersion,
          targetNamespace,
          transportRequestId,
          originalAuthorizationBinding,
          freezeEvidence);
    }
  }
}
