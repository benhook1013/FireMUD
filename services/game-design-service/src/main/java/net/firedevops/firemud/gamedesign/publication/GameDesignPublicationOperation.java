package net.firedevops.firemud.gamedesign.publication;

import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Closed original operation input. Codec validation supplies integrity, never authorization. */
public record GameDesignPublicationOperation(
    AccountPublicationAuthorizationBinding account, WorldPublishedStartLocationEvidence world) {
  public static final String SCHEMA = GameDesignPublicationOperationBinding.SCHEMA;

  public GameDesignPublicationOperation {
    var binding = new GameDesignPublicationOperationBinding(account, world);
    account = binding.account();
    world = binding.world();
  }

  public byte[] canonicalBytes() {
    return new GameDesignPublicationOperationBinding(account, world).canonicalBytes();
  }

  public static GameDesignPublicationOperation fromStored(byte[] bytes) {
    var binding = GameDesignPublicationOperationBinding.fromStored(bytes);
    return new GameDesignPublicationOperation(binding.account(), binding.world());
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
