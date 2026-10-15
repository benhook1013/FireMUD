package net.firedevops.firemud.gamedesign.publication;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;

/** Complete effective command set captured at one actual synchronized Draft commit. */
public record CommandSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    String genesisReceiptDigest,
    List<CommandSource.Definition> definitions) {
  public CommandSnapshot {
    var value =
        new net.firedevops.firemud.common.gamedesign.CommandSnapshot(
            binding, sourceEpoch, inheritedCommitId, genesisReceiptDigest, definitions);
    definitions = value.definitions();
  }

  @Override
  public List<CommandSource.Definition> definitions() {
    return List.copyOf(definitions);
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

  public static CommandSnapshot fromStored(String json) {
    return fromShared(net.firedevops.firemud.common.gamedesign.CommandSnapshot.fromStored(json));
  }

  public net.firedevops.firemud.common.gamedesign.CommandSnapshot shared() {
    return new net.firedevops.firemud.common.gamedesign.CommandSnapshot(
        binding, sourceEpoch, inheritedCommitId, genesisReceiptDigest, definitions);
  }

  public static CommandSnapshot fromShared(
      net.firedevops.firemud.common.gamedesign.CommandSnapshot value) {
    return new CommandSnapshot(
        value.binding(),
        value.sourceEpoch(),
        value.inheritedCommitId(),
        value.genesisReceiptDigest(),
        value.definitions());
  }

  /** Frozen source paired with the actual operation bytes and selected synchronized snapshot. */
  public record Capture(GameDesignPublicationOperation operation, CommandSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(snapshot, "snapshot");
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding())) {
        throw new IllegalArgumentException(
            "Frozen command source differs from the exact selected synchronized commit");
      }
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          out, "game-design-command-source-capture/v1");
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
