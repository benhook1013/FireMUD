package net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class TestDataSeederTest {
  private static final String GAME_SESSION_URI_SAN =
      "spiffe://firemud/ns/dev/sa/game-session-service";

  @Mock GameRepository gameRepository;
  @Mock GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Mock GameTemplateRepository templateRepository;
  @Mock RevisionRepository revisionRepository;
  @Mock VersionRepository versionRepository;
  @Mock PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Mock VersionAssetArtifactRepository versionAssetArtifactRepository;
  @Mock RunOwnedFixtureLaunchReadback launchReadback;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(
            gameRepository,
            authoredWorldSourceRepository,
            templateRepository,
            revisionRepository,
            versionRepository,
            publishedReleaseBundleRepository,
            versionAssetArtifactRepository,
            launchReadback,
            new ObjectMapper());
  }

  @Test
  void runSeedsAndReassertsCanonicalPublishedRuntimeData() throws Exception {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(null);
    when(gameRepository.save(any(Game.class))).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setVersionNumber(1);
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1))
        .thenReturn(java.util.Optional.empty());
    when(versionRepository.save(any(Version.class))).thenReturn(version);
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(java.util.Optional.empty());
    GameTemplate template = new GameTemplate();
    template.setId(9L);
    template.setTenantId("1");
    when(templateRepository.save(any(GameTemplate.class))).thenReturn(template);
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(java.util.Optional.empty());
    when(revisionRepository.save(any(Revision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(publishedReleaseBundleRepository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(versionAssetArtifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(gameRepository).save(any());
    verify(templateRepository).save(any());
    verify(revisionRepository).save(any());
    verify(versionRepository).save(any());
    ArgumentCaptor<PublishedReleaseBundle> bundleCaptor =
        ArgumentCaptor.forClass(PublishedReleaseBundle.class);
    verify(publishedReleaseBundleRepository).save(bundleCaptor.capture());
    assertThat(bundleCaptor.getValue().getTenantId()).isEqualTo("1");
    assertThat(bundleCaptor.getValue().getVersionId()).isEqualTo(7L);
    assertThat(bundleCaptor.getValue().getManifestHash()).isEqualTo("demo-manifest-hash");
    assertThat(bundleCaptor.getValue().getPublishWorkflowId()).isEqualTo("demo-seed-publish");

    ArgumentCaptor<VersionAssetArtifact> artifactCaptor =
        ArgumentCaptor.forClass(VersionAssetArtifact.class);
    verify(versionAssetArtifactRepository).save(artifactCaptor.capture());
    assertThat(artifactCaptor.getValue().getTenantId()).isEqualTo("1");
    assertThat(artifactCaptor.getValue().getVersionId()).isEqualTo(7L);
    assertThat(artifactCaptor.getValue().getLastWorkflowId()).isEqualTo("demo-seed-publish");
    assertThat(artifactCaptor.getValue().getManifestHash()).isEqualTo("demo-manifest-hash");
    verify(authoredWorldSourceRepository, never())
        .register(any(), any(), any(), any(), any(), any());
  }

  @Test
  void reusesExactExistingPublishedReleaseBundleWithoutSavingItAgain() {
    PublishedReleaseBundle bundle = stubExistingSeedData();
    Long originalId = bundle.getId();
    String originalReference = bundle.getPublishedReleaseBundleRef();

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(publishedReleaseBundleRepository).findByTenantIdAndVersionId("1", 7L);
    verify(publishedReleaseBundleRepository, never()).save(any(PublishedReleaseBundle.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(templateRepository, never()).save(any(GameTemplate.class));
    verify(versionAssetArtifactRepository, never()).save(any(VersionAssetArtifact.class));
    assertThat(bundle.getId()).isEqualTo(originalId);
    assertThat(bundle.getPublishedReleaseBundleRef()).isEqualTo(originalReference);
    assertThat(bundle.getManifestHash()).isEqualTo("demo-manifest-hash");
  }

  @Test
  void rejectsExistingPublishedReleaseBundleThatDiffersFromDemoSeed() {
    PublishedReleaseBundle bundle = stubExistingSeedData();
    bundle.setManifestHash("different-manifest");

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exactly match the demo seed");

    verify(publishedReleaseBundleRepository, never()).save(any(PublishedReleaseBundle.class));
    verify(versionAssetArtifactRepository, never()).save(any(VersionAssetArtifact.class));
  }

  @Test
  void rejectsExistingVersionWithChangedStateEpochWithoutResettingIt() {
    stubExistingSeedData();
    Version version = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    version.setVersionStateEpoch(2L);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Existing published version does not exactly match the demo seed");

    assertThat(version.getVersionStateEpoch()).isEqualTo(2L);
    verify(versionRepository, never()).save(any(Version.class));
  }

  @Test
  void rejectsExistingTemplateWithChangedDefaultVersionWithoutResettingIt() {
    stubExistingSeedData();
    GameTemplate template =
        templateRepository.findByTenantIdAndName("1", "Default Template").orElseThrow();
    template.setDefaultVersionId(8L);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Existing default template does not exactly match the demo seed");

    assertThat(template.getDefaultVersionId()).isEqualTo(8L);
    verify(versionRepository, never()).save(any(Version.class));
    verify(templateRepository, never()).save(any(GameTemplate.class));
  }

  @Test
  void rejectsExistingArtifactWithChangedStateEpochWithoutResettingIt() {
    stubExistingSeedData();
    VersionAssetArtifact artifact =
        versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L).orElseThrow();
    artifact.setStateEpoch(2L);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Existing version artifact does not exactly match the demo seed");

    assertThat(artifact.getStateEpoch()).isEqualTo(2L);
    verify(versionRepository, never()).save(any(Version.class));
    verify(templateRepository, never()).save(any(GameTemplate.class));
    verify(versionAssetArtifactRepository, never()).save(any(VersionAssetArtifact.class));
  }

  @Test
  void acceptsExpectedWorkloadUriAlongsideGeneratedDnsSans() throws Exception {
    CertificateFixture fixture =
        certificateFixture(
            List.of(
                List.of(6, GAME_SESSION_URI_SAN),
                List.of(2, "game-session-service"),
                List.of(2, "game-session-service.dev"),
                List.of(2, "game-session-service.dev.svc"),
                List.of(2, "game-session-service.dev.svc.cluster.local")));

    requireCertificatePins(fixture);
  }

  @Test
  void rejectsCertificateWithoutWorkloadUriDespiteMatchingDigestPins() throws Exception {
    CertificateFixture fixture =
        certificateFixture(
            List.of(
                List.of(2, "game-session-service"),
                List.of(2, "game-session-service.dev"),
                List.of(2, "game-session-service.dev.svc"),
                List.of(2, "game-session-service.dev.svc.cluster.local")));

    assertThatThrownBy(() -> requireCertificatePins(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wrong workload URI SAN");
  }

  @Test
  void rejectsWrongNamespaceOrServiceUrisDespiteMatchingDigestPins() throws Exception {
    for (String uri :
        List.of(
            "spiffe://firemud/ns/qa/sa/game-session-service",
            "spiffe://firemud/ns/dev/sa/world-management-service")) {
      CertificateFixture fixture = certificateFixture(List.of(List.of(6, uri)));

      assertThatThrownBy(() -> requireCertificatePins(fixture))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong workload URI SAN");
    }
  }

  @Test
  void rejectsMultipleWorkloadUrisDespiteMatchingDigestPins() throws Exception {
    CertificateFixture fixture =
        certificateFixture(
            List.of(
                List.of(6, GAME_SESSION_URI_SAN),
                List.of(6, "spiffe://firemud/ns/dev/sa/world-management-service")));

    assertThatThrownBy(() -> requireCertificatePins(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wrong workload URI SAN");
  }

  @Test
  void refusesFixtureSourceRegistrationWithoutExactRunAndProjectBinding() throws Exception {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    game.setCanonicalTenantId(java.util.UUID.randomUUID());
    game.setName("Demo Game");
    game.setDescription("Seed game");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    when(gameRepository.save(any(Game.class))).thenReturn(game);
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1))
        .thenReturn(java.util.Optional.empty());
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setVersionNumber(1);
    when(versionRepository.save(any(Version.class))).thenReturn(version);
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(java.util.Optional.empty());
    when(templateRepository.save(any(GameTemplate.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(java.util.Optional.empty());
    when(revisionRepository.save(any(Revision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(publishedReleaseBundleRepository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(java.util.Optional.empty());
    when(versionAssetArtifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixture configuration is incomplete");

    verify(authoredWorldSourceRepository, never())
        .register(any(), any(), any(), any(), any(), any());
  }

  private PublishedReleaseBundle stubExistingSeedData() {
    UUID canonicalTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID canonicalVersionId = UUID.fromString("00000000-0000-0000-0000-000000000007");
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    game.setCanonicalTenantId(canonicalTenantId);
    game.setName("Demo Game");
    game.setDescription("Seed game");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    when(gameRepository.findRuntimeTenantIdentityByTenantKey("1"))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    canonicalTenantId, GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW, 1L, "1")));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setCanonicalTenantId(canonicalTenantId);
    version.setCanonicalVersionId(canonicalVersionId);
    version.setVersionNumber(1);
    version.setVersionState(
        net.firedevops.firemud.gamedesign.model.VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setNotes("Seed published version");
    version.setIdentitySourceGameRowId(1L);
    version.setIdentitySourceGameTenantKey("1");
    version.setIdentitySourceProvenanceKind("NEW_GAME_ROW");
    when(versionRepository.findByTenantIdAndVersionNumber("1", 1)).thenReturn(Optional.of(version));

    GameTemplate template = new GameTemplate();
    template.setId(9L);
    template.setTenantId("1");
    template.setName("Default Template");
    template.setDescription("Demo template");
    template.setConfig("{}");
    template.setDefaultVersionId(7L);
    template.setDefaultRuntimeFlagsJson("{}");
    when(templateRepository.findByTenantIdAndName("1", "Default Template"))
        .thenReturn(Optional.of(template));

    Revision revision = new Revision();
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKind("1", 7L, "GENERIC"))
        .thenReturn(Optional.of(revision));
    when(revisionRepository.save(any(Revision.class))).thenReturn(revision);

    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setId(23L);
    bundle.setPublishedReleaseBundleRef("seed-release-reference");
    bundle.setTenantId("1");
    bundle.setVersionId(7L);
    bundle.setCanonicalTenantId(canonicalTenantId);
    bundle.setCanonicalVersionId(canonicalVersionId);
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("demo-seed-publish");
    bundle.setManifestHash("demo-manifest-hash");
    bundle.setGenerationConfigRevision("genrev:demo");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(Optional.of(bundle));

    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setId(31L);
    artifact.setTenantId("1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(1);
    artifact.setArtifactState(
        net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(1L);
    artifact.setManifestHash("demo-manifest-hash");
    artifact.setLastWorkflowId("demo-seed-publish");
    artifact.setExportedManifestAssetKeysJson("[]");
    when(versionAssetArtifactRepository.findByTenantIdAndVersionId("1", 7L))
        .thenReturn(Optional.of(artifact));

    return bundle;
  }

  private CertificateFixture certificateFixture(List<List<?>> subjectAlternativeNames)
      throws Exception {
    byte[] leafEncoding = "fixture-game-session-leaf".getBytes(StandardCharsets.UTF_8);
    byte[] caEncoding = "fixture-ca".getBytes(StandardCharsets.UTF_8);
    X509Certificate leaf = mock(X509Certificate.class);
    X509Certificate ca = mock(X509Certificate.class);
    X500Principal issuer = new X500Principal("CN=fixture-ca");
    when(leaf.getEncoded()).thenReturn(leafEncoding);
    when(ca.getEncoded()).thenReturn(caEncoding);
    when(leaf.getIssuerX500Principal()).thenReturn(issuer);
    when(ca.getSubjectX500Principal()).thenReturn(issuer);
    when(ca.getBasicConstraints()).thenReturn(0);
    when(ca.getPublicKey()).thenReturn(mock(PublicKey.class));
    when(leaf.getSubjectAlternativeNames()).thenReturn(subjectAlternativeNames);
    return new CertificateFixture(
        leaf, ca, certificateDigest(leafEncoding), certificateDigest(caEncoding));
  }

  private void requireCertificatePins(CertificateFixture fixture) {
    ReflectionTestUtils.invokeMethod(
        seeder,
        "requireCertificatePins",
        fixture.leaf(),
        fixture.ca(),
        fixture.leafDigest(),
        fixture.caDigest());
  }

  private String certificateDigest(byte[] encoding) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoding));
  }

  private record CertificateFixture(
      X509Certificate leaf, X509Certificate ca, String leafDigest, String caDigest) {}
}
