package net.firedevops.firemud.gamedesign.publication;

import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Closed original operation input. Codec validation supplies integrity, never authorization. */
public record GameDesignPublicationOperation(
    AccountPublicationAuthorizationBinding account,
    WorldPublishedStartLocationEvidence world,
    WorldSelectedPublicationArtifactInventoryEvidence inventory) {
  public static final String SCHEMA = GameDesignPublicationOperationBinding.SCHEMA;

  public GameDesignPublicationOperation {
    var binding = new GameDesignPublicationOperationBinding(account, world, inventory);
    account = binding.account();
    world = binding.world();
    inventory = binding.inventory();
  }

  public byte[] canonicalBytes() {
    return new GameDesignPublicationOperationBinding(account, world, inventory).canonicalBytes();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GameDesignPublicationOperation operation
        && java.util.Arrays.equals(canonicalBytes(), operation.canonicalBytes());
  }

  @Override
  public int hashCode() {
    return java.util.Arrays.hashCode(canonicalBytes());
  }

  public static GameDesignPublicationOperation fromStored(byte[] bytes) {
    var binding = GameDesignPublicationOperationBinding.fromStored(bytes);
    return new GameDesignPublicationOperation(
        binding.account(), binding.world(), binding.inventory());
  }

  public String workflowId() {
    return world.request().publishWorkflowId();
  }

  public String tenantKey() {
    return account.input().selection().target().gameDesignVersionTenantKey();
  }

  public long versionId() {
    return account.input().selection().target().gameDesignVersionRowId();
  }

  public String selectionDigest() {
    return account.input().selection().digest();
  }
}
