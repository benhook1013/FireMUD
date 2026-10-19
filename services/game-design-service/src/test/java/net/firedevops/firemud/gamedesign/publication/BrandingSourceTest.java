package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import org.junit.jupiter.api.Test;

class BrandingSourceTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();

  @Test
  void everyRoleRetainsItsExactBindingAndRequiredPolicy() {
    for (var role : BrandingSource.Role.values()) {
      var binding =
          binding(
              BrandingSource.upsertPayload(
                  "1", "image.png", role, BrandingSource.Requiredness.REQUIRED));
      var reference = BrandingSource.replay(List.of(), binding).references().getFirst();
      assertThat(reference.role()).isEqualTo(role);
      assertThat(reference.family()).isEqualTo("BRANDING");
      assertThat(reference.sourceBinding()).isSameAs(binding);
      assertThatThrownBy(
              () ->
                  BrandingSource.upsertPayload(
                      "1", "image.png", role, BrandingSource.Requiredness.OPTIONAL))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void replayPreservesUntouchedOriginalRevisionAndExplicitRoleDeclarations() {
    var original =
        binding(
            BrandingSource.upsertPayload(
                "1", "logo.png", BrandingSource.Role.LOGO, BrandingSource.Requiredness.REQUIRED));
    var first = BrandingSource.replay(List.of(), original);
    var changed =
        binding(
            BrandingSource.upsertPayload(
                "2", "theme.css", BrandingSource.Role.THEME, BrandingSource.Requiredness.REQUIRED));
    var replay = BrandingSource.replay(first.references(), changed);
    assertThat(replay.references().getFirst().sourceBinding()).isEqualTo(original);
    var items =
        replay.references().stream()
            .map(
                r ->
                    new net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item(
                        r, "image/png", "sha256:" + "a".repeat(64), 1))
            .toList();
    var snapshot =
        new BrandingSourceSnapshot(changed, "2", original.commitId(), UUID.randomUUID(), items);
    assertThat(snapshot.roleDeclarations())
        .isEqualTo(
            Map.of("RESOURCE", "EMPTY", "LOGO", "PRESENT", "FAVICON", "EMPTY", "THEME", "PRESENT"));
    assertThat(BrandingSourceSnapshot.fromStored(snapshot.canonicalJson())).isEqualTo(snapshot);
    assertThatThrownBy(
            () ->
                BrandingSourceSnapshot.fromStored(
                    snapshot
                        .canonicalJson()
                        .replace("\"FAVICON\":\"EMPTY\"", "\"FAVICON\":\"PRESENT\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownMalformedOptionalAndOrdinaryReinterpretationDeny() {
    String payload =
        BrandingSource.upsertPayload(
            "1", "logo.png", BrandingSource.Role.LOGO, BrandingSource.Requiredness.REQUIRED);
    for (String invalid :
        List.of(
            payload.replace("LOGO", "UNKNOWN"),
            payload.replace("REQUIRED", "OPTIONAL"),
            payload.replace("BRANDING_ASSET_REFERENCE", "UNKNOWN"),
            payload.replace("\"family\":\"BRANDING\"", "\"family\":\"ORDINARY\""),
            payload.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            payload + "{}")) {
      assertThatThrownBy(() -> BrandingSource.mutations(binding(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                BrandingSource.replay(
                    List.of(),
                    binding(
                        BrandingSource.upsertPayload(
                            "1",
                            "a",
                            BrandingSource.Role.LOGO,
                            BrandingSource.Requiredness.REQUIRED),
                        BrandingSource.upsertPayload(
                            "1",
                            "b",
                            BrandingSource.Role.THEME,
                            BrandingSource.Requiredness.REQUIRED))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void knownSiblingDispatchPreservesFullBindingWithoutAcceptingUnknownKinds() {
    var binding =
        binding(
            BrandingSource.deletePayload("logo.png", BrandingSource.Role.LOGO),
            AssetSource.deletePayload("ordinary.png"));
    assertThat(BrandingSource.mutations(binding)).hasSize(1);
    assertThat(AssetSource.mutations(binding)).hasSize(1);
    assertThat(CommandSource.mutations(binding)).isEmpty();
    assertThat(CommandSource.hasRealmPolicyRevision(binding)).isFalse();
    assertThat(GameplayRuleSource.mutations(binding)).isEmpty();
    assertThat(BrandingSource.mutations(binding).getFirst().binding()).isSameAs(binding);
  }

  private static DraftCommitBinding binding(String... payloads) {
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    for (int i = 0; i < payloads.length; i++)
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[i]));
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        revisions,
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                BrandingSource.SCOPE,
                VERSION.toString(),
                BrandingSource.SCOPE,
                "effective",
                "0")));
  }
}
