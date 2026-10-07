package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RealmPolicySourceTest {
  @Test
  void draftPolicyStateCanBeEmptyOrIncompleteWithoutPublicationCardinality() {
    var empty = new RealmPolicySnapshot(binding(), "0", List.of());
    assertThat(RealmPolicySnapshot.fromStored(empty.canonicalJson()).policies()).isEmpty();
    var hidden = policy("private", false);
    assertThat(RealmPolicySource.effective(List.of(), List.of(hidden))).containsExactly(hidden);
    assertThatThrownBy(() -> RealmPolicySource.ordered(empty.policies()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RealmPolicySource.ordered(List.of(hidden)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactCanonicalSnapshotRetainsOriginalAuthoredProvenanceAndDisjointInheritance() {
    var original = policy("main", true);
    var binding = binding();
    var snapshot =
        new RealmPolicySnapshot(binding, "90071992547409931234567890", List.of(original));
    assertThat(RealmPolicySnapshot.fromStored(snapshot.canonicalJson())).isEqualTo(snapshot);
    assertThat(RealmPolicySource.effective(snapshot.policies(), List.of()))
        .containsExactly(original);
    assertThatThrownBy(() -> RealmPolicySnapshot.fromStored(snapshot.canonicalJson() + " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closedTypedRevisionRejectsMalformedUnknownAndUnsupportedFields() {
    var binding = binding();
    var original = policy("main", true);
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-main\",\"policy\":"
            + original.policy().canonicalJson()
            + "}";
    var input =
        new DraftCommitBinding.RevisionPayload(
            "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, payload);
    assertThat(RealmPolicySource.revision(binding, input).logicalRevisionId())
        .isEqualTo("authored-main");
    for (String malformed :
        List.of(
            payload.replace("PRESEEDED_ONLY", "AUTO_PROVISIONED"),
            payload.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"),
            payload.replace(
                "\"revisionKind\":",
                "\"revisionKind\":\"REALM_ENTRY_POLICY\",\"revisionKind\":"))) {
      assertThatThrownBy(
              () ->
                  RealmPolicySource.revision(
                      binding,
                      new DraftCommitBinding.RevisionPayload(
                          "0", input.revisionId(), input.owner(), malformed)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void effectiveSetRejectsDuplicateRealmAndProductionAndOverLimitInsteadOfTruncating() {
    var main = policy("main", true);
    assertThatThrownBy(() -> RealmPolicySource.ordered(List.of(main, policy("main", false))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RealmPolicySource.ordered(List.of(main, policy("another", true))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RealmPolicySource.ordered(List.of(policy("hidden", false))))
        .isInstanceOf(IllegalArgumentException.class);
    var bound = IntStream.range(0, 128).mapToObj(i -> policy("realm-" + i, i == 0)).toList();
    assertThat(RealmPolicySource.ordered(bound)).hasSize(128);
    var oversized = new java.util.ArrayList<>(bound);
    oversized.add(policy("last", false));
    assertThatThrownBy(() -> RealmPolicySource.ordered(oversized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RealmPolicySource.Policy policy(String realm, boolean production) {
    String json =
        "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
            + "\"realmSlug\":\""
            + realm
            + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
            + "\"publicProduction\":"
            + production
            + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    return new RealmPolicySource.Policy(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "authored-" + realm,
        RealmEntryPolicy.parse(json, new ObjectMapper()));
  }

  private static DraftCommitBinding binding() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "owner", 1, "owner", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ISOLATED-base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                "ISOLATED-world")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.WORLD_MANAGEMENT, "ROOM", "room", "ROOM", "room", "0")));
  }
}
