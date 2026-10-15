package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.Test;

/** Structural digest vectors only; synthetic source rows do not prove physical owner provenance. */
class SelectedDraftControlPlaneDigestTest {
  @Test
  void exactSixFamilyPreimageIsDeterministicAndDoesNotIncludePublicationRequestIdentity()
      throws Exception {
    var selected = selectedBinding();
    var sources = sources(selected);

    var firstRequest =
        PublicationDigestRequestBinding.full(
            selected.target().canonicalTenantId().toString(),
            Long.toString(selected.target().gameDesignVersionRowId()),
            "publish-one");
    var retryRequest =
        PublicationDigestRequestBinding.full(
            selected.target().canonicalTenantId().toString(),
            Long.toString(selected.target().gameDesignVersionRowId()),
            "publish-two");

    var first = SelectedDraftControlPlaneDigest.compute(selected, sources);
    var replay = SelectedDraftControlPlaneDigest.compute(selected, sources);
    var otherRequest = SelectedDraftControlPlaneDigest.compute(selected, sources);

    assertThat(first).isEqualTo(replay);
    assertThat(first).isEqualTo(otherRequest);
    assertThat(firstRequest.requestDigest()).isNotEqualTo(retryRequest.requestDigest());
    assertThat(first.digestSchemaVersion()).isEqualTo(2);
    assertThat(first.tenantId()).isEqualTo(selected.target().canonicalTenantId().toString());
    assertThat(first.scopeValue())
        .isEqualTo(Long.toString(selected.target().gameDesignVersionRowId()));
    assertThat(first.appliedCommitId()).isEqualTo(selected.commitId().toString());
    assertThat(first.contentDigest())
        .isEqualTo("414e333c7a695aa81cfe86d39eda7adb13cd3e16b78d2e2bed5df67b08181a5a");
  }

  @Test
  void changedSnapshotOrSelectedBindingChangesContentDigest() throws Exception {
    var selected = selectedBinding();
    var originalSources = sources(selected);
    var original = SelectedDraftControlPlaneDigest.compute(selected, originalSources);

    var changedAsset =
        new AssetSnapshot(
            selected,
            "1",
            null,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            List.of());
    var changedSnapshot =
        new GameDesignSourceRepository.SynchronizedSources(
            originalSources.command(),
            originalSources.policy(),
            Optional.of(changedAsset),
            originalSources.gameplayEvidence(),
            originalSources.branding(),
            originalSources.templateConfig());
    assertThat(SelectedDraftControlPlaneDigest.compute(selected, changedSnapshot).contentDigest())
        .isNotEqualTo(original.contentDigest());

    var changedBinding =
        DraftCommitBinding.create(
            selected.target(),
            selected.requestId(),
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            selected.baseCommitId(),
            selected.revisions(),
            selected.affectedUnits());
    var changedInput =
        SelectedDraftControlPlaneDigest.compute(changedBinding, sources(changedBinding));
    assertThat(changedInput.contentDigest()).isNotEqualTo(original.contentDigest());
  }

  @Test
  void missingBrandingOrTemplateFamilyCannotBeTreatedAsAnEmptySnapshot() throws Exception {
    var selected = selectedBinding();
    var complete = sources(selected);
    var missingBrand =
        new GameDesignSourceRepository.SynchronizedSources(
            complete.command(),
            complete.policy(),
            complete.assetEvidence(),
            complete.gameplayEvidence(),
            Optional.empty(),
            complete.templateConfig());
    var missingTemplate =
        new GameDesignSourceRepository.SynchronizedSources(
            complete.command(),
            complete.policy(),
            complete.assetEvidence(),
            complete.gameplayEvidence(),
            complete.branding(),
            Optional.empty());

    assertThatThrownBy(() -> SelectedDraftControlPlaneDigest.compute(selected, missingBrand))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("branding");
    assertThatThrownBy(() -> SelectedDraftControlPlaneDigest.compute(selected, missingTemplate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("template-config");
  }

  @Test
  void sourceBindingMustExactlyEqualSelectedDraftBinding() throws Exception {
    var selected = selectedBinding();
    var otherBinding =
        DraftCommitBinding.create(
            selected.target(),
            selected.requestId(),
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            selected.baseCommitId(),
            selected.revisions(),
            selected.affectedUnits());

    assertThatThrownBy(
            () -> SelectedDraftControlPlaneDigest.compute(selected, sources(otherBinding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact Draft commit");
  }

  private static GameDesignPublicationOperation operation() throws Exception {
    return IsolatedPublicationOperationFixtures.fresh(
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }

  private static DraftCommitBinding selectedBinding() throws Exception {
    return operation().account().input().selection().selectedCommit();
  }

  private static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding) {
    var command = new CommandSnapshot(binding, "0", null, "sha256:" + "a".repeat(64), List.of());
    // Controlled inherited policy provenance; this World-only commit need not mutate policy.
    var policy =
        new RealmPolicySnapshot(
            binding,
            "1",
            List.of(
                new RealmPolicySource.Policy(
                    UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                    UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    "inherited-policy",
                    net.firedevops.firemud.common.publication.RealmEntryPolicy.parse(
                        "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                            + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
                            + "\"publicProduction\":false,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                        new tools.jackson.databind.ObjectMapper()))));
    var asset =
        new AssetSnapshot(
            binding, "0", null, UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of());
    var gameplay =
        new GameplayRuleSnapshot(
            binding, "0", null, UUID.fromString("44444444-4444-4444-8444-444444444444"), List.of());
    var branding =
        new BrandingSourceSnapshot(
            binding, "0", null, UUID.fromString("55555555-5555-4555-8555-555555555555"), List.of());
    var template =
        new TemplateConfigSourceSnapshot(
            binding, "0", null, UUID.fromString("66666666-6666-4666-8666-666666666666"), List.of());
    return new GameDesignSourceRepository.SynchronizedSources(
        command,
        policy,
        Optional.of(asset),
        Optional.of(gameplay),
        Optional.of(branding),
        Optional.of(template));
  }
}
