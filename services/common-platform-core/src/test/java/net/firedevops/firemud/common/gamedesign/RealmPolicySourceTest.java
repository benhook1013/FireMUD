package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RealmPolicySourceTest {
  private static final UUID TENANT = id("tenant");
  private static final UUID VERSION = id("version");
  private static final TargetProof TARGET =
      new TargetProof(TENANT, VERSION, 1L, "private", 2L, "private", "NEW_GAME_ROW");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void convertsOnlyTheExactPolicyRevisionInItsBinding() {
    var sourceRevision = policyRevision("policy-r1", "world");
    var binding = binding("selected", sourceRevision);

    var policy = RealmPolicySource.revision(binding, sourceRevision);

    assertThat(policy.commitId()).isEqualTo(binding.commitId());
    assertThat(policy.revisionId()).isEqualTo(sourceRevision.revisionId());

    var alteredRevision =
        new RevisionPayload(
            sourceRevision.revisionOrder(),
            sourceRevision.revisionId(),
            sourceRevision.owner(),
            policyPayload("policy-r1", "other-world"));
    assertThatThrownBy(() -> RealmPolicySource.revision(binding, alteredRevision))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in its exact binding");
  }

  @Test
  void preservesOriginalCommitAndRevisionForAnInheritedPolicy() {
    var originalRevision = policyRevision("policy-origin", "world");
    var originBinding = binding("origin", originalRevision);
    var originalPolicy = RealmPolicySource.revision(originBinding, originalRevision);
    var laterBinding =
        binding(
            "later",
            new RevisionPayload(
                "0", id("unrelated-revision"), Owner.GAME_DESIGN_CONTROL_PLANE, "opaque"));

    var inherited = RealmPolicySource.effective(List.of(originalPolicy), List.of());
    var snapshot = new RealmPolicySnapshot(laterBinding, "1", inherited);

    assertThat(snapshot.binding().commitId()).isEqualTo(laterBinding.commitId());
    assertThat(snapshot.policies()).hasSize(1);
    assertThat(snapshot.policies().getFirst().commitId()).isEqualTo(originBinding.commitId());
    assertThat(snapshot.policies().getFirst().revisionId()).isEqualTo(originalPolicy.revisionId());
  }

  private static RevisionPayload policyRevision(String logicalRevisionId, String worldSlug) {
    return new RevisionPayload(
        "0",
        id(logicalRevisionId + "-revision"),
        Owner.GAME_DESIGN_CONTROL_PLANE,
        policyPayload(logicalRevisionId, worldSlug));
  }

  private static String policyPayload(String logicalRevisionId, String worldSlug) {
    return RealmPolicySource.canonical(
        Map.of(
            "revisionKind",
            RealmPolicySource.REVISION_KIND,
            "logicalRevisionId",
            logicalRevisionId,
            "policy",
            RealmPolicySource.tree(
                "{\"schemaVersion\":1,\"worldSlug\":\""
                    + worldSlug
                    + "\",\"worldDisplayName\":\"World\",\"realmSlug\":\"realm\","
                    + "\"realmDisplayName\":\"Realm\",\"visible\":true,"
                    + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                    + "\"entryPolicy\":\"PRESEEDED_ONLY\"}")));
  }

  private static DraftCommitBinding binding(String name, RevisionPayload revision) {
    return DraftCommitBinding.create(
        TARGET,
        id(name + "-request"),
        id(name + "-commit"),
        "base-0",
        List.of(revision),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                VERSION.toString(),
                RealmPolicySource.SCOPE,
                "effective",
                "0")));
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
