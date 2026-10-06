package net.firedevops.firemud.gamelogic.authoring;

import static net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact Game Logic owner input for the only currently supported rule-manifest application.
 *
 * <p>The empty arrays are an explicit versioned owner revision, not an inference from missing rows.
 * The complete cross-owner binding and its Game Design source proof remain unchanged and are
 * retained as provenance; neither is mixed into the semantic content digest.
 */
public record GameLogicRuleInputManifest(
    DraftCommitBinding binding,
    UUID revisionId,
    String sourceProofJson,
    String ownerIntentJson,
    String manifestJson,
    int digestSchemaVersion,
    String contentDigest,
    int abilitySchemaVersion,
    String abilitySchemaJson,
    String abilitySchemaDigest,
    String ownerResultJson,
    String ownerResultDigest) {
  public static final String AGGREGATE_TYPE = "VERSION_RULE_MANIFEST";
  public static final String SCOPE_TYPE = "VERSION";
  public static final String EMPTY_INTENT_KIND = "EMPTY_RULE_INPUT_MANIFEST/v1";
  public static final int DIGEST_SCHEMA_VERSION = 1;
  public static final int ABILITY_SCHEMA_VERSION = 1;
  public static final long INITIAL_EPOCH = 0L;
  public static final long APPLIED_EPOCH = 1L;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ABILITY_DIGEST_DOMAIN = "firemud.game-logic.ability-schema/v1";

  public GameLogicRuleInputManifest {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(revisionId, "revisionId");
    Objects.requireNonNull(sourceProofJson, "sourceProofJson");
    Objects.requireNonNull(ownerIntentJson, "ownerIntentJson");
    Objects.requireNonNull(manifestJson, "manifestJson");
    Objects.requireNonNull(contentDigest, "contentDigest");
    Objects.requireNonNull(abilitySchemaJson, "abilitySchemaJson");
    Objects.requireNonNull(abilitySchemaDigest, "abilitySchemaDigest");
    Objects.requireNonNull(ownerResultJson, "ownerResultJson");
    Objects.requireNonNull(ownerResultDigest, "ownerResultDigest");
  }

  /**
   * Validates the complete Game Design binding and derives the closed empty owner manifest. Future
   * non-empty or configured rule/ability input remains explicitly unsupported.
   */
  public static GameLogicRuleInputManifest fromExplicitEmptyIntent(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    if (!binding.requiredOwners().equals(Arrays.asList(Owner.values()))) {
      throw new InvalidRuleManifestIntentException(
          "A full Draft binding must retain all five required publication owners");
    }

    TargetProof target = binding.target();
    String versionId = target.canonicalVersionId().toString();
    List<AffectedUnit> ownerUnits = binding.affectedUnits(Owner.GAME_LOGIC);
    if (ownerUnits.size() != 1) {
      throw new InvalidRuleManifestIntentException(
          "The Game Logic owner must declare exactly one VERSION_RULE_MANIFEST unit");
    }
    AffectedUnit unit = ownerUnits.getFirst();
    if (!AGGREGATE_TYPE.equals(unit.aggregateType())
        || !versionId.equals(unit.aggregateId())
        || !SCOPE_TYPE.equals(unit.scopeType())
        || !versionId.equals(unit.scopeId())
        || !Long.toString(INITIAL_EPOCH).equals(unit.expectedEpoch())) {
      throw new InvalidRuleManifestIntentException(
          "The Game Logic owner unit must bind this canonical Version at initial epoch zero");
    }

    List<RevisionPayload> ownerRevisions =
        binding.revisions().stream()
            .filter(revision -> revision.owner() == Owner.GAME_LOGIC)
            .toList();
    if (ownerRevisions.size() != 1) {
      throw new InvalidRuleManifestIntentException(
          "The Game Logic owner must declare exactly one explicit empty-manifest revision");
    }
    RevisionPayload revision = ownerRevisions.getFirst();
    String ownerIntentJson = emptyIntentJson();
    if (!ownerIntentJson.equals(revision.payload())) {
      throw new InvalidRuleManifestIntentException(
          "Game Logic currently accepts only an explicit EMPTY_RULE_INPUT_MANIFEST/v1 intent");
    }

    String manifestJson = canonicalJson(emptyManifestObject());
    String contentPreimageJson = canonicalJson(contentDigestObject());
    String abilitySchemaJson = canonicalJson(abilitySchemaDigestObject());
    String contentDigest = sha256Hex(contentPreimageJson.getBytes(StandardCharsets.UTF_8));
    String abilitySchemaDigest = sha256Hex(abilitySchemaJson.getBytes(StandardCharsets.UTF_8));
    String sourceProofJson = canonicalJson(sourceProofObject(target));
    String ownerResultJson =
        canonicalJson(
            ownerResultObject(binding, revision.revisionId(), contentDigest, abilitySchemaDigest));

    return new GameLogicRuleInputManifest(
        binding,
        revision.revisionId(),
        sourceProofJson,
        ownerIntentJson,
        manifestJson,
        DIGEST_SCHEMA_VERSION,
        contentDigest,
        ABILITY_SCHEMA_VERSION,
        abilitySchemaJson,
        abilitySchemaDigest,
        ownerResultJson,
        sha256Prefixed(ownerResultJson.getBytes(StandardCharsets.UTF_8)));
  }

  /** Canonical owner payload that must be carried by one Game Logic revision in the binding. */
  public static String emptyIntentJson() {
    Map<String, Object> intent = new LinkedHashMap<>();
    intent.put("intentKind", EMPTY_INTENT_KIND);
    intent.put("manifest", emptyManifestObject());
    return canonicalJson(intent);
  }

  public UUID tenantId() {
    return binding.target().canonicalTenantId();
  }

  public UUID versionId() {
    return binding.target().canonicalVersionId();
  }

  public UUID requestId() {
    return binding.requestId();
  }

  public UUID commitId() {
    return binding.commitId();
  }

  public String baseCommitId() {
    return binding.baseCommitId();
  }

  private static Map<String, Object> emptyManifestObject() {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("abilitySchemas", List.of());
    manifest.put("ruleInputs", List.of());
    manifest.put("schemaVersion", 1);
    return manifest;
  }

  private static Map<String, Object> contentDigestObject() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("abilitySchemas", List.of());
    input.put("digestSchemaVersion", DIGEST_SCHEMA_VERSION);
    input.put("ruleInputs", List.of());
    return input;
  }

  private static Map<String, Object> abilitySchemaDigestObject() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("abilitySchemaDigestDomain", ABILITY_DIGEST_DOMAIN);
    input.put("abilitySchemaVersion", ABILITY_SCHEMA_VERSION);
    input.put("abilitySchemas", List.of());
    return input;
  }

  private static Map<String, Object> sourceProofObject(TargetProof target) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("canonicalTenantId", target.canonicalTenantId().toString());
    source.put("canonicalVersionId", target.canonicalVersionId().toString());
    source.put("gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()));
    source.put("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey());
    source.put("sourceGameRowId", Long.toString(target.sourceGameRowId()));
    source.put("sourceGameTenantKey", target.sourceGameTenantKey());
    source.put("sourceProvenanceKind", target.sourceProvenanceKind());
    return source;
  }

  private static Map<String, Object> ownerResultObject(
      DraftCommitBinding binding,
      UUID revisionId,
      String contentDigest,
      String abilitySchemaDigest) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("abilitySchemaDigest", abilitySchemaDigest);
    result.put("appliedCommitId", binding.commitId().toString());
    result.put("contentDigest", contentDigest);
    result.put("digestSchemaVersion", DIGEST_SCHEMA_VERSION);
    result.put("owner", Owner.GAME_LOGIC.name());
    result.put("ownerAggregateEpochAfter", Long.toString(APPLIED_EPOCH));
    result.put("ownerScopeEpochAfter", Long.toString(APPLIED_EPOCH));
    result.put("revisionId", revisionId.toString());
    result.put("status", "APPLIED");
    return result;
  }

  private static String canonicalJson(Object value) {
    try {
      String json = JSON.writeValueAsString(value);
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Game Logic manifest JSON could not be canonicalized", exception);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String sha256Prefixed(byte[] bytes) {
    return "sha256:" + sha256Hex(bytes);
  }

  public static final class InvalidRuleManifestIntentException extends IllegalArgumentException {
    public InvalidRuleManifestIntentException(String message) {
      super(message);
    }
  }
}
