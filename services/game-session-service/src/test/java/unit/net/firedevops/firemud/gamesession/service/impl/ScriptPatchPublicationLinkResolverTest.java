package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class ScriptPatchPublicationLinkResolverTest {
  @Test
  void resolvesPublicationUsingExactVersionAndBase() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(
            publishedPatch("1", "patch-7", 7L, 100L));

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getScriptPatchVersion()).isEqualTo("patch-7");
    assertThat(result.getVersionId()).isEqualTo(7L);
    assertThat(result.getBaseVersionId()).isEqualTo(100L);
    assertThat(result.getPublicationState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(result.getLastChangedAtMs()).isEqualTo(123L);
    assertThat(result.getLookupErrorCode()).isEmpty();
    verify(gameDesign).getPublishedScriptPatchVersion(1L, "patch-7", 100L);
  }

  @Test
  void missingBaseAndMissingClientFailClosedWithoutLookup() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);

    ScriptPatchPublicationLink missingBase =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", null);
    ScriptPatchPublicationLink missingClient =
        ScriptPatchPublicationLinkResolver.resolve(null, 1L, "patch-7", 100L);

    assertThat(missingBase.getLookupErrorCode()).isEqualTo("SCRIPT_PATCH_BASE_VERSION_REQUIRED");
    assertThat(missingBase.getScriptPatchVersion()).isEqualTo("patch-7");
    assertThat(missingClient.getLookupErrorCode())
        .isEqualTo("SCRIPT_PATCH_PUBLICATION_LOOKUP_UNAVAILABLE");
    assertThat(missingClient.getScriptPatchVersion()).isEqualTo("patch-7");
    verifyNoInteractions(gameDesign);
  }

  @Test
  void nullLookupResponseIsReportedAsUnavailable() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L)).thenReturn(null);

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode())
        .isEqualTo("SCRIPT_PATCH_PUBLICATION_LOOKUP_UNAVAILABLE");
    assertThat(result.getVersionId()).isZero();
    assertThat(result.getBaseVersionId()).isZero();
  }

  @Test
  void explicitGameDesignErrorIsPreserved() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("PUBLICATION_UNAVAILABLE").setMessage("down"))
                .build());

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode()).isEqualTo("PUBLICATION_UNAVAILABLE");
    assertThat(result.getLookupErrorMessage()).isEqualTo("down");
  }

  @Test
  void emptySuccessPayloadIsReportedAsLookupError() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(GetPublishedScriptPatchVersionResponse.getDefaultInstance());

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode()).isEqualTo("SCRIPT_PATCH_PUBLICATION_LOOKUP_EMPTY");
  }

  @Test
  void wrongReturnedPatchVersionIsReportedAsProvenanceMismatch() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(publishedPatch("1", "patch-other", 7L, 100L));

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode()).isEqualTo("SCRIPT_PATCH_PROVENANCE_MISMATCH");
  }

  @Test
  void wrongReturnedBaseIsReportedAsProvenanceMismatch() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(publishedPatch("1", "patch-7", 7L, 101L));

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode()).isEqualTo("SCRIPT_PATCH_PROVENANCE_MISMATCH");
  }

  @Test
  void wrongReturnedTenantIsReportedAsProvenanceMismatch() {
    GameDesignClient gameDesign = mock(GameDesignClient.class);
    when(gameDesign.getPublishedScriptPatchVersion(1L, "patch-7", 100L))
        .thenReturn(publishedPatch("2", "patch-7", 7L, 100L));

    ScriptPatchPublicationLink result =
        ScriptPatchPublicationLinkResolver.resolve(gameDesign, 1L, "patch-7", 100L);

    assertThat(result.getLookupErrorCode()).isEqualTo("SCRIPT_PATCH_PROVENANCE_MISMATCH");
  }

  private static GetPublishedScriptPatchVersionResponse publishedPatch(
      String tenantId, String patchVersion, long versionId, long baseVersionId) {
    return GetPublishedScriptPatchVersionResponse.newBuilder()
        .setScriptPatch(
            PublishedScriptPatchVersion.newBuilder()
                .setTenantId(tenantId)
                .setScriptPatchVersion(patchVersion)
                .setVersionId(versionId)
                .setBaseVersionId(baseVersionId)
                .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(123L)
                .build())
        .build();
  }
}
