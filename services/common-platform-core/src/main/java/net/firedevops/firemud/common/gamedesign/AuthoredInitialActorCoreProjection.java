package net.firedevops.firemud.common.gamedesign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AuthoredInitialActorCore;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed, public-field projection of one retained authored initial actor core source binding.
 *
 * <p>This value carries source identity and content needed by a qualified owner read. It does not
 * carry the private publication operation, source capture, terminal, selected commit bytes, or
 * private target keys. Its digest is integrity metadata, not authentication of its producer.
 */
public final class AuthoredInitialActorCoreProjection {
  public static final String SCHEMA = "game-design-authored-initial-actor-core-projection/v1";
  public static final int MAX_BYTES = 1_048_576;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern REVISION_ORDER = Pattern.compile("0|[1-9][0-9]*");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final UUID bindingId;
  private final String bindingDigest;
  private final UUID canonicalTenantId;
  private final UUID canonicalVersionId;
  private final UUID worldSourceOperationId;
  private final String worldSourceEvidenceDigest;
  private final String worldSlug;
  private final String publicationWorkflowId;
  private final UUID selectedCommitId;
  private final String selectedCommitDigest;
  private final Policy policy;
  private final AuthoredCore core;
  private final Descriptor descriptor;
  private final RuleDefinition disposition;
  private final RuleDefinition safeFeedback;
  private final byte[] canonicalBytes;
  private final String digest;

  private AuthoredInitialActorCoreProjection(
      UUID bindingId,
      String bindingDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID worldSourceOperationId,
      String worldSourceEvidenceDigest,
      String worldSlug,
      String publicationWorkflowId,
      UUID selectedCommitId,
      String selectedCommitDigest,
      Policy policy,
      AuthoredCore core,
      Descriptor descriptor,
      RuleDefinition disposition,
      RuleDefinition safeFeedback) {
    this.bindingId = nonNil(bindingId, "bindingId");
    this.bindingDigest = requireDigest(bindingDigest, "bindingDigest");
    this.canonicalTenantId = nonNil(canonicalTenantId, "canonicalTenantId");
    this.canonicalVersionId = nonNil(canonicalVersionId, "canonicalVersionId");
    this.worldSourceOperationId = nonNil(worldSourceOperationId, "worldSourceOperationId");
    this.worldSourceEvidenceDigest =
        requireDigest(worldSourceEvidenceDigest, "worldSourceEvidenceDigest");
    if (!RealmEntryPolicy.isCanonicalSlug(worldSlug)) {
      throw invalid("Canonical worldSlug required");
    }
    this.worldSlug = worldSlug;
    this.publicationWorkflowId = boundedText(publicationWorkflowId, 256, "publicationWorkflowId");
    this.selectedCommitId = nonNil(selectedCommitId, "selectedCommitId");
    this.selectedCommitDigest = requireDigest(selectedCommitDigest, "selectedCommitDigest");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.core = Objects.requireNonNull(core, "core");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.disposition = Objects.requireNonNull(disposition, "disposition");
    this.safeFeedback = Objects.requireNonNull(safeFeedback, "safeFeedback");
    requireCrossLinks();
    this.canonicalBytes = canonicalJson().getBytes(StandardCharsets.UTF_8);
    if (canonicalBytes.length > MAX_BYTES) {
      throw invalid("Projection exceeds its 1 MiB bound");
    }
    this.digest = CommandSource.sha256(canonicalBytes);
  }

  /**
   * Builds the projection only from a fully validated private source binding. The owner supplies
   * its retained nonzero binding ID; this method does not allocate or authenticate that identity.
   */
  public static AuthoredInitialActorCoreProjection fromValidatedBinding(
      UUID bindingId, AuthoredInitialActorCoreSourceBinding binding) {
    Objects.requireNonNull(binding, "validated source binding is required");
    var selectedCommit = binding.selectedCommit();
    var target = selectedCommit.target();
    var policySource = binding.policySource();
    var policyJson = policySource.policy().canonicalJson();
    var descriptorValue = policySource.policy().creationDescriptor();
    return new AuthoredInitialActorCoreProjection(
        bindingId,
        binding.digest(),
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        binding.worldSourceOperationId(),
        binding.worldSourceEvidenceDigest(),
        binding.expectedWorldSlug(),
        binding.publicationWorkflowId(),
        selectedCommit.commitId(),
        selectedCommit.digest(),
        new Policy(
            policySource.commitId(),
            policySource.revisionId(),
            policySource.logicalRevisionId(),
            policyJson,
            CommandSource.sha256(policyJson.getBytes(StandardCharsets.UTF_8))),
        new AuthoredCore(binding.core().canonicalJson()),
        new Descriptor(descriptorValue.canonicalJson(), descriptorValue.digest()),
        ruleDefinition(binding.dispositionSource()),
        ruleDefinition(binding.safeFeedbackSource()));
  }

  /** Decodes a closed canonical projection and verifies its exact public-content digest. */
  public static AuthoredInitialActorCoreProjection fromStored(
      byte[] storedBytes, String expectedDigest) {
    if (storedBytes == null || storedBytes.length == 0 || storedBytes.length > MAX_BYTES) {
      throw invalid("Complete bounded projection bytes required");
    }
    if (expectedDigest == null
        || !SHA256.matcher(expectedDigest).matches()
        || !CommandSource.sha256(storedBytes).equals(expectedDigest)) {
      throw invalid("Projection digest differs");
    }

    final String storedJson = strictUtf8(storedBytes);
    final JsonNode root;
    try {
      root = JSON.readTree(storedJson);
    } catch (RuntimeException malformed) {
      throw invalid("Projection must be valid unambiguous JSON", malformed);
    }
    if (root == null || !root.isObject()) {
      throw invalid("Projection must be an object");
    }
    byte[] canonical = canonicalJsonBytes(storedJson);
    if (!Arrays.equals(canonical, storedBytes)) {
      throw invalid("Projection JSON is not RFC 8785 canonical");
    }
    fields(
        root,
        "schema",
        "bindingId",
        "bindingDigest",
        "canonicalTenantId",
        "canonicalVersionId",
        "worldSourceOperationId",
        "worldSourceEvidenceDigest",
        "worldSlug",
        "publicationWorkflowId",
        "selectedCommitId",
        "selectedCommitDigest",
        "policy",
        "core",
        "descriptor",
        "disposition",
        "safeFeedback");
    if (!SCHEMA.equals(text(root, "schema"))) {
      throw invalid("Unsupported projection schema");
    }
    var value =
        new AuthoredInitialActorCoreProjection(
            uuid(root, "bindingId"),
            text(root, "bindingDigest"),
            uuid(root, "canonicalTenantId"),
            uuid(root, "canonicalVersionId"),
            uuid(root, "worldSourceOperationId"),
            text(root, "worldSourceEvidenceDigest"),
            text(root, "worldSlug"),
            text(root, "publicationWorkflowId"),
            uuid(root, "selectedCommitId"),
            text(root, "selectedCommitDigest"),
            policy(root.get("policy")),
            core(root.get("core")),
            descriptor(root.get("descriptor")),
            rule(root.get("disposition")),
            rule(root.get("safeFeedback")));
    if (!Arrays.equals(value.canonicalBytes, storedBytes) || !value.digest.equals(expectedDigest)) {
      throw invalid("Projection changed during canonical reconstruction");
    }
    return value;
  }

  private void requireCrossLinks() {
    RealmEntryPolicy parsedPolicy = parsePolicy(policy.canonicalJson());
    if (policy.commitId().equals(NIL_UUID)
        || !worldSlug.equals(parsedPolicy.worldSlug())
        || parsedPolicy.schemaVersion()
            != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || parsedPolicy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || !core.displayName().equals(parsedPolicy.initialActorCore().displayName())
        || !core.dispositionKey().equals(parsedPolicy.initialActorCore().dispositionKey())
        || !core.canonicalJson().equals(parsedPolicy.initialActorCore().canonicalJson())
        || !descriptor.canonicalJson().equals(parsedPolicy.creationDescriptor().canonicalJson())
        || !policy
            .contentDigest()
            .equals(
                CommandSource.sha256(policy.canonicalJson().getBytes(StandardCharsets.UTF_8)))) {
      throw invalid("Projection source, policy, core, or descriptor bindings differ");
    }
    var dispositionValue = definition(Family.DISPOSITIONS, disposition);
    var feedbackValue = definition(Family.FEEDBACK, safeFeedback);
    if (!(dispositionValue instanceof GameplayRuleManifest.Disposition selectedDisposition)
        || !(feedbackValue instanceof GameplayRuleManifest.Feedback selectedFeedback)
        || !selectedDisposition.dispositionKey().equals(core.dispositionKey())
        || !selectedDisposition.safeFeedbackKey().equals(selectedFeedback.feedbackKey())) {
      throw invalid("Disposition and mandatory feedback source bindings differ");
    }
    var parsedDescriptor = PlayerCreationDescriptor.parse(descriptor.canonicalJson());
    if (!descriptor.digest().equals(parsedDescriptor.digest())) {
      throw invalid("Descriptor content digest differs");
    }
  }

  private String canonicalJson() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("bindingId", bindingId.toString());
    value.put("bindingDigest", bindingDigest);
    value.put("canonicalTenantId", canonicalTenantId.toString());
    value.put("canonicalVersionId", canonicalVersionId.toString());
    value.put("worldSourceOperationId", worldSourceOperationId.toString());
    value.put("worldSourceEvidenceDigest", worldSourceEvidenceDigest);
    value.put("worldSlug", worldSlug);
    value.put("publicationWorkflowId", publicationWorkflowId);
    value.put("selectedCommitId", selectedCommitId.toString());
    value.put("selectedCommitDigest", selectedCommitDigest);
    value.put("policy", policy.object());
    value.put("core", core.object());
    value.put("descriptor", descriptor.object());
    value.put("disposition", disposition.object());
    value.put("safeFeedback", safeFeedback.object());
    return CommandSource.canonical(value);
  }

  private static RuleDefinition ruleDefinition(GameplayRuleSource.Entry source) {
    String definitionJson = GameplayRuleManifest.canonical(source.definition());
    return new RuleDefinition(
        source.definition().key(),
        definitionJson,
        CommandSource.sha256(definitionJson.getBytes(StandardCharsets.UTF_8)),
        source.sourceBinding().commitId(),
        source.sourceBinding().digest(),
        source.revisionId(),
        source.revisionOrder());
  }

  private static Policy policy(JsonNode node) {
    fields(
        node,
        "commitId",
        "revisionId",
        "logicalRevisionId",
        "canonicalPolicyJson",
        "contentDigest");
    return new Policy(
        uuid(node, "commitId"),
        uuid(node, "revisionId"),
        text(node, "logicalRevisionId"),
        text(node, "canonicalPolicyJson"),
        text(node, "contentDigest"));
  }

  private static AuthoredCore core(JsonNode node) {
    fields(node, "canonicalJson");
    return new AuthoredCore(text(node, "canonicalJson"));
  }

  private static Descriptor descriptor(JsonNode node) {
    fields(node, "canonicalJson", "digest");
    return new Descriptor(text(node, "canonicalJson"), text(node, "digest"));
  }

  private static RuleDefinition rule(JsonNode node) {
    fields(
        node,
        "key",
        "definitionJson",
        "definitionDigest",
        "sourceCommitId",
        "sourceCommitDigest",
        "revisionId",
        "revisionOrder");
    return new RuleDefinition(
        text(node, "key"),
        text(node, "definitionJson"),
        text(node, "definitionDigest"),
        uuid(node, "sourceCommitId"),
        text(node, "sourceCommitDigest"),
        uuid(node, "revisionId"),
        text(node, "revisionOrder"));
  }

  private static GameplayRuleManifest.Definition definition(Family family, RuleDefinition rule) {
    JsonNode parsed;
    try {
      parsed = JSON.readTree(rule.definitionJson());
    } catch (RuntimeException malformed) {
      throw invalid("Rule definition JSON is invalid", malformed);
    }
    if (parsed == null || !parsed.isObject()) {
      throw invalid("Rule definition must be an object");
    }
    byte[] canonical = canonicalJsonBytes(rule.definitionJson());
    if (!Arrays.equals(canonical, rule.definitionJson().getBytes(StandardCharsets.UTF_8))
        || !GameplayRuleManifest.canonical(parsed).equals(rule.definitionJson())) {
      throw invalid("Rule definition is not canonical");
    }
    var value = GameplayRuleManifest.definition(family, rule.definitionJson());
    if (!value.key().equals(rule.key())
        || !rule.definitionDigest().equals(CommandSource.sha256(canonical))) {
      throw invalid("Rule definition digest or key differs");
    }
    return value;
  }

  private static RealmEntryPolicy parsePolicy(String json) {
    try {
      JsonNode parsed = JSON.readTree(json);
      if (parsed == null || !parsed.isObject()) {
        throw invalid("Policy must be an object");
      }
      if (!Arrays.equals(
          Rfc8785CanonicalJson.canonicalizeUtf8(json), json.getBytes(StandardCharsets.UTF_8))) {
        throw invalid("Policy JSON is not canonical");
      }
      return RealmEntryPolicy.parse(json, JSON);
    } catch (IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException invalid) {
        throw invalid;
      }
      throw invalid("Policy JSON is invalid", malformed);
    }
  }

  private static void fields(JsonNode node, String... names) {
    if (node == null || !node.isObject() || node.size() != names.length) {
      throw invalid("Closed projection fields required");
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw invalid("Missing projection field: " + name);
      }
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) {
      throw invalid("Text projection field required: " + name);
    }
    return value.textValue();
  }

  private static UUID uuid(JsonNode node, String name) {
    String value = text(node, name);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw invalid("Canonical nonzero UUID required: " + name);
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      if (malformed.getMessage() != null
          && malformed.getMessage().startsWith("Canonical nonzero UUID")) {
        throw malformed;
      }
      throw invalid("Canonical nonzero UUID required: " + name, malformed);
    }
  }

  private static UUID nonNil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw invalid("Canonical nonzero UUID required: " + name);
    }
    return value;
  }

  private static String requireDigest(String value, String name) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw invalid("Canonical SHA-256 digest required: " + name);
    }
    return value;
  }

  private static String boundedText(String value, int maxLength, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw invalid("Bounded nonempty text required: " + name);
    }
    return value;
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw invalid("Projection bytes are not valid UTF-8", malformed);
    }
  }

  private static byte[] canonicalJsonBytes(String json) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException malformed) {
      throw invalid("Projection content is not valid canonical JSON", malformed);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }

  public UUID bindingId() {
    return bindingId;
  }

  public String bindingDigest() {
    return bindingDigest;
  }

  public UUID canonicalTenantId() {
    return canonicalTenantId;
  }

  public UUID canonicalVersionId() {
    return canonicalVersionId;
  }

  public UUID worldSourceOperationId() {
    return worldSourceOperationId;
  }

  public String worldSourceEvidenceDigest() {
    return worldSourceEvidenceDigest;
  }

  public String worldSlug() {
    return worldSlug;
  }

  public String publicationWorkflowId() {
    return publicationWorkflowId;
  }

  public UUID selectedCommitId() {
    return selectedCommitId;
  }

  public String selectedCommitDigest() {
    return selectedCommitDigest;
  }

  public Policy policy() {
    return policy;
  }

  public AuthoredCore core() {
    return core;
  }

  public Descriptor descriptor() {
    return descriptor;
  }

  public RuleDefinition disposition() {
    return disposition;
  }

  public RuleDefinition safeFeedback() {
    return safeFeedback;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  public record Policy(
      UUID commitId,
      UUID revisionId,
      String logicalRevisionId,
      String canonicalJson,
      String contentDigest) {
    public Policy {
      nonNil(commitId, "policy commitId");
      nonNil(revisionId, "policy revisionId");
      logicalRevisionId = boundedText(logicalRevisionId, 128, "policy logicalRevisionId");
      Objects.requireNonNull(canonicalJson, "canonical policy JSON");
      requireDigest(contentDigest, "policy contentDigest");
    }

    private Map<String, Object> object() {
      return Map.of(
          "commitId", commitId.toString(),
          "revisionId", revisionId.toString(),
          "logicalRevisionId", logicalRevisionId,
          "canonicalPolicyJson", canonicalJson,
          "contentDigest", contentDigest);
    }
  }

  public record AuthoredCore(String canonicalJson) {
    public AuthoredCore {
      Objects.requireNonNull(canonicalJson, "canonical authored core JSON");
      parseCore(canonicalJson);
    }

    public String displayName() {
      return parseCore(canonicalJson).displayName();
    }

    public String dispositionKey() {
      return parseCore(canonicalJson).dispositionKey();
    }

    private Map<String, Object> object() {
      return Map.of("canonicalJson", canonicalJson);
    }
  }

  private static AuthoredInitialActorCore parseCore(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      fields(node, "displayName", "dispositionKey");
      String canonical = GameplayRuleManifest.canonical(node);
      if (!canonical.equals(json)) {
        throw invalid("Authored core JSON is not canonical");
      }
      return new AuthoredInitialActorCore(text(node, "displayName"), text(node, "dispositionKey"));
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException invalid) {
        throw invalid;
      }
      throw invalid("Authored core JSON is invalid", malformed);
    }
  }

  public record Descriptor(String canonicalJson, String digest) {
    public Descriptor {
      Objects.requireNonNull(canonicalJson, "canonical descriptor JSON");
      requireDigest(digest, "descriptor digest");
    }

    private Map<String, Object> object() {
      return Map.of("canonicalJson", canonicalJson, "digest", digest);
    }
  }

  public record RuleDefinition(
      String key,
      String definitionJson,
      String definitionDigest,
      UUID sourceCommitId,
      String sourceCommitDigest,
      UUID revisionId,
      String revisionOrder) {
    public RuleDefinition {
      boundedText(key, 128, "rule key");
      Objects.requireNonNull(definitionJson, "canonical definition JSON");
      requireDigest(definitionDigest, "definitionDigest");
      nonNil(sourceCommitId, "rule sourceCommitId");
      requireDigest(sourceCommitDigest, "rule sourceCommitDigest");
      nonNil(revisionId, "rule revisionId");
      if (revisionOrder == null || !REVISION_ORDER.matcher(revisionOrder).matches()) {
        throw invalid("Canonical rule revisionOrder required");
      }
    }

    private Map<String, Object> object() {
      return Map.of(
          "key", key,
          "definitionJson", definitionJson,
          "definitionDigest", definitionDigest,
          "sourceCommitId", sourceCommitId.toString(),
          "sourceCommitDigest", sourceCommitDigest,
          "revisionId", revisionId.toString(),
          "revisionOrder", revisionOrder);
    }
  }
}
