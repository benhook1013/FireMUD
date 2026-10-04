package net.firedevops.firemud.gamedesign.service.impl;

import static net.firedevops.firemud.gamedesign.service.impl.CommandDefinitionFixtures.commandDefinition;
import static net.firedevops.firemud.gamedesign.service.impl.CommandDefinitionFixtures.validCommandDefinition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedRealmEntryPolicy;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.PublishedRealmEntryPolicyRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class PublishedReleaseBundleServiceImplTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("87426bb3-a733-43f0-9c8e-2e379cbdf7ec");
  private static final UUID POLICY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String VALID_REALM_ENTRY_POLICY =
      "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
          + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Realm\","
          + "\"visible\":true,\"publicProduction\":false,\"stateScope\":\"SHARED\","
          + "\"entryPolicy\":\"PRESEEDED_ONLY\"}";

  @Mock private PublishedReleaseBundleRepository repository;
  @Mock private PublishedRealmEntryPolicyRepository realmEntryPolicyRepository;
  @Mock private GameRepository gameRepository;
  @Mock private RevisionRepository revisionRepository;
  @Mock private VersionRepository versionRepository;

  private PublishedReleaseBundleServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new PublishedReleaseBundleServiceImpl(
            repository,
            revisionRepository,
            realmEntryPolicyRepository,
            gameRepository,
            versionRepository,
            new ObjectMapper());
  }

  @Test
  void createFullVersionBundlePersistsImmutableAttestation() {
    VersionDto version =
        new VersionDto(
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision commandDefinition = new Revision();
    commandDefinition.setData(validCommandDefinition());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(commandDefinition));
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle entity = invocation.getArgument(0);
              entity.setId(11L);
              return entity;
            });

    var dto =
        service.createFullVersionBundle(
            version,
            "workflow-1",
            new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json")),
            "genrev-1",
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-1", 1, null, null)));

    assertEquals(11L, dto.id());
    assertEquals("tenant-1", dto.tenantId());
    assertEquals(7L, dto.versionId());
    assertEquals("abc123", dto.manifestHash());
    assertEquals("genrev-1", dto.generationConfigRevision());
    assertEquals(List.of("logo.png", "manifest.json"), dto.requiredManifestAssetKeys());
    assertEquals(1, dto.participantDigests().size());
    assertEquals(List.of(validCommandDefinition()), dto.commandDefinitions());
    assertEquals("v1", dto.attestationSchemaVersion());
  }

  @Test
  void createFullVersionBundleFreezesPolicyWithExactOwnerAndSourceEvidence() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, RealmEntryPolicy.REVISION_KIND))
        .thenReturn(List.of(policyRevision(21L, "tenant-1", 7L, VALID_REALM_ENTRY_POLICY)));
    when(gameRepository.findRuntimeTenantIdentityByTenantKey("tenant-1"))
        .thenReturn(Optional.of(tenantIdentity("tenant-1")));
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle entity = invocation.getArgument(0);
              entity.setId(11L);
              return entity;
            });

    service.createFullVersionBundle(
        version(),
        "workflow-1",
        new ExportedAssetManifest("abc123", List.of("manifest.json")),
        "genrev-1",
        List.of());

    org.mockito.ArgumentCaptor<PublishedRealmEntryPolicy> policyCaptor =
        org.mockito.ArgumentCaptor.forClass(PublishedRealmEntryPolicy.class);
    verify(realmEntryPolicyRepository).insert(policyCaptor.capture());
    PublishedRealmEntryPolicy frozen = policyCaptor.getValue();
    assertEquals(CANONICAL_TENANT_ID, frozen.canonicalTenantId());
    assertEquals("NEW_GAME_ROW", frozen.tenantIdentityProvenanceKind());
    assertEquals(42L, frozen.sourceGameRowId());
    assertEquals("tenant-1", frozen.sourceGameTenantKey());
    assertEquals(7L, frozen.versionId());
    assertEquals(8, frozen.versionNumber());
    assertEquals(11L, frozen.releaseBundleId());
    assertEquals(21L, frozen.sourceRevisionId());
    assertEquals(
        RealmEntryPolicy.parse(VALID_REALM_ENTRY_POLICY, new ObjectMapper()).canonicalJson(),
        frozen.policyJson());
    assertEquals("sha256:", frozen.policyDigest().substring(0, 7));
  }

  @Test
  void createFullVersionBundlePreservesLegacyPublicationWhenNoPolicyExists() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(repository.save(any(PublishedReleaseBundle.class)))
        .thenAnswer(
            invocation -> {
              PublishedReleaseBundle entity = invocation.getArgument(0);
              entity.setId(11L);
              return entity;
            });

    var result =
        service.createFullVersionBundle(
            version(),
            "workflow-1",
            new ExportedAssetManifest("abc123", List.of("manifest.json")),
            "genrev-1",
            List.of());

    assertEquals(11L, result.id());
    verify(realmEntryPolicyRepository, never()).insert(any(PublishedRealmEntryPolicy.class));
    verify(gameRepository, never()).findRuntimeTenantIdentityByTenantKey("tenant-1");
  }

  @Test
  void createFullVersionBundleRejectsWrongTenantVersionSourceAndDuplicateSelectors() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, RealmEntryPolicy.REVISION_KIND))
        .thenReturn(List.of(policyRevision(21L, "tenant-2", 7L, VALID_REALM_ENTRY_POLICY)));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(),
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
    verify(repository, never()).save(any(PublishedReleaseBundle.class));

    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, RealmEntryPolicy.REVISION_KIND))
        .thenReturn(
            List.of(
                policyRevision(21L, "tenant-1", 7L, VALID_REALM_ENTRY_POLICY),
                policyRevision(22L, "tenant-1", 7L, VALID_REALM_ENTRY_POLICY)));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(),
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
    verify(gameRepository, never()).findRuntimeTenantIdentityByTenantKey("tenant-1");
  }

  @Test
  void publishedPolicyReadRequiresPublishedBundleAndVerifiesFrozenEvidence() {
    PublishedReleaseBundle bundle = publishedBundle();
    Revision source = policyRevision(21L, "tenant-1", 7L, VALID_REALM_ENTRY_POLICY);
    RealmEntryPolicy policy = RealmEntryPolicy.parse(VALID_REALM_ENTRY_POLICY, new ObjectMapper());
    String bundleIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, 7L, "workflow-1", "abc123", new ObjectMapper());
    PublishedRealmEntryPolicyEvidence evidence =
        PublishedRealmEntryPolicyEvidence.create(
            POLICY_ID,
            CANONICAL_TENANT_ID,
            "NEW_GAME_ROW",
            42L,
            "tenant-1",
            7L,
            8,
            21L,
            bundleIdentity,
            "workflow-1",
            "abc123",
            policy,
            new ObjectMapper());
    when(gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(CANONICAL_TENANT_ID))
        .thenReturn(Optional.of(tenantIdentity("tenant-1")));
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L))
        .thenReturn(Optional.of(publishedVersion()));
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(bundle));
    when(realmEntryPolicyRepository.findByScope(CANONICAL_TENANT_ID, 7L, "earth", "main"))
        .thenReturn(List.of(storedPolicy(evidence, bundle.getId())));
    when(revisionRepository.findById(21L)).thenReturn(source);

    var resolved =
        service.resolvePublishedRealmEntryPolicy(CANONICAL_TENANT_ID, 7L, "earth", "main");

    assertEquals(POLICY_ID, resolved.policyId());
    assertEquals(
        RealmEntryPolicy.parse(VALID_REALM_ENTRY_POLICY, new ObjectMapper()).canonicalJson(),
        resolved.policy().canonicalJson());
    assertEquals(bundleIdentity, resolved.releaseBundleIdentity());
    assertEquals(evidence.policyDigest(), resolved.policyDigest());
  }

  @Test
  void publishedPolicyReadDeniesLegacyBundleAndContradictoryEvidence() {
    when(gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(CANONICAL_TENANT_ID))
        .thenReturn(Optional.of(tenantIdentity("tenant-1")));
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L))
        .thenReturn(Optional.of(publishedVersion()));
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.of(publishedBundle()));
    when(realmEntryPolicyRepository.findByScope(CANONICAL_TENANT_ID, 7L, "earth", "main"))
        .thenReturn(List.of());
    assertThrows(
        PublishedRealmEntryPolicyNotFoundException.class,
        () -> service.resolvePublishedRealmEntryPolicy(CANONICAL_TENANT_ID, 7L, "earth", "main"));

    RealmEntryPolicy policy = RealmEntryPolicy.parse(VALID_REALM_ENTRY_POLICY, new ObjectMapper());
    var bundleIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, 7L, "workflow-1", "abc123", new ObjectMapper());
    var validEvidence =
        PublishedRealmEntryPolicyEvidence.create(
            POLICY_ID,
            CANONICAL_TENANT_ID,
            "NEW_GAME_ROW",
            42L,
            "tenant-1",
            7L,
            8,
            21L,
            bundleIdentity,
            "workflow-1",
            "abc123",
            policy,
            new ObjectMapper());
    PublishedRealmEntryPolicy contradictory =
        storedPolicy(validEvidence, 11L, "sha256:" + "0".repeat(64));
    when(realmEntryPolicyRepository.findByScope(CANONICAL_TENANT_ID, 7L, "earth", "main"))
        .thenReturn(List.of(contradictory));
    when(revisionRepository.findById(21L))
        .thenReturn(policyRevision(21L, "tenant-1", 7L, VALID_REALM_ENTRY_POLICY));

    assertThrows(
        IllegalStateException.class,
        () -> service.resolvePublishedRealmEntryPolicy(CANONICAL_TENANT_ID, 7L, "earth", "main"));
  }

  @Test
  void completePolicySetReadReturnsAllRowsInCanonicalOrderWithStableOwnerDigest() {
    stubPolicyOwnerRead();
    PublishedRealmEntryPolicyEvidence main = policyEvidence("main", true, true, 21L);
    PublishedRealmEntryPolicyEvidence side = policyEvidence("side", false, false, 22L);
    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L))
        .thenReturn(List.of(storedPolicy(side, 11L), storedPolicy(main, 11L)));
    when(revisionRepository.findById(21L))
        .thenReturn(policyRevision(21L, "tenant-1", 7L, main.policy().canonicalJson()));
    when(revisionRepository.findById(22L))
        .thenReturn(policyRevision(22L, "tenant-1", 7L, side.policy().canonicalJson()));

    PublishedRealmEntryPolicySetEvidence set =
        service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L);

    assertEquals(2, set.policies().size());
    assertEquals("main", set.policies().getFirst().policy().realmSlug());
    assertEquals("side", set.policies().get(1).policy().realmSlug());
    assertEquals(true, set.policies().getFirst().policy().publicProduction());
    assertEquals(true, set.hasValidDigest(new ObjectMapper()));
    assertEquals(21L, set.policies().getFirst().sourceRevisionId());
  }

  @Test
  void completePolicySetReadRejectsMissingDuplicateInvalidCardinalityAndContradictoryRows() {
    stubPolicyOwnerRead();
    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L)).thenReturn(List.of());
    assertThrows(
        PublishedRealmEntryPolicyNotFoundException.class,
        () -> service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L));

    PublishedRealmEntryPolicyEvidence hidden = policyEvidence("hidden", false, false, 23L);
    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L))
        .thenReturn(List.of(storedPolicy(hidden, 11L)));
    when(revisionRepository.findById(23L))
        .thenReturn(policyRevision(23L, "tenant-1", 7L, hidden.policy().canonicalJson()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L));

    PublishedRealmEntryPolicyEvidence first = policyEvidence("main", true, true, 24L);
    PublishedRealmEntryPolicyEvidence duplicate = policyEvidence("main", true, true, 25L);
    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L))
        .thenReturn(List.of(storedPolicy(first, 11L), storedPolicy(duplicate, 11L)));
    when(revisionRepository.findById(24L))
        .thenReturn(policyRevision(24L, "tenant-1", 7L, first.policy().canonicalJson()));
    when(revisionRepository.findById(25L))
        .thenReturn(policyRevision(25L, "tenant-1", 7L, duplicate.policy().canonicalJson()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L));

    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L))
        .thenReturn(List.of(storedPolicy(first, 12L)));
    assertThrows(
        IllegalStateException.class,
        () -> service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L));
  }

  @Test
  void completePolicySetReadRejectsOverLimitInsteadOfReturningATruncatedPrefix() {
    stubPolicyOwnerRead();
    PublishedRealmEntryPolicyEvidence main = policyEvidence("main", true, true, 21L);
    when(realmEntryPolicyRepository.findByOwner(CANONICAL_TENANT_ID, 7L))
        .thenReturn(
            java.util.Collections.nCopies(
                PublishedRealmEntryPolicySetEvidence.MAX_POLICIES + 1, storedPolicy(main, 11L)));

    assertThrows(
        IllegalStateException.class,
        () -> service.listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID, 7L));
    verify(revisionRepository, never()).findById(21L);
  }

  @Test
  void createFullVersionBundleRejectsDuplicateAttestation() {
    VersionDto version =
        new VersionDto(
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.of(new PublishedReleaseBundle()));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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
  void createFullVersionBundleRejectsDuplicateCommandDefinitionAlias() {
    VersionDto version =
        new VersionDto(
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
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "hail"));
    Revision second = new Revision();
    second.setData(commandDefinition("greet", "HAIL"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
  }

  @Test
  void createFullVersionBundleRejectsDuplicateCanonicalCommandIds() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "hail"));
    Revision second = new Revision();
    second.setData(commandDefinition("SALUTE", "greet"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(),
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
  }

  @Test
  void createFullVersionBundleRejectsCanonicalIdAndAliasCollisions() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision first = new Revision();
    first.setData(commandDefinition("salute", "greet"));
    Revision second = new Revision();
    second.setData(commandDefinition("hail", "SALUTE"));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(first, second));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createFullVersionBundle(
                version(),
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
  }

  @Test
  void createFullVersionBundleRejectsMalformedCommandEffectDeclaration() {
    VersionDto version = version();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    Revision commandDefinition = new Revision();
    commandDefinition.setData(validCommandDefinition().replace("\"value\":1", "\"value\":\"one\""));
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(commandDefinition));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.createFullVersionBundle(
                version,
                "workflow-1",
                new ExportedAssetManifest("abc123", List.of("manifest.json")),
                "genrev-1",
                List.of()));
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

  private static Revision policyRevision(
      long revisionId, String tenantId, long versionId, String data) {
    Revision revision = new Revision();
    revision.setId(revisionId);
    revision.setTenantId(tenantId);
    revision.setVersionId(versionId);
    revision.setRevisionKind(RealmEntryPolicy.REVISION_KIND);
    revision.setData(data);
    return revision;
  }

  private static GameTenantIdentity tenantIdentity(String tenantKey) {
    return new GameTenantIdentity(
        CANONICAL_TENANT_ID, GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW, 42L, tenantKey);
  }

  private static net.firedevops.firemud.gamedesign.entity.Version publishedVersion() {
    net.firedevops.firemud.gamedesign.entity.Version version =
        new net.firedevops.firemud.gamedesign.entity.Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    return version;
  }

  private static PublishedReleaseBundle publishedBundle() {
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setId(11L);
    bundle.setTenantId("tenant-1");
    bundle.setVersionId(7L);
    bundle.setVersionNumber(8);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("workflow-1");
    bundle.setManifestHash("abc123");
    return bundle;
  }

  private static PublishedRealmEntryPolicy storedPolicy(
      PublishedRealmEntryPolicyEvidence evidence, long bundleId) {
    return storedPolicy(evidence, bundleId, evidence.policyDigest());
  }

  private static PublishedRealmEntryPolicy storedPolicy(
      PublishedRealmEntryPolicyEvidence evidence, long bundleId, String digest) {
    RealmEntryPolicy policy = evidence.policy();
    return new PublishedRealmEntryPolicy(
        evidence.policyId(),
        evidence.canonicalTenantId(),
        evidence.tenantIdentityProvenanceKind(),
        evidence.sourceGameRowId(),
        evidence.sourceGameTenantKey(),
        evidence.versionId(),
        evidence.versionNumber(),
        bundleId,
        evidence.sourceRevisionId(),
        evidence.releaseBundleIdentity(),
        evidence.publishWorkflowId(),
        evidence.manifestHash(),
        policy.worldSlug(),
        policy.worldDisplayName(),
        policy.realmSlug(),
        policy.realmDisplayName(),
        policy.visible(),
        policy.publicProduction(),
        policy.stateScope().name(),
        policy.entryPolicy().name(),
        policy.canonicalJson(),
        digest);
  }

  private void stubPolicyOwnerRead() {
    when(gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(CANONICAL_TENANT_ID))
        .thenReturn(Optional.of(tenantIdentity("tenant-1")));
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L))
        .thenReturn(Optional.of(publishedVersion()));
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.of(publishedBundle()));
  }

  private static PublishedRealmEntryPolicyEvidence policyEvidence(
      String realmSlug, boolean visible, boolean publicProduction, long sourceRevisionId) {
    ObjectMapper objectMapper = new ObjectMapper();
    String json =
        "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
            + "\"realmSlug\":\""
            + realmSlug
            + "\",\"realmDisplayName\":\""
            + realmSlug
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + publicProduction
            + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    RealmEntryPolicy policy = RealmEntryPolicy.parse(json, objectMapper);
    return PublishedRealmEntryPolicyEvidence.create(
        UUID.nameUUIDFromBytes(realmSlug.concat(Long.toString(sourceRevisionId)).getBytes()),
        CANONICAL_TENANT_ID,
        "NEW_GAME_ROW",
        42L,
        "tenant-1",
        7L,
        8,
        sourceRevisionId,
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, 7L, "workflow-1", "abc123", objectMapper),
        "workflow-1",
        "abc123",
        policy,
        objectMapper);
  }
}
