package net.firedevops.firemud.gamedesign.publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owner-local typed source; neither these bytes nor a caller's declaration grant authority. */
public final class RealmPolicySource {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String SCOPE = "REALM_ENTRY_POLICY_SET";
  public static final int MAX_POLICIES = 128;

  public static final class InvalidPublicationPolicySetException extends IllegalArgumentException {
    InvalidPublicationPolicySetException(String message) {
      super(message);
    }
  }

  private RealmPolicySource() {}

  /**
   * One owner-authored mutation; the published snapshot continues to retain only effective rows.
   */
  public sealed interface Mutation permits Policy, Deletion {
    UUID commitId();

    UUID revisionId();

    String logicalRevisionId();
  }

  /** Original authored UUID and logical identity survive every inherited snapshot. */
  public record Policy(
      UUID commitId, UUID revisionId, String logicalRevisionId, RealmEntryPolicy policy)
      implements Mutation {
    public Policy {
      nonNil(commitId);
      nonNil(revisionId);
      requireLogicalRevisionId(logicalRevisionId);
      policy = RealmEntryPolicy.parseCanonical(policy.canonicalJson(), JSON);
    }
  }

  /** Explicit deletion carries the exact authored identity and selector in the stored binding. */
  public record Deletion(
      UUID commitId, UUID revisionId, String logicalRevisionId, String worldSlug, String realmSlug)
      implements Mutation {
    public Deletion {
      nonNil(commitId);
      nonNil(revisionId);
      requireLogicalRevisionId(logicalRevisionId);
      if (!RealmEntryPolicy.isCanonicalSlug(worldSlug)
          || !RealmEntryPolicy.isCanonicalSlug(realmSlug)) {
        throw new IllegalArgumentException("Canonical policy deletion selectors required");
      }
    }
  }

  /** Closed owner payload, distinct from the policy JSON itself. */
  public static boolean isPolicyRevision(DraftCommitBinding.RevisionPayload input) {
    var kind = text(tree(input.payload()), "revisionKind");
    if (RealmEntryPolicy.REVISION_KIND.equals(kind)) return true;
    if ("COMMAND_DEFINITION".equals(kind)
        || AssetSource.REVISION_KIND.equals(kind)
        || BrandingSource.REVISION_KIND.equals(kind)
        || net.firedevops.firemud.common.gamedesign.GameplayRuleSource.REVISION_KIND.equals(kind)
        || TemplateConfigSource.REVISION_KIND.equals(kind))
      return false;
    throw new IllegalArgumentException("Unsupported Game Design owner revision kind");
  }

  public static Policy revision(
      DraftCommitBinding binding, DraftCommitBinding.RevisionPayload input) {
    if (input.owner() != DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE) {
      throw new IllegalArgumentException("Policy revision belongs to Game Design control plane");
    }
    JsonNode root = tree(input.payload());
    fields(root, "revisionKind", "logicalRevisionId", "policy");
    if (!RealmEntryPolicy.REVISION_KIND.equals(text(root, "revisionKind"))) {
      throw new IllegalArgumentException("Unsupported Game Design owner revision kind");
    }
    return new Policy(
        binding.commitId(),
        input.revisionId(),
        text(root, "logicalRevisionId"),
        RealmEntryPolicy.parse(root.get("policy").toString(), JSON));
  }

  /** Parses one closed owner mutation while preserving the established UPSERT policy parser. */
  public static Mutation mutation(
      DraftCommitBinding binding, DraftCommitBinding.RevisionPayload input) {
    if (input.owner() != DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE) {
      throw new IllegalArgumentException("Policy mutation belongs to Game Design control plane");
    }
    JsonNode root = tree(input.payload());
    if (!RealmEntryPolicy.REVISION_KIND.equals(text(root, "revisionKind"))) {
      throw new IllegalArgumentException("Unsupported Game Design owner revision kind");
    }
    if (!root.has("operation")) return revision(binding, input);

    fields(root, "revisionKind", "logicalRevisionId", "operation", "worldSlug", "realmSlug");
    if (!"DELETE".equals(text(root, "operation"))) {
      throw new IllegalArgumentException("Unsupported realm policy mutation operation");
    }
    return new Deletion(
        binding.commitId(),
        input.revisionId(),
        text(root, "logicalRevisionId"),
        text(root, "worldSlug"),
        text(root, "realmSlug"));
  }

  public static List<Policy> effective(List<Policy> inherited, List<? extends Mutation> updates) {
    Map<String, Policy> policies = new LinkedHashMap<>();
    for (Policy policy : draftOrdered(inherited)) policies.put(selector(policy), policy);
    var seen = new HashSet<String>();
    for (Mutation mutation : updates) {
      String selector = selector(mutation);
      if (!seen.add(selector)) {
        throw new IllegalArgumentException("A commit repeats a policy selector");
      }
      if (mutation instanceof Policy policy) {
        policies.put(selector, policy);
      } else if (mutation instanceof Deletion deletion) {
        Policy existing = policies.get(selector);
        if (existing == null) {
          throw new IllegalArgumentException("Cannot delete an absent policy selector");
        }
        if (!existing.logicalRevisionId().equals(deletion.logicalRevisionId())) {
          throw new IllegalArgumentException(
              "Policy deletion logical revision identity does not match its selector");
        }
        policies.remove(selector);
      }
    }
    return draftOrdered(new ArrayList<>(policies.values()));
  }

  public static List<Policy> ordered(List<Policy> policies) {
    var ordered = draftOrdered(policies);
    if (policies.isEmpty() || policies.size() > MAX_POLICIES) {
      throw new InvalidPublicationPolicySetException(
          "Complete policy set must contain 1 through 128 policies");
    }
    int production = 0;
    for (Policy policy : policies) {
      if (policy.policy().visible() && policy.policy().publicProduction()) production++;
    }
    if (production != 1) {
      throw new InvalidPublicationPolicySetException(
          "Exactly one visible public-production realm required");
    }
    return ordered;
  }

  /** Draft source may be empty or not yet publication-complete. */
  public static List<Policy> draftOrdered(List<Policy> policies) {
    if (policies.size() > MAX_POLICIES)
      throw new IllegalArgumentException("Draft policy set exceeds 128 policies");
    var realms = new HashSet<String>();
    var revisions = new HashSet<UUID>();
    for (Policy policy : policies) {
      if (!realms.add(policy.policy().realmSlug()) || !revisions.add(policy.revisionId())) {
        throw new IllegalArgumentException("Duplicate tenant realm selector or authored revision");
      }
    }
    return policies.stream()
        .sorted(
            Comparator.comparing((Policy p) -> p.policy().worldSlug())
                .thenComparing(p -> p.policy().realmSlug()))
        .toList();
  }

  static String policiesJson(List<Policy> policies) {
    return canonical(
        draftOrdered(policies).stream()
            .map(
                policy ->
                    Map.of(
                        "commitId",
                        policy.commitId().toString(),
                        "revisionId",
                        policy.revisionId().toString(),
                        "logicalRevisionId",
                        policy.logicalRevisionId(),
                        "policy",
                        tree(policy.policy().canonicalJson())))
            .toList());
  }

  static List<Policy> policiesFromStored(String bytes) {
    JsonNode root = tree(bytes);
    if (!root.isArray() || root.size() > MAX_POLICIES) {
      throw new IllegalArgumentException("Complete stored policy array required");
    }
    var policies = new ArrayList<Policy>();
    for (JsonNode node : root) {
      fields(node, "commitId", "revisionId", "logicalRevisionId", "policy");
      policies.add(
          new Policy(
              UUID.fromString(text(node, "commitId")),
              UUID.fromString(text(node, "revisionId")),
              text(node, "logicalRevisionId"),
              RealmEntryPolicy.parseCanonical(node.get("policy").toString(), JSON)));
    }
    var result = draftOrdered(policies);
    if (!policiesJson(result).equals(bytes)) {
      throw new IllegalArgumentException("Stored policy snapshot is not canonical");
    }
    return result;
  }

  static String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Canonical owner source encoding failed", failure);
    }
  }

  static JsonNode tree(String value) {
    try {
      return JSON.readTree(Rfc8785CanonicalJson.canonicalizeUtf8(value));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Unambiguous owner source JSON required", failure);
    }
  }

  static void fields(JsonNode node, String... fields) {
    if (!node.isObject() || node.size() != fields.length) {
      throw new IllegalArgumentException("Closed owner source fields required");
    }
    for (String field : fields)
      if (!node.has(field)) throw new IllegalArgumentException("Missing " + field);
  }

  static String text(JsonNode node, String field) {
    if (!node.path(field).isTextual())
      throw new IllegalArgumentException("Text required: " + field);
    return node.get(field).asText();
  }

  private static String selector(Policy policy) {
    return policy.policy().worldSlug() + "/" + policy.policy().realmSlug();
  }

  private static String selector(Mutation mutation) {
    if (mutation instanceof Policy policy) return selector(policy);
    Deletion deletion = (Deletion) mutation;
    return deletion.worldSlug() + "/" + deletion.realmSlug();
  }

  private static void requireLogicalRevisionId(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException("Exact authored logical revision identity required");
    }
  }

  private static void nonNil(UUID value) {
    if (value == null || new UUID(0, 0).equals(value))
      throw new IllegalArgumentException("Non-nil authored UUID required");
  }
}
