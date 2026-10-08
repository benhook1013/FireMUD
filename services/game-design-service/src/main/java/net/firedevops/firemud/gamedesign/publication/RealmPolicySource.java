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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owner-local typed source; neither these bytes nor a caller's declaration grant authority. */
public final class RealmPolicySource {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String SCOPE = "REALM_ENTRY_POLICY_SET";
  public static final int MAX_POLICIES = 128;

  private RealmPolicySource() {}

  /** Original authored UUID and logical identity survive every inherited snapshot. */
  public record Policy(
      UUID commitId, UUID revisionId, String logicalRevisionId, RealmEntryPolicy policy) {
    public Policy {
      nonNil(commitId);
      nonNil(revisionId);
      if (logicalRevisionId == null
          || logicalRevisionId.isBlank()
          || logicalRevisionId.length() > 128
          || !StandardCharsets.UTF_8.newEncoder().canEncode(logicalRevisionId)) {
        throw new IllegalArgumentException("Exact authored logical revision identity required");
      }
      policy = RealmEntryPolicy.parseCanonical(policy.canonicalJson(), JSON);
    }
  }

  /** Closed owner payload, distinct from the policy JSON itself. */
  public static boolean isPolicyRevision(DraftCommitBinding.RevisionPayload input) {
    var kind = text(tree(input.payload()), "revisionKind");
    if (RealmEntryPolicy.REVISION_KIND.equals(kind)) return true;
    if ("COMMAND_DEFINITION".equals(kind)) return false;
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

  public static List<Policy> effective(List<Policy> inherited, List<Policy> updates) {
    Map<String, Policy> policies = new LinkedHashMap<>();
    for (Policy policy : draftOrdered(inherited)) policies.put(selector(policy), policy);
    var seen = new HashSet<String>();
    for (Policy policy : updates) {
      if (!seen.add(selector(policy))) {
        throw new IllegalArgumentException("A commit repeats a policy selector");
      }
      policies.put(selector(policy), policy);
    }
    return draftOrdered(new ArrayList<>(policies.values()));
  }

  public static List<Policy> ordered(List<Policy> policies) {
    var ordered = draftOrdered(policies);
    if (policies.isEmpty() || policies.size() > MAX_POLICIES) {
      throw new IllegalArgumentException("Complete policy set must contain 1 through 128 policies");
    }
    int production = 0;
    for (Policy policy : policies) {
      if (policy.policy().visible() && policy.policy().publicProduction()) production++;
    }
    if (production != 1) {
      throw new IllegalArgumentException("Exactly one visible public-production realm required");
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

  private static void nonNil(UUID value) {
    if (value == null || new UUID(0, 0).equals(value))
      throw new IllegalArgumentException("Non-nil authored UUID required");
  }
}
