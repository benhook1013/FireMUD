package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Original closed publication input; byte integrity never authenticates its producer. */
public record GameDesignPublicationOperationBinding(
    AccountPublicationAuthorizationBinding account,
    WorldPublishedStartLocationEvidence world,
    WorldSelectedPublicationArtifactInventoryEvidence inventory) {
  public static final String SCHEMA = "game-design-publication-operation/v2";
  public static final int MAX_OPERATION_BYTES = 4 * 1024 * 1024;

  public GameDesignPublicationOperationBinding {
    account =
        AccountPublicationAuthorizationBinding.fromStored(
            Objects.requireNonNull(account).canonicalBytes());
    world =
        WorldPublishedStartLocationEvidence.fromStored(
            Objects.requireNonNull(world).canonicalBytes());
    requireAccountWorldCorrelation(account, world);
    Objects.requireNonNull(inventory, "inventory");
    inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
            account, world, inventory.canonicalBytes(), inventory.digest());
    long size =
        5L * Integer.BYTES
            + SCHEMA.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + account.canonicalBytes().length
            + world.canonicalBytes().length
            + inventory.canonicalBytes().length
            + inventory.digest().length();
    if (size > MAX_OPERATION_BYTES)
      throw new IllegalArgumentException("Publication operation exceeds 4 MiB");
  }

  /** Historical correlation only; neither authentication nor complete operation authority. */
  public static void requireAccountWorldCorrelation(
      AccountPublicationAuthorizationBinding account, WorldPublishedStartLocationEvidence world) {
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
    DraftAuthorizationFenceBinding.frame(out, inventory.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, inventory.digest());
    return out.toByteArray();
  }

  public static GameDesignPublicationOperationBinding fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_OPERATION_BYTES)
      throw new IllegalArgumentException("Complete bounded v2 publication operation required");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var account = AccountPublicationAuthorizationBinding.fromStored(reader.bytes());
    var world = WorldPublishedStartLocationEvidence.fromStored(reader.bytes());
    var inventoryBytes = reader.bytes();
    var digestBytes = reader.bytes();
    String digest = new String(digestBytes, java.nio.charset.StandardCharsets.US_ASCII);
    var operation =
        new GameDesignPublicationOperationBinding(
            account,
            world,
            WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
                account, world, inventoryBytes, digest));
    reader.requireEnd();
    if (!Arrays.equals(bytes, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical publication operation");
    }
    return operation;
  }
}
