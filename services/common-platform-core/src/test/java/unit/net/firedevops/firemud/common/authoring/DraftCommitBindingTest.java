package unit.net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.OwnedAffectedTuple;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DraftCommitBindingTest {
  private static final String LARGE_EPOCH = "900719925474099312345678901234567890";

  @Test
  void canonicalBindingRetainsCompleteOpaqueInputsAndDecimalCounters() {
    DraftCommitBinding binding = binding();
    DraftCommitBinding restored =
        DraftCommitBinding.fromStored(binding.canonicalJson(), binding.digest());

    assertThat(restored).isEqualTo(binding);
    assertThat(binding.canonicalJson())
        .contains("\"gameDesignVersionRowId\":\"9007199254740993\"")
        .contains("\"sourceGameRowId\":\"9007199254740995\"")
        .contains("\"expectedEpoch\":\"" + LARGE_EPOCH + "\"")
        .contains("unknownTypedField")
        .contains("\\n  \\\"full\\\": true");
    assertThat(binding.requiredOwners())
        .containsExactly(Owner.WORLD_MANAGEMENT, Owner.ENTITY_MANAGEMENT);
    assertThat(binding.affectedUnits())
        .extracting(AffectedUnit::aggregateType, AffectedUnit::aggregateId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("WORLD_TEMPLATE", "world-23"),
            org.assertj.core.groups.Tuple.tuple("ENTITY_TEMPLATE", "entity-17"));

    byte[] firstRead = binding.canonicalBytes();
    firstRead[0] = (byte) (firstRead[0] ^ 1);
    assertThat(binding.canonicalBytes()).isNotEqualTo(firstRead);
    assertThat(new String(binding.canonicalBytes(), StandardCharsets.UTF_8))
        .isEqualTo(binding.canonicalJson());
  }

  @Test
  void canonicalBindingIsStableAcrossAffectedInputOrder() {
    DraftCommitBinding first = binding();
    DraftCommitBinding reordered =
        DraftCommitBinding.create(
            first.target(),
            first.requestId(),
            first.commitId(),
            first.baseCommitId(),
            first.revisions(),
            List.of(first.affectedUnits().get(1), first.affectedUnits().get(0)));

    assertThat(reordered.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(reordered.digest()).isEqualTo(first.digest());
  }

  @Test
  void collectionAccessorsReturnImmutableCopies() {
    DraftCommitBinding binding = binding();

    assertThatThrownBy(() -> binding.revisions().add(binding.revisions().getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> binding.affectedUnits().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> binding.requiredOwners().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsMissingOrNonArrayStoredCollections() {
    DraftCommitBinding original = binding();
    ObjectMapper json = new ObjectMapper();

    ObjectNode missingRevisions = (ObjectNode) json.readTree(original.canonicalJson());
    missingRevisions.remove("revisions");
    assertThatThrownBy(
            () ->
                DraftCommitBinding.fromStored(
                    json.writeValueAsString(missingRevisions), original.digest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("corrupt");

    ObjectNode invalidAffectedUnits = (ObjectNode) json.readTree(original.canonicalJson());
    invalidAffectedUnits.put("affectedUnits", "not-an-array");
    assertThatThrownBy(
            () ->
                DraftCommitBinding.fromStored(
                    json.writeValueAsString(invalidAffectedUnits), original.digest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("corrupt");
  }

  @Test
  void rejectsOmittedDuplicateOrInconsistentCanonicalOrder() {
    DraftCommitBinding original = binding();
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    original.target(),
                    original.requestId(),
                    original.commitId(),
                    original.baseCommitId(),
                    List.of(original.revisions().get(0)),
                    original.affectedUnits()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner set");
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    original.target(),
                    original.requestId(),
                    original.commitId(),
                    original.baseCommitId(),
                    List.of(original.revisions().get(0), original.revisions().get(0)),
                    original.affectedUnits()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical array order");
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    original.target(),
                    original.requestId(),
                    original.commitId(),
                    original.baseCommitId(),
                    original.revisions(),
                    List.of(original.affectedUnits().get(0), original.affectedUnits().get(0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate affected");
  }

  @Test
  void rejectsNonCanonicalCountersAndNilOrUnprovedTargetIdentity() {
    DraftCommitBinding original = binding();
    AffectedUnit affected = original.affectedUnits().getFirst();
    assertThatThrownBy(
            () ->
                new AffectedUnit(
                    affected.owner(),
                    affected.aggregateType(),
                    affected.aggregateId(),
                    affected.scopeType(),
                    affected.scopeId(),
                    "01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical nonnegative decimal");
    assertThatThrownBy(
            () ->
                new TargetProof(
                    new UUID(0L, 0L),
                    original.target().canonicalVersionId(),
                    1,
                    "tenant-source",
                    2,
                    "tenant-source",
                    "NEW_GAME_ROW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil UUID");
    assertThatThrownBy(
            () ->
                new TargetProof(
                    original.target().canonicalTenantId(),
                    original.target().canonicalVersionId(),
                    1,
                    "tenant-source",
                    2,
                    "different-source",
                    "NEW_GAME_ROW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must match exactly");
  }

  @Test
  void worldStartLocationTupleCarriesTheSameUnboundedCanonicalDraftEpoch() {
    AffectedUnit draftUnit =
        binding().affectedUnits().stream()
            .filter(unit -> unit.owner() == Owner.WORLD_MANAGEMENT)
            .findFirst()
            .orElseThrow();
    OwnedAffectedTuple worldTuple =
        new OwnedAffectedTuple(
            draftUnit.owner().name(),
            draftUnit.aggregateType(),
            draftUnit.aggregateId(),
            draftUnit.scopeType(),
            draftUnit.scopeId(),
            draftUnit.expectedEpoch());

    assertThat(draftUnit.expectedEpoch()).isEqualTo(LARGE_EPOCH);
    assertThat(worldTuple.expectedEpoch()).isEqualTo(draftUnit.expectedEpoch());
  }

  private DraftCommitBinding binding() {
    return DraftCommitBinding.create(
        new TargetProof(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            9_007_199_254_740_993L,
            "tenant-source",
            9_007_199_254_740_995L,
            "tenant-source",
            "NEW_GAME_ROW"),
        uuid("33333333-3333-4333-8333-333333333333"),
        uuid("44444444-4444-4444-8444-444444444444"),
        "base-commit-opaque-01",
        List.of(
            new RevisionPayload(
                "0",
                uuid("55555555-5555-4555-8555-555555555555"),
                Owner.WORLD_MANAGEMENT,
                "{\n  \"full\": true, \"unknownTypedField\": {\"keep\": \"all\"}\n}"),
            new RevisionPayload(
                "1",
                uuid("66666666-6666-4666-8666-666666666666"),
                Owner.ENTITY_MANAGEMENT,
                "{\"actorTemplate\":{\"complete\":true}}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-23",
                "ROOM_SCOPE",
                "room-scope-3",
                LARGE_EPOCH),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT,
                "ENTITY_TEMPLATE",
                "entity-17",
                "ACTOR_SCOPE",
                "actor-scope-2",
                "0")));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
