package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Original closed publication input; byte integrity never authenticates its producer. */
public record GameDesignPublicationOperationBinding(
    AccountPublicationAuthorizationBinding account, WorldPublishedStartLocationEvidence world) {
  public static final String SCHEMA = "game-design-publication-operation/v1";

  public GameDesignPublicationOperationBinding {
    account =
        AccountPublicationAuthorizationBinding.fromStored(
            Objects.requireNonNull(account).canonicalBytes());
    world =
        WorldPublishedStartLocationEvidence.fromStored(
            Objects.requireNonNull(world).canonicalBytes());
    var selection = account.input().selection();
    var intent = selection.intent();
    var freeze = world.request();
    if (!account.tenantId().equals(freeze.canonicalTenantId())
        || !intent.canonicalVersionId().equals(freeze.canonicalVersionId())
        || !intent.publishRequestId().equals(freeze.publicationRequestId())
        || !selection.digest().equals("sha256:" + freeze.requestDigest())
        || !intent.expectedVersionStateEpoch().equals(Long.toString(freeze.versionStateEpoch()))
        || !intent.selectedCommitId().toString().equals(freeze.appliedCommitId())
        || !Arrays.equals(
            selection.selectedCommit().canonicalBytes(),
            DraftAuthorizationFenceBinding.fromStored(world.originalAccountBindingBytes())
                .normalizedInput())) {
      throw new IllegalArgumentException("PUBLICATION_OPERATION_IDENTITY_CONFLICT");
    }
    if (!PublicationDigestRequestBinding.full(
            account.tenantId().toString(),
            Long.toString(selection.target().gameDesignVersionRowId()),
            intent.publishRequestId())
        .derivedWorkflowIdentity()
        .equals(freeze.publishWorkflowId())) {
      throw new IllegalArgumentException("PUBLICATION_OPERATION_WORKFLOW_CONFLICT");
    }
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, account.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, world.canonicalBytes());
    return out.toByteArray();
  }

  public static GameDesignPublicationOperationBinding fromStored(byte[] bytes) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var operation =
        new GameDesignPublicationOperationBinding(
            AccountPublicationAuthorizationBinding.fromStored(reader.bytes()),
            WorldPublishedStartLocationEvidence.fromStored(reader.bytes()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical publication operation");
    }
    return operation;
  }
}
