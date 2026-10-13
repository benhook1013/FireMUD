package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AssetSourceTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();

  @Test
  void extractionPreservesExactBindingOrderAndExplicitRequiredness() {
    var binding =
        binding(
            AssetSource.upsertPayload(
                "9223372036854775807", " Logo.png ", AssetSource.Requiredness.REQUIRED),
            "{\"revisionKind\":\"COMMAND_DEFINITION\"}",
            "{\"revisionKind\":\"REALM_ENTRY_POLICY\"}",
            AssetSource.upsertPayload("2", "required.png", AssetSource.Requiredness.REQUIRED));
    var mutations = AssetSource.mutations(binding);

    assertThat(mutations).hasSize(2);
    assertThat(mutations).extracting(AssetSource.Mutation::revisionOrder).containsExactly("0", "3");
    assertThat(mutations.getFirst().binding()).isSameAs(binding);
    assertThat(mutations.getFirst().revisionId())
        .isEqualTo(binding.revisions().getFirst().revisionId());
    assertThat(mutations.getFirst().usageKey()).isEqualTo(" Logo.png ");
    assertThat(mutations.getFirst().requiredness()).isEqualTo(AssetSource.Requiredness.REQUIRED);
    assertThat(mutations.getLast().requiredness()).isEqualTo(AssetSource.Requiredness.REQUIRED);
    assertThat(mutations.getFirst().binding()).isSameAs(binding);
    assertThatThrownBy(() -> mutations.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void replayReplacesOnlyExplicitKeysAndRetainsExactSourceProvenance() {
    var original =
        binding(
            AssetSource.upsertPayload("1", "one.png", AssetSource.Requiredness.REQUIRED),
            AssetSource.upsertPayload("2", "two.png", AssetSource.Requiredness.REQUIRED));
    var baseline = AssetSource.replay(List.of(), original);
    var changed =
        binding(
            AssetSource.deletePayload("one.png"),
            AssetSource.upsertPayload("3", "one.png", AssetSource.Requiredness.REQUIRED));
    var result = AssetSource.replay(baseline.references(), changed);

    assertThat(result.binding()).isSameAs(changed);
    assertThat(result.references())
        .extracting(AssetSource.Reference::usageKey)
        .containsExactly("one.png", "two.png");
    assertThat(result.references().getFirst().assetRowId()).isEqualTo("3");
    assertThat(result.references().getFirst().requiredness())
        .isEqualTo(AssetSource.Requiredness.REQUIRED);
    assertThat(result.references().getFirst().family()).isEqualTo("ORDINARY");
    assertThat(result.references().getFirst().role()).isEqualTo("RESOURCE");
    assertThat(result.references().getFirst().sourceBinding()).isSameAs(changed);
    assertThat(result.references().getLast().sourceBinding()).isSameAs(original);
    assertThat(AssetSource.replay(result.references(), changed)).isEqualTo(result);
    assertThat(
            AssetSource.replay(result.references(), binding(AssetSource.deletePayload("two.png")))
                .references())
        .extracting(AssetSource.Reference::usageKey)
        .containsExactly("one.png");
  }

  @Test
  void referenceCannotRewriteItsBoundRequirednessOrRevisionIdentity() {
    var binding =
        binding(AssetSource.upsertPayload("1", "logo.png", AssetSource.Requiredness.REQUIRED));
    var revision = binding.revisions().getFirst();

    assertThatThrownBy(
            () ->
                new AssetSource.Reference(
                    "logo.png",
                    "1",
                    AssetSource.Requiredness.OPTIONAL,
                    binding,
                    "0",
                    revision.revisionId()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AssetSource.Reference(
                    "logo.png",
                    "1",
                    AssetSource.Requiredness.REQUIRED,
                    binding,
                    "0",
                    UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotRejectsForeignTargetDuplicateKeysAndSourceAliases() {
    var original =
        binding(AssetSource.upsertPayload("1", "logo.png", AssetSource.Requiredness.REQUIRED));
    var references = AssetSource.replay(List.of(), original).references();
    var foreign = binding(UUID.randomUUID(), AssetSource.deletePayload("logo.png"));

    assertThatThrownBy(() -> AssetSource.replay(references, foreign))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AssetSource.Snapshot(
                    original, List.of(references.getFirst(), references.getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AssetSource.replay(
                    references,
                    binding(
                        AssetSource.upsertPayload(
                            "1", "alias.png", AssetSource.Requiredness.REQUIRED))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotDefensivelyRetainsItsReferenceList() {
    var binding =
        binding(AssetSource.upsertPayload("1", "logo.png", AssetSource.Requiredness.REQUIRED));
    var mutable = new ArrayList<>(AssetSource.replay(List.of(), binding).references());
    var snapshot = new AssetSource.Snapshot(binding, mutable);
    mutable.clear();

    assertThat(snapshot.references()).hasSize(1);
    assertThatThrownBy(() -> snapshot.references().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "01",
        "-1",
        "+1",
        "1.0",
        "1e2",
        " 1",
        "9223372036854775808",
        "999999999999999999999999"
      })
  void deniesNoncanonicalOrOverflowingOwnerRowId(String value) {
    assertThatThrownBy(
            () -> AssetSource.mutations(binding(upsert("\"" + value + "\"", "\"REQUIRED\""))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "1.0", "1e0", "null", "true", "{}", "[]"})
  void ownerRowIdMustBeTextNotCoercedJson(String value) {
    assertThatThrownBy(() -> AssetSource.mutations(binding(upsert(value, "\"REQUIRED\""))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"UNKNOWN\"", "\"required\"", "null", "true", "1", "{}"})
  void deniesUnknownOrUntypedRequiredness(String value) {
    assertThatThrownBy(() -> AssetSource.mutations(binding(upsert("\"1\"", value))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deniesMissingRequirednessUnknownMembersDuplicateFieldsAndTrailingJson() {
    String valid = upsert("\"1\"", "\"REQUIRED\"");
    List<String> invalid =
        List.of(
            valid.replace(",\"requiredness\":\"REQUIRED\"", ""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"usageKey\":\"logo.png\"", "\"usageKey\":\"logo.png\",\"extra\":true"),
            valid + " {}",
            "{\"revisionKind\":\"UNSUPPORTED\"}",
            "{}",
            AssetSource.deletePayload("logo.png")
                .replace(
                    "\"operation\":\"DELETE\"",
                    "\"operation\":\"DELETE\",\"requiredness\":\"OPTIONAL\""));
    for (String payload : invalid) {
      assertThatThrownBy(() -> AssetSource.mutations(binding(payload)))
          .as(payload)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void usageKeyIsExactBoundedAndCannotReserveManifestName() {
    for (String key : List.of("", " ", "manifest.json", "x".repeat(256))) {
      assertThatThrownBy(
              () -> AssetSource.upsertPayload("1", key, AssetSource.Requiredness.REQUIRED))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            AssetSource.mutations(
                binding(
                    AssetSource.upsertPayload(
                        "1", "x".repeat(255), AssetSource.Requiredness.REQUIRED))))
        .hasSize(1);
  }

  @Test
  void ordinaryResourceCannotClaimOptionalPresentationOrAnUnknownFamilyOrRole() {
    assertThatThrownBy(
            () -> AssetSource.upsertPayload("1", "logo.png", AssetSource.Requiredness.OPTIONAL))
        .isInstanceOf(IllegalArgumentException.class);
    String required = upsert("\"1\"", "\"REQUIRED\"");
    for (String payload :
        List.of(
            required.replace("REQUIRED", "OPTIONAL"),
            required.replace("ORDINARY", "BRANDING"),
            required.replace("RESOURCE", "PRESENTATION"),
            required.replace(",\"role\":\"RESOURCE\"", ""),
            required.replace(",\"family\":\"ORDINARY\"", ""))) {
      assertThatThrownBy(() -> AssetSource.mutations(binding(payload)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static String upsert(String rowIdJson, String requirednessJson) {
    return "{\"schemaVersion\":1,\"revisionKind\":\"ASSET_REFERENCE\",\"family\":\"ORDINARY\",\"role\":\"RESOURCE\",\"operation\":\"UPSERT\",\"assetRowId\":"
        + rowIdJson
        + ",\"usageKey\":\"logo.png\",\"requiredness\":"
        + requirednessJson
        + "}";
  }

  private static DraftCommitBinding binding(String... payloads) {
    return binding(VERSION, payloads);
  }

  private static DraftCommitBinding binding(UUID versionId, String... payloads) {
    List<RevisionPayload> revisions = new ArrayList<>();
    for (int index = 0; index < payloads.length; index++) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(index),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[index]));
    }
    return DraftCommitBinding.create(
        new TargetProof(TENANT, versionId, 23L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        revisions,
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                AssetSource.SCOPE,
                versionId.toString(),
                AssetSource.SCOPE,
                AssetSource.SCOPE_ID,
                "0")));
  }
}
