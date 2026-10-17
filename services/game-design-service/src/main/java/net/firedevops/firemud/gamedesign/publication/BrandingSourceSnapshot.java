package net.firedevops.firemud.gamedesign.publication;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item;
import net.firedevops.firemud.common.gamedesign.CommandSource;

/** Branding selected source only; no total inventory or template completeness claim. */
public record BrandingSourceSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<Item> items) {
  public BrandingSourceSnapshot {
    var value =
        new net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot(
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

  public Map<String, String> roleDeclarations() {
    return shared().roleDeclarations();
  }

  public static BrandingSourceSnapshot fromStored(String json) {
    return fromShared(
        net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.fromStored(json));
  }

  public net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot shared() {
    return new net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot(
        binding, sourceEpoch, inheritedCommitId, genesisReceiptId, items);
  }

  public static BrandingSourceSnapshot fromShared(
      net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot value) {
    return new BrandingSourceSnapshot(
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
        throw new IllegalArgumentException("Exact branding source creation evidence required");
    }

    public Map<String, String> roleDeclarations() {
      return BrandingSource.emptyRoleDeclarations();
    }
  }

  public record Capture(GameDesignPublicationOperation operation, BrandingSourceSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(snapshot);
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding()))
        throw new IllegalArgumentException("Branding asset capture differs from selected commit");
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, "game-design-branding-asset-source-capture/v1");
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
