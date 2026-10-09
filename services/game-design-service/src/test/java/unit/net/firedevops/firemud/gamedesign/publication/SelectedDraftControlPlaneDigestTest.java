package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
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
        .isEqualTo("08584ce89ee924b5d8b5b613ee60ccc0780716ba43cc7671fd5735b4b5e8015b");
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
            changedAsset,
            originalSources.gameplay(),
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
            complete.asset(),
            complete.gameplay(),
            Optional.empty(),
            complete.templateConfig());
    var missingTemplate =
        new GameDesignSourceRepository.SynchronizedSources(
            complete.command(),
            complete.policy(),
            complete.asset(),
            complete.gameplay(),
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
    var selected = operation().account().input().selection().selectedCommit();
    var policyRevisions =
        selected.revisions().stream()
            .filter(
                revision -> revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(RealmPolicySource::isPolicyRevision)
            .toList();
    if (policyRevisions.size() != 1) {
      throw new IllegalStateException("Expected exactly one selected policy revision");
    }
    var policyRevision = policyRevisions.getFirst();
    var fixedRevisionId = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    var oldRevisionId = policyRevision.revisionId().toString();
    var revisions = new java.util.ArrayList<RevisionPayload>();
    for (var revision : selected.revisions()) {
      var payload =
          new String(
              Rfc8785CanonicalJson.canonicalizeUtf8(revision.payload()),
              java.nio.charset.StandardCharsets.UTF_8);
      var revisionId = revision.revisionId();
      if (revision == policyRevision) {
        if (payload.indexOf(oldRevisionId) < 0
            || payload.indexOf(oldRevisionId) != payload.lastIndexOf(oldRevisionId)) {
          throw new IllegalStateException(
              "Expected policy revision identity exactly once in payload");
        }
        payload = payload.replace(oldRevisionId, fixedRevisionId.toString());
        revisionId = fixedRevisionId;
      }
      revisions.add(
          new RevisionPayload(revision.revisionOrder(), revisionId, revision.owner(), payload));
    }
    return DraftCommitBinding.create(
        selected.target(),
        selected.requestId(),
        selected.commitId(),
        selected.baseCommitId(),
        revisions,
        selected.affectedUnits());
  }

  private static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding) {
    var command = new CommandSnapshot(binding, "0", null, "sha256:" + "a".repeat(64), List.of());
    var policies =
        binding.revisions().stream()
            .filter(
                revision -> revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(RealmPolicySource::isPolicyRevision)
            .map(revision -> RealmPolicySource.revision(binding, revision))
            .toList();
    var policy = new RealmPolicySnapshot(binding, "1", policies);
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
        command, policy, asset, gameplay, Optional.of(branding), Optional.of(template));
  }
}
