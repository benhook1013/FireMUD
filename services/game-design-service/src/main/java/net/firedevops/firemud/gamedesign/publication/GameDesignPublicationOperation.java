package net.firedevops.firemud.gamedesign.publication;

import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Closed original operation input. Codec validation supplies integrity, never authorization. */
public final class GameDesignPublicationOperation {
  public static final String SCHEMA = GameDesignPublicationOperationBinding.SCHEMA;
  private final GameDesignPublicationOperationBinding binding;

  public GameDesignPublicationOperation(
      AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world,
      WorldSelectedPublicationArtifactInventoryEvidence inventory) {
    this(new GameDesignPublicationOperationBinding(account, world, inventory));
  }

  private GameDesignPublicationOperation(GameDesignPublicationOperationBinding binding) {
    this.binding = binding;
  }

  public AccountPublicationAuthorizationBinding account() {
    return binding.account();
  }

  public WorldPublishedStartLocationEvidence world() {
    return binding.world();
  }

  public WorldSelectedPublicationArtifactInventoryEvidence inventory() {
    return binding.inventory();
  }

  public byte[] canonicalBytes() {
    return binding.canonicalBytes();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GameDesignPublicationOperation operation
        && binding.equals(operation.binding);
  }

  @Override
  public int hashCode() {
    return binding.hashCode();
  }

  public static GameDesignPublicationOperation fromStored(byte[] bytes) {
    return new GameDesignPublicationOperation(
        GameDesignPublicationOperationBinding.fromStored(bytes));
  }

  public String workflowId() {
    return world().request().publishWorkflowId();
  }

  public String tenantKey() {
    return account().input().selection().target().gameDesignVersionTenantKey();
  }

  public long versionId() {
    return account().input().selection().target().gameDesignVersionRowId();
  }

  public String selectionDigest() {
    return account().input().selection().digest();
  }
}
