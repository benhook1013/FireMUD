package net.firedevops.firemud.common.gamedesign;

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
import org.junit.jupiter.api.Test;

class BrandingSourceTest {
  private static final TargetProof TARGET =
      new TargetProof(id("tenant"), id("version"), 1L, "private", 2L, "private", "NEW_GAME_ROW");

  @Test
  void deletesOnlyAnExistingReferenceWithTheMatchingRole() {
    var logo = reference("logo-origin", "logo.svg", "101", BrandingSource.Role.LOGO);
    var favicon = reference("favicon-origin", "favicon.ico", "102", BrandingSource.Role.FAVICON);
    var delete =
        binding("logo-delete", BrandingSource.deletePayload("logo.svg", BrandingSource.Role.LOGO));

    var snapshot = BrandingSource.replay(List.of(logo, favicon), delete);

    assertThat(snapshot.references()).containsExactly(favicon);
  }

  @Test
  void rejectsDeleteForAnAbsentUsageOrAMismatchedRole() {
    var absentDelete =
        binding(
            "absent-delete", BrandingSource.deletePayload("missing.svg", BrandingSource.Role.LOGO));
    assertThatThrownBy(() -> BrandingSource.replay(List.of(), absentDelete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing usage key");

    var logo = reference("logo-origin", "logo.svg", "101", BrandingSource.Role.LOGO);
    var wrongRoleDelete =
        binding(
            "wrong-role-delete",
            BrandingSource.deletePayload("logo.svg", BrandingSource.Role.FAVICON));
    assertThatThrownBy(() -> BrandingSource.replay(List.of(logo), wrongRoleDelete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role differs");
  }

  private static BrandingSource.Reference reference(
      String source, String usageKey, String assetRowId, BrandingSource.Role role) {
    var binding =
        binding(
            source,
            BrandingSource.upsertPayload(
                assetRowId, usageKey, role, BrandingSource.Requiredness.REQUIRED));
    return new BrandingSource.Reference(
        usageKey,
        assetRowId,
        role,
        BrandingSource.Requiredness.REQUIRED,
        binding,
        "0",
        binding.revisions().getFirst().revisionId());
  }

  private static DraftCommitBinding binding(String name, String payload) {
    UUID revisionId = id(name + "-revision");
    return DraftCommitBinding.create(
        TARGET,
        id(name + "-request"),
        id(name + "-commit"),
        "base-0",
        List.of(new RevisionPayload("0", revisionId, Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                BrandingSource.SCOPE,
                TARGET.canonicalVersionId().toString(),
                BrandingSource.SCOPE,
                BrandingSource.SCOPE_ID,
                "0")));
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
