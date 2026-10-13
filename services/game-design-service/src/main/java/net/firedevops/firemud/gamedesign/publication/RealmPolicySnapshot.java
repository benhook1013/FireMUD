package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/** Complete effective source at one synchronized fence, including original revision provenance. */
public record RealmPolicySnapshot(
    DraftCommitBinding binding, String sourceEpoch, List<RealmPolicySource.Policy> policies) {
  public RealmPolicySnapshot {
    if (binding == null || sourceEpoch == null || !sourceEpoch.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException("Exact source binding and canonical epoch required");
    }
    policies = RealmPolicySource.draftOrdered(policies);
  }

  @Override
  public List<RealmPolicySource.Policy> policies() {
    return List.copyOf(policies);
  }

  public String canonicalJson() {
    return RealmPolicySource.canonical(
        Map.of(
            "schema",
            "game-design-realm-policy-snapshot/v1",
            "bindingJson",
            binding.canonicalJson(),
            "bindingDigest",
            binding.digest(),
            "sourceEpoch",
            sourceEpoch,
            "policies",
            RealmPolicySource.tree(RealmPolicySource.policiesJson(policies))));
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public static RealmPolicySnapshot fromStored(String json) {
    var node = RealmPolicySource.tree(json);
    RealmPolicySource.fields(
        node, "schema", "bindingJson", "bindingDigest", "sourceEpoch", "policies");
    if (!"game-design-realm-policy-snapshot/v1".equals(RealmPolicySource.text(node, "schema"))) {
      throw new IllegalArgumentException("Unsupported policy snapshot schema");
    }
    var result =
        new RealmPolicySnapshot(
            DraftCommitBinding.fromStored(
                RealmPolicySource.text(node, "bindingJson"),
                RealmPolicySource.text(node, "bindingDigest")),
            RealmPolicySource.text(node, "sourceEpoch"),
            RealmPolicySource.policiesFromStored(node.get("policies").toString()));
    if (!result.canonicalJson().equals(json))
      throw new IllegalArgumentException("Policy snapshot bytes changed");
    return result;
  }

  /** Frozen source only. No terminal association or PUBLISHED claim is represented here. */
  public record Capture(GameDesignPublicationOperation operation, RealmPolicySnapshot snapshot) {
    public Capture {
      RealmPolicySource.ordered(snapshot.policies());
      if (!operation.account().input().selection().selectedCommit().equals(snapshot.binding())) {
        throw new IllegalArgumentException(
            "Frozen policy snapshot differs from original selected commit");
      }
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "game-design-realm-policy-source-capture/v1");
      DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
      return out.toByteArray();
    }
  }
}
