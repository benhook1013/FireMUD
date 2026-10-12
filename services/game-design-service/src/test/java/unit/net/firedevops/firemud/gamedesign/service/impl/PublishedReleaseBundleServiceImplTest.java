package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class PublishedReleaseBundleServiceImplTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String LOGO_DIGEST = "sha256:" + "b".repeat(64);

  @Mock private PublishedReleaseBundleRepository repository;

  private PublishedReleaseBundleServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new PublishedReleaseBundleServiceImpl(repository, new ObjectMapper());
  }

  @Test
  void legacyFullVersionBundleFailsClosedBeforeReadingMutableSources() {
    var refusal =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.createFullVersionBundle(
                    version(), "workflow-1", logoManifest(), "genrev-1", List.of()));
    assertEquals(
        "PUBLISH_SELECTION_REQUIRED: full-version bundles require the captured publication operation",
        refusal.getMessage());
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void retainedBundleWithoutCanonicalIdentityStaysAbsent() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setParticipantDigestsJson(
        "[{\"participantKey\":\"GAME_LOGIC\",\"scopeValue\":\"7\","
            + "\"baseVersionId\":null,\"appliedCommitId\":\"version:7\","
            + "\"contentDigest\":\"legacy-aggregate\",\"digestSchemaVersion\":1,"
            + "\"errorCode\":null,\"errorMessage\":null}]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    var dto = service.findPublishedReleaseBundle("tenant-1", 7L).orElseThrow();

    assertNull(dto.canonicalTenantId());
    assertNull(dto.canonicalVersionId());
    assertNull(dto.publishedReleaseBundleRef());
    assertNull(dto.manifestSchemaVersion());
    assertNull(dto.artifactDigests());
    assertNull(dto.worldPublishedStartLocationEvidence());
    assertNull(dto.participantDigests().getFirst().abilitySchemaDigest());
  }

  @Test
  void corruptedSelectorEvidenceIsRejectedOnRead() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setAttestationSchemaVersion("v2");
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setWorldPublishedStartLocationEvidenceJson("{}");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    IllegalArgumentException denied =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.getPublishedReleaseBundle("tenant-1", 7L));
    assertEquals(
        "published World selector evidence has missing or unsupported fields", denied.getMessage());
  }

  @Test
  void retainedBundleWithBlankArtifactDigestStorageKeepsArtifactProofAbsent() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setArtifactDigestsJson(" \t ");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    var dto = service.findPublishedReleaseBundle("tenant-1", 7L).orElseThrow();

    assertNull(dto.canonicalTenantId());
    assertNull(dto.canonicalVersionId());
    assertNull(dto.publishedReleaseBundleRef());
    assertNull(dto.manifestSchemaVersion());
    assertNull(dto.artifactDigests());
  }

  @Test
  void retainedBundleReadsPopulatedArtifactEvidence() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setManifestSchemaVersion(1);
    retained.setArtifactDigestsJson(
        "[{\"usageKey\":\"logo.png\",\"artifactKind\":\"IMAGE\","
            + "\"immutableObjectKey\":\"artifacts/sha256/"
            + "0000000000000000000000000000000000000000000000000000000000000000\","
            + "\"contentDigest\":\"sha256:"
            + "0000000000000000000000000000000000000000000000000000000000000000\","
            + "\"contentType\":\"image/png\",\"artifactSchemaVersion\":1}]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    var dto = service.findPublishedReleaseBundle("tenant-1", 7L).orElseThrow();

    assertEquals(
        List.of(
            new PublishedArtifactDigest(
                "logo.png",
                "IMAGE",
                "artifacts/sha256/0000000000000000000000000000000000000000000000000000000000000000",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                "image/png",
                1)),
        dto.artifactDigests());
  }

  @Test
  void retainedBundleRejectsNonblankMalformedArtifactDigestStorage() {
    PublishedReleaseBundle retained = new PublishedReleaseBundle();
    retained.setId(11L);
    retained.setTenantId("tenant-1");
    retained.setVersionId(7L);
    retained.setArtifactDigestsJson("[{}]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(retained));

    assertThrows(RuntimeException.class, () -> service.findPublishedReleaseBundle("tenant-1", 7L));
  }

  @Test
  void optionalReadDistinguishesMissingBundleWhileRequiredReadStillFails() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), service.findPublishedReleaseBundle("tenant-1", 7L));
    assertThrows(
        PublishedReleaseBundleNotFoundException.class,
        () -> service.getPublishedReleaseBundle("tenant-1", 7L));
  }

  @Test
  void legacyFullVersionBundleRefusesEmptyManifestBeforeRepositoryAccess() {
    var refusal =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.createFullVersionBundle(
                    version(), "workflow-1", emptyManifest(), "genrev-1", List.of()));
    assertEquals(
        "PUBLISH_SELECTION_REQUIRED: full-version bundles require the captured publication operation",
        refusal.getMessage());
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  private VersionDto version() {
    return new VersionDto(
        7L,
        "tenant-1",
        8,
        VersionLifecycleState.PUBLISHED,
        2L,
        null,
        null,
        false,
        "notes",
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  private static ExportedAssetManifest emptyManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of(), List.of());
  }

  private static ExportedAssetManifest logoManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of("logo.png"), List.of(logoProof()));
  }

  private static PublishedArtifactDigest logoProof() {
    return new PublishedArtifactDigest(
        "logo.png",
        "BINARY",
        "artifacts/sha256/" + LOGO_DIGEST.substring("sha256:".length()),
        LOGO_DIGEST,
        "image/png",
        1);
  }
}
