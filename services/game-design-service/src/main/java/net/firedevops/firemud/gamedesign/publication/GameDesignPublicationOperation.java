package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;

/** Closed original operation input. Codec validation supplies integrity, never authorization. */
public record GameDesignPublicationOperation(
    AccountPublicationAuthorizationBinding account,
    WorldPublishedStartLocationEvidence world) {
  public static final String SCHEMA = "game-design-publication-operation/v1";

  public GameDesignPublicationOperation {
    Objects.requireNonNull(account);
    Objects.requireNonNull(world);
    account = AccountPublicationAuthorizationBinding.fromStored(account.canonicalBytes());
    world = WorldPublishedStartLocationEvidence.fromStored(world.canonicalBytes());
    var selection = account.input().selection();
    var intent = selection.intent();
    var freeze = world.request();
    if (!account.tenantId().equals(freeze.canonicalTenantId())
        || !intent.canonicalVersionId().equals(freeze.canonicalVersionId())
        || !intent.publishRequestId().equals(freeze.publicationRequestId())
        || !selection.digest().equals("sha256:" + freeze.requestDigest())
        || !intent.expectedVersionStateEpoch().equals(Long.toString(freeze.versionStateEpoch()))
        || !intent.selectedCommitId().toString().equals(freeze.appliedCommitId())
        || !Arrays.equals(selection.selectedCommit().canonicalBytes(),
            DraftAuthorizationFenceBinding.fromStored(world.originalAccountBindingBytes()).normalizedInput())) {
      throw new IllegalArgumentException("PUBLICATION_OPERATION_IDENTITY_CONFLICT");
    }
    String workflow = FiremudWorkflowIds.workflowId("publish", account.tenantId().toString(),
        "publish-request", intent.publishRequestId());
    if (!workflow.equals(freeze.publishWorkflowId())) {
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

  public static GameDesignPublicationOperation fromStored(byte[] bytes) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var operation = new GameDesignPublicationOperation(
        AccountPublicationAuthorizationBinding.fromStored(reader.bytes()),
        WorldPublishedStartLocationEvidence.fromStored(reader.bytes()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical publication operation");
    }
    return operation;
  }

  public String workflowId() { return world.request().publishWorkflowId(); }
  public String tenantKey() { return account.input().selection().target().gameDesignVersionTenantKey(); }
  public long versionId() { return account.input().selection().target().gameDesignVersionRowId(); }
  public String selectionDigest() { return account.input().selection().digest(); }
}
