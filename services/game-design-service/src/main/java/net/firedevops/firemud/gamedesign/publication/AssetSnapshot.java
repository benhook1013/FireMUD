package net.firedevops.firemud.gamedesign.publication;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item;
import net.firedevops.firemud.common.gamedesign.CommandSource;

/** Ordinary selected source only; no total inventory or template/branding completeness claim. */
public record AssetSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<Item> items) {
  public AssetSnapshot {
    var value =
        new net.firedevops.firemud.common.gamedesign.AssetSnapshot(
            binding, sourceEpoch, inheritedCommitId, genesisReceiptId, items);
    items = value.items();
  }

  @Override
  public List<Item> items() {
    return List.copyOf(items);
  }

  public String canonicalJson() {
    return shared().canonicalJson();
  }

  public byte[] canonicalBytes() {
    return shared().canonicalBytes();
  }

  public String digest() {
    return shared().digest();
  }

  public static AssetSnapshot fromStored(String json) {
    return fromShared(net.firedevops.firemud.common.gamedesign.AssetSnapshot.fromStored(json));
  }

  public net.firedevops.firemud.common.gamedesign.AssetSnapshot shared() {
    return new net.firedevops.firemud.common.gamedesign.AssetSnapshot(
        binding, sourceEpoch, inheritedCommitId, genesisReceiptId, items);
  }

  public static AssetSnapshot fromShared(
      net.firedevops.firemud.common.gamedesign.AssetSnapshot value) {
    return new AssetSnapshot(
        value.binding(),
        value.sourceEpoch(),
        value.inheritedCommitId(),
        value.genesisReceiptId(),
        value.items());
  }

  public record Genesis(TargetProof target, UUID receiptId, String creationTransactionId) {
    public Genesis {
      Objects.requireNonNull(target);
      if (receiptId == null
          || new UUID(0, 0).equals(receiptId)
          || creationTransactionId == null
          || !creationTransactionId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException("Exact ordinary source creation evidence required");
    }
  }

  public record Capture(GameDesignPublicationOperation operation, AssetSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(snapshot);
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding()))
        throw new IllegalArgumentException("Ordinary asset capture differs from selected commit");
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, "game-design-ordinary-asset-source-capture/v1");
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, operation.canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, snapshot.canonicalBytes());
      return out.toByteArray();
    }

    public String digest() {
      return CommandSource.sha256(canonicalBytes());
    }
  }
}
