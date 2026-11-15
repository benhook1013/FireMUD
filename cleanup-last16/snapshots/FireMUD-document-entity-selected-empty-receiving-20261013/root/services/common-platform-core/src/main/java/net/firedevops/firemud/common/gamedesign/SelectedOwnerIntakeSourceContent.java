package net.firedevops.firemud.common.gamedesign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/**
 * Exact six-family Game Design source content bound to one selected-owner preliminary scope.
 *
 * <p>Decoding checks framing, hashes, canonical JSON bindings and each existing typed source
 * snapshot. It does not authenticate disclosure, grant finalization or retention authority, prove
 * an owner inventory complete or empty, or create an owner receipt. Template snapshot v1/v2 parsing
 * retains the stored grammar but does not prove a fresh authored inventory. Typed snapshot
 * integrity is not independent proof that all owner references are resolved.
 */
public final class SelectedOwnerIntakeSourceContent {
  public static final String DOMAIN = "game-design-selected-owner-intake-source/v1";
  public static final int MAX_BYTES = 8 * 1024 * 1024;

  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final List<String> FAMILIES =
      List.of("COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG");

  private final SelectedOwnerIntakeSourceReadScope scope;
  private final byte[] canonicalBytes;
  private final String digest;
  private final Map<String, byte[]> snapshots;
  private final CommandSnapshot commandSource;
  private final RealmPolicySnapshot realmPolicySource;
  private final AssetSnapshot assetSource;
  private final BrandingSourceSnapshot brandingSource;
  private final GameplayRuleSelectedSource gameplaySource;
  private final TemplateConfigSourceSnapshot templateConfigSource;

  private SelectedOwnerIntakeSourceContent(
      SelectedOwnerIntakeSourceReadScope scope,
      byte[] canonicalBytes,
      String digest,
      Map<String, byte[]> snapshots,
      CommandSnapshot commandSource,
      RealmPolicySnapshot realmPolicySource,
      AssetSnapshot assetSource,
      BrandingSourceSnapshot brandingSource,
      GameplayRuleSelectedSource gameplaySource,
      TemplateConfigSourceSnapshot templateConfigSource) {
    this.scope = scope;
    this.canonicalBytes = canonicalBytes.clone();
    this.digest = digest;
    var copiedSnapshots = new LinkedHashMap<String, byte[]>();
    for (String family : FAMILIES) copiedSnapshots.put(family, snapshots.get(family).clone());
    this.snapshots = Map.copyOf(copiedSnapshots);
    this.commandSource = commandSource;
    this.realmPolicySource = realmPolicySource;
    this.assetSource = assetSource;
    this.brandingSource = brandingSource;
    this.gameplaySource = gameplaySource;
    this.templateConfigSource = templateConfigSource;
  }

  /**
   * Decodes exact persisted content against the independently expected preliminary scope and
   * content digest. This value is integrity evidence only; it is not an authority grant.
   */
  public static SelectedOwnerIntakeSourceContent fromStored(
      byte[] bytes, SelectedOwnerIntakeSourceReadScope expectedScope, String expectedDigest) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Selected owner source content size is invalid");
    Objects.requireNonNull(expectedScope, "expectedScope");
    if (expectedDigest == null || !SHA256.matcher(expectedDigest).matches())
      throw new IllegalArgumentException("Expected selected owner source digest is invalid");

    // Enforce the envelope budget before copying, hashing or parsing any caller-controlled bytes.
    byte[] stored = bytes.clone();
    String actualDigest = DraftAuthorizationFenceBinding.digest(stored);
    if (!actualDigest.equals(expectedDigest))
      throw new IllegalArgumentException("Selected owner source content digest differs");

    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(DOMAIN);
    byte[] scopeBytes = reader.bytes();
    String scopeDigest = reader.text();
    if (!DraftAuthorizationFenceBinding.digest(scopeBytes).equals(scopeDigest))
      throw new IllegalArgumentException("Selected owner source scope digest differs");
    SelectedOwnerIntakeSourceReadScope decodedScope =
        SelectedOwnerIntakeSourceReadScope.fromStored(scopeBytes);
    if (!Arrays.equals(scopeBytes, decodedScope.canonicalBytes())
        || !decodedScope.equals(expectedScope)
        || decodedScope.owner() != expectedScope.owner()
        || !decodedScope.selected().equals(expectedScope.selected())
        || !decodedScope.digest().equals(scopeDigest))
      throw new IllegalArgumentException("Selected owner source scope differs from expectation");

    var snapshots = new LinkedHashMap<String, byte[]>();
    for (String family : FAMILIES) {
      reader.expect(family);
      byte[] snapshot = reader.bytes();
      if (snapshot.length == 0)
        throw new IllegalArgumentException("Selected " + family + " snapshot size is invalid");
      String familyDigest = reader.text();
      if (!DraftAuthorizationFenceBinding.digest(snapshot).equals(familyDigest))
        throw new IllegalArgumentException("Selected " + family + " snapshot digest differs");
      requireSelectedBinding(family, snapshot, decodedScope.selected());
      snapshots.put(family, snapshot);
    }
    reader.requireEnd();

    CommandSnapshot commandSource =
        CommandSnapshot.fromStored(utf8(snapshots.get("COMMAND"), "COMMAND"));
    RealmPolicySnapshot realmPolicySource =
        RealmPolicySnapshot.fromStored(utf8(snapshots.get("REALM_POLICY"), "REALM_POLICY"));
    AssetSnapshot assetSource = AssetSnapshot.fromStored(utf8(snapshots.get("ASSET"), "ASSET"));
    BrandingSourceSnapshot brandingSource =
        BrandingSourceSnapshot.fromStored(utf8(snapshots.get("BRANDING"), "BRANDING"));
    requireClosedGameDesignRevisions(decodedScope.selected());

    GameplayRuleSelectedSource gameplaySource =
        new GameplayRuleSelectedSource(utf8(snapshots.get("GAMEPLAY_RULE"), "GAMEPLAY_RULE"));
    if (!decodedScope.selected().equals(gameplaySource.binding()))
      throw new IllegalArgumentException("Selected gameplay rule binding differs");

    TemplateConfigSourceSnapshot templateConfigSource =
        TemplateConfigSourceSnapshot.fromStored(
            utf8(snapshots.get("TEMPLATE_CONFIG"), "TEMPLATE_CONFIG"));
    if (!decodedScope.selected().equals(templateConfigSource.binding()))
      throw new IllegalArgumentException("Selected template config binding differs");

    return new SelectedOwnerIntakeSourceContent(
        decodedScope,
        stored,
        actualDigest,
        snapshots,
        commandSource,
        realmPolicySource,
        assetSource,
        brandingSource,
        gameplaySource,
        templateConfigSource);
  }

  public SelectedOwnerIntakeSourceReadScope scope() {
    return scope;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  /** Returns the original canonical snapshot bytes for one of the six closed family names. */
  public byte[] snapshotBytes(String closedFamily) {
    if (closedFamily == null)
      throw new IllegalArgumentException("Unsupported selected owner source family");
    byte[] snapshot = snapshots.get(closedFamily);
    if (snapshot == null)
      throw new IllegalArgumentException("Unsupported selected owner source family");
    return snapshot.clone();
  }

  public GameplayRuleSelectedSource gameplaySource() {
    return gameplaySource;
  }

  public CommandSnapshot commandSource() {
    return commandSource;
  }

  public RealmPolicySnapshot realmPolicySource() {
    return realmPolicySource;
  }

  public AssetSnapshot assetSource() {
    return assetSource;
  }

  public BrandingSourceSnapshot brandingSource() {
    return brandingSource;
  }

  public TemplateConfigSourceSnapshot templateConfigSource() {
    return templateConfigSource;
  }

  private static void requireClosedGameDesignRevisions(DraftCommitBinding binding) {
    // Each actual owner grammar validates its family and rejects unrecognized Game Design kinds.
    // Other owners' payloads are not interpreted as Game Design sources or silently reconstructed.
    CommandSource.mutations(binding);
    AssetSource.mutations(binding);
    BrandingSource.mutations(binding);
    GameplayRuleSource.mutations(binding);
    TemplateConfigSourceValues.mutations(binding);
    for (var revision : binding.revisions()) {
      if (revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE
          && RealmPolicySource.isPolicyRevision(revision)) {
        RealmPolicySource.revision(binding, revision);
      }
    }
  }

  private static void requireSelectedBinding(
      String family, byte[] snapshot, DraftCommitBinding selected) {
    String json = utf8(snapshot, family);
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      if (!Arrays.equals(snapshot, canonical))
        throw new IllegalArgumentException(
            "Selected " + family + " snapshot is not canonical JSON");
      var root = GameplayRuleManifest.tree(json);
      if (!root.isObject())
        throw new IllegalArgumentException(
            "Selected " + family + " snapshot root must be an object");
      var bindingJson = root.get("bindingJson");
      var bindingDigest = root.get("bindingDigest");
      if (bindingJson == null
          || !bindingJson.isTextual()
          || bindingDigest == null
          || !bindingDigest.isTextual())
        throw new IllegalArgumentException("Selected " + family + " binding fields must be text");
      DraftCommitBinding decoded =
          DraftCommitBinding.fromStored(bindingJson.textValue(), bindingDigest.textValue());
      if (!selected.equals(decoded))
        throw new IllegalArgumentException("Selected " + family + " binding differs");
    } catch (IOException failure) {
      throw new IllegalArgumentException(
          "Selected " + family + " snapshot JSON is invalid", failure);
    }
  }

  private static String utf8(byte[] bytes, String family) {
    String value = new String(bytes, StandardCharsets.UTF_8);
    if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8)))
      throw new IllegalArgumentException("Selected " + family + " snapshot is not exact UTF-8");
    return value;
  }
}
