package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.util.List;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;

/** Complete effective source at one synchronized fence, including original revision provenance. */
public record RealmPolicySnapshot(
    DraftCommitBinding binding, String sourceEpoch, List<RealmPolicySource.Policy> policies) {
  public RealmPolicySnapshot {
    var value =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            binding, sourceEpoch, policies);
    policies = value.policies();
  }

  @Override
  public List<RealmPolicySource.Policy> policies() {
    return List.copyOf(policies);
  }

  public String canonicalJson() {
    return shared().canonicalJson();
  }

  public byte[] canonicalBytes() {
    return shared().canonicalBytes();
  }

  public static RealmPolicySnapshot fromStored(String json) {
    return fromShared(
        net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot.fromStored(json));
  }

  public net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot shared() {
    return new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
        binding, sourceEpoch, policies);
  }

  public static RealmPolicySnapshot fromShared(
      net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot value) {
    return new RealmPolicySnapshot(value.binding(), value.sourceEpoch(), value.policies());
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
