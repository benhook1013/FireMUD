package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Actual template wiring history only; no Game Logic receipt or total-release completeness. */
public record TemplateConfigSourceSnapshot(
    DraftCommitBinding binding,
    String sourceEpoch,
    UUID inheritedCommitId,
    UUID genesisReceiptId,
    List<TemplateConfigSource.Entry> entries,
    List<TemplateConfigOwnerSourceInventoryDeclaration> ownerSourceInventoryDeclarations) {
  public static final String SCHEMA =
      net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot.SCHEMA;
  public static final String SCHEMA_V2 =
      net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot.SCHEMA_V2;

  public TemplateConfigSourceSnapshot(
      DraftCommitBinding binding,
      String sourceEpoch,
      UUID inheritedCommitId,
      UUID genesisReceiptId,
      List<TemplateConfigSource.Entry> entries) {
    this(binding, sourceEpoch, inheritedCommitId, genesisReceiptId, entries, List.of());
  }

  public TemplateConfigSourceSnapshot {
    entries = List.copyOf(entries);
    ownerSourceInventoryDeclarations = List.copyOf(ownerSourceInventoryDeclarations);
    new net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot(
        binding,
        sourceEpoch,
        inheritedCommitId,
        genesisReceiptId,
        entries.stream().map(TemplateConfigSource.Entry::shared).toList(),
        ownerSourceInventoryDeclarations.stream()
            .map(TemplateConfigOwnerSourceInventoryDeclaration::shared)
            .toList());
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

  public static TemplateConfigSourceSnapshot fromStored(String json) {
    return fromShared(
        net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot.fromStored(json));
  }

  net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot shared() {
    return new net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot(
        binding,
        sourceEpoch,
        inheritedCommitId,
        genesisReceiptId,
        entries.stream().map(TemplateConfigSource.Entry::shared).toList(),
        ownerSourceInventoryDeclarations.stream()
            .map(TemplateConfigOwnerSourceInventoryDeclaration::shared)
            .toList());
  }

  static TemplateConfigSourceSnapshot fromShared(
      net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot value) {
    return new TemplateConfigSourceSnapshot(
        value.binding(),
        value.sourceEpoch(),
        value.inheritedCommitId(),
        value.genesisReceiptId(),
        value.entries().stream().map(TemplateConfigSource.Entry::fromShared).toList(),
        value.ownerSourceInventoryDeclarations().stream()
            .map(TemplateConfigOwnerSourceInventoryDeclaration::fromShared)
            .toList());
  }

  public record Genesis(TargetProof target, UUID receiptId, String creationTransactionId) {
    public Genesis {
      Objects.requireNonNull(target);
      DraftAuthorizationFenceBinding.requireUuid(receiptId);
      if (creationTransactionId == null || !creationTransactionId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException(
            "Actual fresh template source creation witness required");
    }
  }

  public record Application(
      DraftCommitBinding binding, String expectedEpoch, TemplateConfigSourceSnapshot snapshot) {
    public Application {
      Objects.requireNonNull(binding);
      if (expectedEpoch == null
          || !expectedEpoch.matches("0|[1-9][0-9]*")
          || !binding.equals(snapshot.binding())
          || TemplateConfigSource.mutations(binding).isEmpty()
          || !new BigInteger(expectedEpoch)
              .add(BigInteger.ONE)
              .toString()
              .equals(snapshot.sourceEpoch()))
        throw new IllegalArgumentException(
            "Exact template source application must advance its epoch");
    }

    public AppliedEpoch appliedEpoch() {
      return new AppliedEpoch(
          TemplateConfigSource.SCOPE,
          binding.target().canonicalVersionId().toString(),
          TemplateConfigSource.SCOPE,
          TemplateConfigSource.SCOPE_ID,
          expectedEpoch,
          snapshot.sourceEpoch());
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(
          out, "game-design-template-config-source-application/v1");
      DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }
  }

  public record Capture(
      GameDesignPublicationOperation operation, TemplateConfigSourceSnapshot snapshot) {
    public Capture {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(snapshot);
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding()))
        throw new IllegalArgumentException(
            "Template source differs from exact publication selection");
    }

    public byte[] canonicalBytes() {
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "game-design-template-config-source-capture/v1");
      DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }

    public String digest() {
      return CommandSource.sha256(canonicalBytes());
    }
  }
}
