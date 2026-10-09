package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;

/** Closed exact Game Design source inventory; it does not export or attest release objects. */
public final class SelectedDraftAssetInventory {
  public static final String SCHEMA = "game-design-selected-draft-asset-inventory/v1";

  private final PublicationDigestRequestBinding request;
  private final GameDesignPublicationOperation operation;
  private final GameDesignSourceRepository.Genesis sourceGenesis;
  private final GameDesignSourceRepository.Capture sourceCapture;
  private final GameDesignSourceRepository.SynchronizedSources selectedSources;
  private final SelectedDraftGameLogicReceipt gameLogicReceipt;
  private final Map<String, String> sourceFamilyDeclarations;
  private final List<Asset> assets;
  private final byte[] canonicalBytes;
  private final String digest;

  SelectedDraftAssetInventory(
      PublicationDigestRequestBinding request,
      GameDesignPublicationOperation operation,
      GameDesignSourceRepository.Genesis sourceGenesis,
      GameDesignSourceRepository.Capture sourceCapture,
      GameDesignSourceRepository.SynchronizedSources selectedSources,
      SelectedDraftGameLogicReceipt gameLogicReceipt,
      List<Asset> assets) {
    this.request = Objects.requireNonNull(request, "request");
    this.operation = Objects.requireNonNull(operation, "operation");
    this.sourceGenesis = Objects.requireNonNull(sourceGenesis, "sourceGenesis");
    this.sourceCapture = Objects.requireNonNull(sourceCapture, "sourceCapture");
    this.selectedSources = Objects.requireNonNull(selectedSources, "selectedSources");
    this.gameLogicReceipt = Objects.requireNonNull(gameLogicReceipt, "gameLogicReceipt");
    this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));

    var selected = operation.account().input().selection().selectedCommit();
    if (request.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION
        || !request.derivedWorkflowIdentity().equals(operation.workflowId())
        || !selected.target().equals(sourceGenesis.policy().target())
        || !selected.target().equals(sourceGenesis.command().target())
        || !selected.target().equals(sourceGenesis.asset().target())
        || !selected.target().equals(sourceGenesis.gameplay().target())
        || !selected.target().equals(sourceGenesis.branding().target())
        || !selected.target().equals(sourceGenesis.templateConfig().target())
        || !selected.equals(selectedSources.command().binding())
        || !selected.equals(sourceCapture.command().snapshot().binding())
        || !Arrays.equals(
            operation.canonicalBytes(), sourceCapture.command().operation().canonicalBytes())
        || !selected.equals(gameLogicReceipt.selection().selectedCommit())) {
      throw new IllegalArgumentException(
          "Inventory evidence differs from exact selected operation");
    }

    var branding = selectedSources.branding().orElseThrow();
    var template = selectedSources.templateConfig().orElseThrow();
    var declarations = new TreeMap<String, String>();
    declarations.put("ORDINARY", state(selectedSources.asset().items().isEmpty()));
    branding
        .roleDeclarations()
        .forEach((role, value) -> declarations.put("BRANDING:" + role, value));
    declarations.put("GAMEPLAY_RULE", state(selectedSources.gameplay().entries().isEmpty()));
    declarations.put("TEMPLATE_CONFIG", state(template.entries().isEmpty()));
    sourceFamilyDeclarations = Map.copyOf(declarations);

    var ordered =
        this.assets.stream()
            .sorted(
                Comparator.comparing(
                        Asset::usageKey,
                        (String left, String right) ->
                            Arrays.compareUnsigned(
                                left.getBytes(StandardCharsets.UTF_8),
                                right.getBytes(StandardCharsets.UTF_8)))
                    .thenComparing(asset -> asset.family().name()))
            .toList();
    if (!this.assets.equals(ordered)) {
      throw new IllegalArgumentException("Selected asset entries must be in canonical key order");
    }
    var keys = new java.util.HashSet<String>();
    for (Asset asset : this.assets) {
      if (!selected.target().equals(asset.sourceBinding().target())
          || !keys.add(asset.usageKey())) {
        throw new IllegalArgumentException(
            "Selected asset provenance or usage key collides across source families");
      }
    }
    canonicalBytes = canonicalJson().getBytes(StandardCharsets.UTF_8);
    digest = CommandSource.sha256(canonicalBytes);
  }

  private static String state(boolean empty) {
    return empty ? "EMPTY" : "PRESENT";
  }

  public PublicationDigestRequestBinding request() {
    return request;
  }

  public GameDesignPublicationOperation operation() {
    return operation;
  }

  public DraftCommitBinding selectedCommit() {
    return operation.account().input().selection().selectedCommit();
  }

  public GameDesignSourceRepository.Genesis sourceGenesis() {
    return sourceGenesis;
  }

  /** Retains the complete six-family frozen capture, including non-asset source families. */
  public GameDesignSourceRepository.Capture sourceCapture() {
    return sourceCapture;
  }

  /** Retains the complete selected synchronized snapshots; World artifacts remain in operation. */
  public GameDesignSourceRepository.SynchronizedSources selectedSources() {
    return selectedSources;
  }

  public SelectedDraftGameLogicReceipt gameLogicReceipt() {
    return gameLogicReceipt;
  }

  /** EMPTY values are derived only from exact captured and selected owner snapshots. */
  public Map<String, String> sourceFamilyDeclarations() {
    return sourceFamilyDeclarations;
  }

  public List<Asset> assets() {
    return assets;
  }

  public String canonicalJson() {
    var capture =
        Map.of(
            "command", encoded(sourceCapture.command().canonicalBytes()),
            "policy", encoded(sourceCapture.policy().canonicalBytes()),
            "ordinary", encoded(sourceCapture.asset().canonicalBytes()),
            "branding", encoded(sourceCapture.branding().canonicalBytes()),
            "templateConfig", encoded(sourceCapture.templateConfig().canonicalBytes()));
    var snapshots =
        Map.of(
            "command", selectedSources.command().canonicalJson(),
            "policy", selectedSources.policy().canonicalJson(),
            "ordinary", selectedSources.asset().canonicalJson(),
            "gameplay", selectedSources.gameplay().canonicalJson(),
            "branding", selectedSources.branding().orElseThrow().canonicalJson(),
            "templateConfig", selectedSources.templateConfig().orElseThrow().canonicalJson());
    var receipt =
        Map.of(
            "selection", encoded(gameLogicReceipt.selection().canonicalBytes()),
            "authorization", encoded(gameLogicReceipt.authorization().canonicalBytes()),
            "terminal", encoded(gameLogicReceipt.receipt().canonicalBytes()));
    return CommandSource.canonical(
        Map.ofEntries(
            Map.entry("schema", SCHEMA),
            Map.entry("requestPreimageBase64", encoded(request.canonicalPreimage())),
            Map.entry("requestDigest", request.requestDigest()),
            Map.entry("operationBase64", encoded(operation.canonicalBytes())),
            Map.entry("selectedCommitJson", selectedCommit().canonicalJson()),
            Map.entry("selectedCommitDigest", selectedCommit().digest()),
            Map.entry("sourceGenesis", genesisObject()),
            Map.entry("sourceCapture", capture),
            Map.entry("selectedSnapshots", snapshots),
            Map.entry("gameLogicReceipt", receipt),
            Map.entry("sourceFamilyDeclarations", new TreeMap<>(sourceFamilyDeclarations)),
            Map.entry("assets", assets.stream().map(Asset::object).toList())));
  }

  private Map<String, Object> genesisObject() {
    var target = selectedCommit().target();
    return Map.ofEntries(
        Map.entry("canonicalTenantId", target.canonicalTenantId().toString()),
        Map.entry("canonicalVersionId", target.canonicalVersionId().toString()),
        Map.entry("gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId())),
        Map.entry("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey()),
        Map.entry("sourceGameRowId", Long.toString(target.sourceGameRowId())),
        Map.entry("sourceGameTenantKey", target.sourceGameTenantKey()),
        Map.entry("sourceProvenanceKind", target.sourceProvenanceKind()),
        Map.entry(
            "command",
            genesisRecord(
                sourceGenesis.command().receiptId(),
                sourceGenesis.command().creationTransactionId())),
        Map.entry(
            "policy",
            genesisRecord(
                sourceGenesis.policy().receiptId(),
                sourceGenesis.policy().creationTransactionId())),
        Map.entry(
            "ordinary",
            genesisRecord(
                sourceGenesis.asset().receiptId(), sourceGenesis.asset().creationTransactionId())),
        Map.entry(
            "gameplay",
            Map.of(
                "receiptId", sourceGenesis.gameplay().receiptId().toString(),
                "creationTransactionId", sourceGenesis.gameplay().creationTransactionId(),
                "emptyInventoryJson", sourceGenesis.gameplay().inventory().canonicalJson())),
        Map.entry(
            "branding",
            genesisRecord(
                sourceGenesis.branding().receiptId(),
                sourceGenesis.branding().creationTransactionId())),
        Map.entry(
            "templateConfig",
            genesisRecord(
                sourceGenesis.templateConfig().receiptId(),
                sourceGenesis.templateConfig().creationTransactionId())));
  }

  private static Map<String, String> genesisRecord(UUID receiptId, String creationTransactionId) {
    return Map.of(
        "receiptId", receiptId.toString(), "creationTransactionId", creationTransactionId);
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  private static String encoded(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  public enum Family {
    ORDINARY,
    BRANDING
  }

  /** Exact original source provenance and actual tenant-qualified owner bytes. */
  public record Asset(
      Family family,
      String role,
      String usageKey,
      String assetRowId,
      String requiredness,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId,
      String contentType,
      String contentDigest,
      byte[] contentBytes) {
    public Asset {
      Objects.requireNonNull(family, "family");
      Objects.requireNonNull(sourceBinding, "sourceBinding");
      DraftAuthorizationFenceBinding.requireUuid(revisionId);
      if (role == null
          || role.isBlank()
          || family == Family.ORDINARY && !AssetSource.ROLE.equals(role)
          || family == Family.BRANDING
              && Arrays.stream(BrandingSource.Role.values())
                  .noneMatch(value -> value.name().equals(role))
          || usageKey == null
          || usageKey.isBlank()
          || "manifest.json".equals(usageKey)
          || assetRowId == null
          || !assetRowId.matches("[1-9][0-9]*")
          || !"REQUIRED".equals(requiredness)
          || revisionOrder == null
          || !revisionOrder.matches("0|[1-9][0-9]*")
          || contentType == null
          || contentType.isBlank()
          || contentDigest == null
          || !contentDigest.matches("sha256:[0-9a-f]{64}")
          || contentBytes == null
          || !CommandSource.sha256(contentBytes).equals(contentDigest)) {
        throw new IllegalArgumentException(
            "Exact required source asset bytes and metadata required");
      }
      contentBytes = contentBytes.clone();
    }

    @Override
    public byte[] contentBytes() {
      return contentBytes.clone();
    }

    private Map<String, Object> object() {
      return Map.ofEntries(
          Map.entry("family", family.name()),
          Map.entry("role", role),
          Map.entry("usageKey", usageKey),
          Map.entry("assetRowId", assetRowId),
          Map.entry("requiredness", requiredness),
          Map.entry("sourceBindingJson", sourceBinding.canonicalJson()),
          Map.entry("sourceBindingDigest", sourceBinding.digest()),
          Map.entry("revisionOrder", revisionOrder),
          Map.entry("revisionId", revisionId.toString()),
          Map.entry("contentType", contentType),
          Map.entry("contentDigest", contentDigest),
          Map.entry("byteSize", Long.toString(contentBytes.length)),
          Map.entry("contentBase64", encoded(contentBytes)));
    }
  }
}
