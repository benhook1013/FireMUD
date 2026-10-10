package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository.AuthoredWorldVersionStateSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

class CompleteLaunchBindingServiceImplTest {
  @Test
  void selectorReleaseIsAssembledFromImmutableLocalBundleWithoutMutableOwnerReads()
      throws Exception {
    var selector =
        PublishedWorldSelectorFixtures.evidence(
            new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
                CANONICAL_TENANT_ID,
                CANONICAL_VERSION_ID,
                VERSION_ID,
                PRIVATE_TENANT_KEY,
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()));
    var selected =
        new PublishedReleaseBundleDto(
            bundle.id(),
            bundle.tenantId(),
            bundle.versionId(),
            bundle.versionNumber(),
            "v2",
            selector.request().publishWorkflowId(),
            bundle.manifestHash(),
            bundle.requiredManifestAssetKeys(),
            PublishedWorldSelectorFixtures.participants(VERSION_ID, selector),
            bundle.commandDefinitions(),
            bundle.generationConfigRevision(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion(),
            bundle.publishedAt(),
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.publishedReleaseBundleRef(),
            bundle.manifestSchemaVersion(),
            bundle.artifactDigests(),
            selector);
    givenBundle(selected);
    var result =
        service.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());
    assertEquals(2, result.releaseAttestation().schemaVersion());
    assertEquals(selector, result.releaseAttestation().worldStartLocationEvidence());
    result.releaseAttestation().requireValid(descriptor);
    verifyReadOnlyCallsOnly();
    var obsolete = new java.util.ArrayList<>(selected.participantDigests());
    var gd = obsolete.getLast();
    obsolete.set(
        obsolete.size() - 1,
        new PublishParticipantDigestDto(
            gd.participantKey(),
            gd.scopeValue(),
            gd.baseVersionId(),
            gd.appliedCommitId(),
            gd.contentDigest(),
            1,
            gd.abilitySchemaDigest(),
            gd.errorCode(),
            gd.errorMessage()));
    givenBundle(
        new PublishedReleaseBundleDto(
            selected.id(),
            selected.tenantId(),
            selected.versionId(),
            selected.versionNumber(),
            selected.attestationSchemaVersion(),
            selected.publishWorkflowId(),
            selected.manifestHash(),
            selected.requiredManifestAssetKeys(),
            obsolete,
            selected.commandDefinitions(),
            selected.generationConfigRevision(),
            selected.scriptOnly(),
            selected.scriptPatchVersion(),
            selected.publishedAt(),
            selected.canonicalTenantId(),
            selected.canonicalVersionId(),
            selected.publishedReleaseBundleRef(),
            selected.manifestSchemaVersion(),
            selected.artifactDigests(),
            selector));
    var rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.getCompleteLaunchBinding(
                    READ_REQUEST_ID,
                    CANONICAL_TENANT_ID,
                    WORLD_SLUG,
                    CONTROL_PLANE_REQUEST_ID,
                    descriptor.requestDigest(),
                    descriptor.resultDigest()));
    assertTrue(rejected.getMessage().contains("PARTICIPANT_EVIDENCE_INVALID"));
  }

  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("72345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_REGISTRATION_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final String WORLD_SLUG = "silver-march";
  private static final String PRIVATE_TENANT_KEY = "game-owner-key-901";
  private static final long VERSION_ID = 7L;
  private static final int VERSION_NUMBER = 12;
  private static final long BUNDLE_ID = 211L;
  private static final String CONTROL_PLANE_REQUEST_ID = "launch-request-901";
  private static final String BUNDLE_REF = "opaque-release-reference-from-owner";
  private static final String WORKFLOW_ID =
      "publish:game-owner-key-901:publish-request:publish-901";
  private static final String MANIFEST_HASH = "sha256:" + "c".repeat(64);
  private static final String CONTENT_DIGEST = "d".repeat(64);
  private static final String ABILITY_SCHEMA_DIGEST = "sha256:" + "e".repeat(64);
  private static final String ARTIFACT_DIGEST = "sha256:" + "f".repeat(64);

  @Mock private LaunchDescriptorService launchDescriptorService;
  @Mock private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;
  @Mock private VersionRepository versionRepository;

  private CompleteLaunchBindingServiceImpl service;
  private AuthoredWorldSourceEvidence source;
  private AuthoredWorldLaunchDescriptorEvidence descriptor;
  private ResolvedLaunchDescriptorDto resolved;
  private PublishedReleaseBundleDto bundle;
  private Version version;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    source = sourceEvidence(WORLD_SLUG);
    descriptor = descriptorEvidence(source);
    resolved = resolvedDescriptor(descriptor);
    bundle = validBundle();
    version = canonicalVersion();
    service =
        new CompleteLaunchBindingServiceImpl(
            launchDescriptorService,
            authoredWorldSourceRepository,
            publishedReleaseBundleService,
            versionRepository);
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);
    givenValidSnapshot();
  }

  @Test
  void returnsImmutableFullBindingUsingTheDescriptorEpochAfterCurrentVersionStateAdvances() {
    var first =
        service.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());
    version.setVersionState(VersionLifecycleState.RETIRED);
    version.setVersionStateEpoch(44L);
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source, CANONICAL_VERSION_ID, VersionLifecycleState.RETIRED, 44L)));

    var later =
        service.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());

    assertEquals(descriptor, first.descriptor());
    assertEquals(descriptor.versionStateEpoch(), first.releaseAttestation().versionStateEpoch());
    assertEquals(first.releaseAttestation(), later.releaseAttestation());
    assertEquals(
        first.releaseAttestation().evidenceDigest(), later.releaseAttestation().evidenceDigest());
    verifyReadOnlyCallsOnly();
  }

  @Test
  void selectedFullV4AssemblesExactParticipantProfileFromImmutableBundle() throws Exception {
    var selector =
        PublishedWorldSelectorFixtures.closureEvidence(
            new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
                CANONICAL_TENANT_ID,
                CANONICAL_VERSION_ID,
                VERSION_ID,
                PRIVATE_TENANT_KEY,
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()));
    var selected =
        new PublishedReleaseBundleDto(
            bundle.id(),
            bundle.tenantId(),
            bundle.versionId(),
            bundle.versionNumber(),
            "v4",
            selector.request().publishWorkflowId(),
            bundle.manifestHash(),
            bundle.requiredManifestAssetKeys(),
            PublishedWorldSelectorFixtures.selectedFullParticipants(VERSION_ID, selector),
            bundle.commandDefinitions(),
            bundle.generationConfigRevision(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion(),
            bundle.publishedAt(),
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.publishedReleaseBundleRef(),
            bundle.manifestSchemaVersion(),
            bundle.artifactDigests(),
            selector);
    givenBundle(selected);
    var result =
        service.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());
    assertEquals(4, result.releaseAttestation().schemaVersion());
    assertThat(
            result.releaseAttestation().participantDigests().stream()
                .map(participant -> participant.digestSchemaVersion())
                .toList())
        .containsExactly(4, 3, 1, 6, 2);
    assertEquals(selector, result.releaseAttestation().worldStartLocationEvidence());
    result.releaseAttestation().requireValid(descriptor);
    verifyReadOnlyCallsOnly();
  }

  @Test
  void closureQualifiedSelectorV3AssemblesWorld4ParticipantFromImmutableBundle() throws Exception {
    var selector =
        PublishedWorldSelectorFixtures.closureEvidence(
            new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
                CANONICAL_TENANT_ID,
                CANONICAL_VERSION_ID,
                VERSION_ID,
                PRIVATE_TENANT_KEY,
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()));
    var selected =
        new PublishedReleaseBundleDto(
            bundle.id(),
            bundle.tenantId(),
            bundle.versionId(),
            bundle.versionNumber(),
            "v3",
            selector.request().publishWorkflowId(),
            bundle.manifestHash(),
            bundle.requiredManifestAssetKeys(),
            PublishedWorldSelectorFixtures.participants(VERSION_ID, selector),
            bundle.commandDefinitions(),
            bundle.generationConfigRevision(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion(),
            bundle.publishedAt(),
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.publishedReleaseBundleRef(),
            bundle.manifestSchemaVersion(),
            bundle.artifactDigests(),
            selector);
    givenBundle(selected);

    var result =
        service.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());

    assertEquals(3, result.releaseAttestation().schemaVersion());
    assertEquals(selector, result.releaseAttestation().worldStartLocationEvidence());
    assertEquals(
        4, result.releaseAttestation().participantDigests().getFirst().digestSchemaVersion());
    result.releaseAttestation().requireValid(descriptor);
    verifyReadOnlyCallsOnly();
  }

  @Test
  void rejectsMissingOrSubstitutedSourceEvidence() {
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(Optional.empty());
    assertDenied();

    AuthoredWorldSourceEvidence substituted = sourceEvidence("other-world");
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    substituted, CANONICAL_VERSION_ID, VersionLifecycleState.PUBLISHED, 17L)));
    assertDenied();
  }

  @Test
  void rejectsMissingOrSubstitutedReleaseIdentityAndReference() {
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_TENANT_KEY, VERSION_ID))
        .thenThrow(new PublishedReleaseBundleNotFoundException(PRIVATE_TENANT_KEY, VERSION_ID));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID + 1,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            bundle.publishedReleaseBundleRef(),
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            "substituted-reference",
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            "other-private-key",
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID + 1,
            VERSION_NUMBER,
            BUNDLE_REF,
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER + 1,
            BUNDLE_REF,
            bundle.canonicalTenantId(),
            bundle.canonicalVersionId(),
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();
  }

  @Test
  void rejectsMissingCanonicalVersionOrContradictoryPersistedGameProvenance() {
    when(versionRepository.findByCanonicalTenantIdAndCanonicalVersionId(
            CANONICAL_TENANT_ID, CANONICAL_VERSION_ID))
        .thenReturn(Optional.empty());
    assertDenied();

    version.setIdentitySourceGameRowId(74L);
    when(versionRepository.findByCanonicalTenantIdAndCanonicalVersionId(
            CANONICAL_TENANT_ID, CANONICAL_VERSION_ID))
        .thenReturn(Optional.of(version));
    assertDenied();
  }

  @Test
  void rejectsCanonicalTenantOrVersionUuidSubstitution() {
    UUID otherCanonicalTenant = UUID.fromString("92345678-1234-4234-8234-123456789abc");
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            otherCanonicalTenant,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    UUID otherCanonicalVersion = UUID.fromString("a2345678-1234-4234-8234-123456789abc");
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            otherCanonicalVersion,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();
  }

  @Test
  void requiresOneConsistentOwnerSnapshotAndMonotonicHistoricalEpoch() {
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source, CANONICAL_VERSION_ID, VersionLifecycleState.PUBLISHED, 16L)));
    assertDenied();

    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source, CANONICAL_VERSION_ID, VersionLifecycleState.RETIRED, 23L)));
    assertDenied();
  }

  @Test
  void rejectsReleaseVersionUuidThatDiffersFromTheSameSourceSnapshot() {
    UUID differentVersionId = UUID.fromString("b2345678-1234-4234-8234-123456789abc");
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source, differentVersionId, VersionLifecycleState.PUBLISHED, 23L)));

    assertDenied();
    verify(versionRepository, never())
        .findByCanonicalTenantIdAndCanonicalVersionId(CANONICAL_TENANT_ID, CANONICAL_VERSION_ID);
  }

  @Test
  void rejectsAbsentOrNilCanonicalVersionUuidWithoutNumericInference() {
    UUID nil = new UUID(0L, 0L);
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            nil,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            bundle.scriptOnly(),
            bundle.scriptPatchVersion()));
    assertDenied();
    verify(versionRepository, never())
        .findByCanonicalTenantIdAndCanonicalVersionId(CANONICAL_TENANT_ID, nil);
  }

  @Test
  void rejectsScriptOnlyVersionMetadata() {
    version.setScriptOnly(true);
    when(versionRepository.findByCanonicalTenantIdAndCanonicalVersionId(
            CANONICAL_TENANT_ID, CANONICAL_VERSION_ID))
        .thenReturn(Optional.of(version));
    assertDenied();

    version.setScriptOnly(false);
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            true,
            "patch-1"));
    assertDenied();
  }

  @Test
  void rejectsMissingOrContradictoryManifestAndArtifactProof() {
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            null,
            bundle.manifestHash(),
            null,
            false,
            null));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            "not-a-digest",
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            List.of("world.navmesh"),
            bundle.participantDigests(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            List.of(),
            false,
            null));
    assertDenied();
  }

  @Test
  void rejectsMissingFailedUnsupportedAndMisorderedParticipantEvidence() {
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            List.of(),
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    List<PublishParticipantDigestDto> unsupported =
        new java.util.ArrayList<>(bundle.participantDigests());
    unsupported.set(
        0,
        new PublishParticipantDigestDto(
            PublishParticipantKey.WORLD_MANAGEMENT.name(),
            Long.toString(VERSION_ID),
            null,
            "commit-1",
            CONTENT_DIGEST,
            99,
            null,
            null,
            null));
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            unsupported,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    List<PublishParticipantDigestDto> failed =
        new java.util.ArrayList<>(bundle.participantDigests());
    failed.set(
        0,
        new PublishParticipantDigestDto(
            PublishParticipantKey.WORLD_MANAGEMENT.name(),
            Long.toString(VERSION_ID),
            null,
            "commit-1",
            CONTENT_DIGEST,
            2,
            null,
            "OWNER_FAILED",
            "owner failed"));
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            failed,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    List<PublishParticipantDigestDto> misordered =
        new java.util.ArrayList<>(bundle.participantDigests());
    java.util.Collections.swap(misordered, 0, 1);
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            misordered,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();
  }

  @Test
  void requiresGameLogicOwnedAbilityProofAndOneSharedParticipantCommit() {
    List<PublishParticipantDigestDto> missingAbility =
        new java.util.ArrayList<>(bundle.participantDigests());
    missingAbility.set(
        2,
        new PublishParticipantDigestDto(
            PublishParticipantKey.GAME_LOGIC.name(),
            Long.toString(VERSION_ID),
            null,
            "commit-1",
            CONTENT_DIGEST,
            1,
            null,
            null,
            null));
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            missingAbility,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    List<PublishParticipantDigestDto> mismatchedCommit =
        new java.util.ArrayList<>(bundle.participantDigests());
    mismatchedCommit.set(
        4,
        new PublishParticipantDigestDto(
            PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE.name(),
            Long.toString(VERSION_ID),
            null,
            "different-commit",
            CONTENT_DIGEST,
            1,
            null,
            null,
            null));
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            mismatchedCommit,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();

    List<PublishParticipantDigestDto> wrongAbilityOwner =
        new java.util.ArrayList<>(bundle.participantDigests());
    wrongAbilityOwner.set(
        0,
        new PublishParticipantDigestDto(
            PublishParticipantKey.WORLD_MANAGEMENT.name(),
            Long.toString(VERSION_ID),
            null,
            "commit-1",
            CONTENT_DIGEST,
            2,
            ABILITY_SCHEMA_DIGEST,
            null,
            null));
    givenBundle(
        copyBundle(
            bundle,
            BUNDLE_ID,
            PRIVATE_TENANT_KEY,
            VERSION_ID,
            VERSION_NUMBER,
            BUNDLE_REF,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            bundle.requiredManifestAssetKeys(),
            wrongAbilityOwner,
            bundle.manifestSchemaVersion(),
            bundle.manifestHash(),
            bundle.artifactDigests(),
            false,
            null));
    assertDenied();
  }

  private void givenValidSnapshot() {
    when(launchDescriptorService.getLaunchDescriptorInOwnerSnapshot(
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest()))
        .thenReturn(resolved);
    when(authoredWorldSourceRepository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            VERSION_ID))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source, CANONICAL_VERSION_ID, VersionLifecycleState.PUBLISHED, 23L)));
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_TENANT_KEY, VERSION_ID))
        .thenReturn(bundle);
    when(versionRepository.findByCanonicalTenantIdAndCanonicalVersionId(
            CANONICAL_TENANT_ID, CANONICAL_VERSION_ID))
        .thenReturn(Optional.of(version));
  }

  private void givenBundle(PublishedReleaseBundleDto replacement) {
    doReturn(replacement)
        .when(publishedReleaseBundleService)
        .getPublishedReleaseBundle(PRIVATE_TENANT_KEY, VERSION_ID);
  }

  private void assertDenied() {
    IllegalArgumentException denied =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.getCompleteLaunchBinding(
                    READ_REQUEST_ID,
                    CANONICAL_TENANT_ID,
                    WORLD_SLUG,
                    CONTROL_PLANE_REQUEST_ID,
                    descriptor.requestDigest(),
                    descriptor.resultDigest()));
    assertTrue(
        denied.getMessage().startsWith("COMPLETE_LAUNCH_BINDING_")
            || denied.getMessage().startsWith("LAUNCH_DESCRIPTOR_")
            || denied.getMessage().startsWith("AUTHORED_WORLD_SOURCE_"));
    verifyReadOnlyCallsOnly();
  }

  private void verifyReadOnlyCallsOnly() {
    verify(launchDescriptorService, never()).resolveLaunchDescriptor(any());
    verify(authoredWorldSourceRepository, never())
        .register(
            anyString(), any(UUID.class), any(UUID.class), anyString(), anyString(), anyString());
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), anyString(), any(), anyString(), any());
    verify(versionRepository, never()).save(any(Version.class));
    verify(versionRepository, never()).delete(any(Version.class));
  }

  private AuthoredWorldSourceEvidence sourceEvidence(String worldSlug) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_ID,
            CANONICAL_TENANT_ID,
            "silver-tenant",
            worldSlug,
            "Silver March");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            CANONICAL_TENANT_ID,
            "silver-tenant",
            worldSlug,
            "Silver March",
            73L,
            PRIVATE_TENANT_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        SOURCE_REGISTRATION_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        CANONICAL_TENANT_ID,
        "silver-tenant",
        worldSlug,
        "Silver March",
        73L,
        PRIVATE_TENANT_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private AuthoredWorldLaunchDescriptorEvidence descriptorEvidence(
      AuthoredWorldSourceEvidence sourceEvidence) {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            CONTROL_PLANE_REQUEST_ID,
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidence.evidenceDigest(),
            9L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "ld-71234567-1234-4234-8234-123456789abc",
        VERSION_ID,
        false,
        null,
        "{}",
        "generation-config-12",
        17L,
        BUNDLE_ID,
        BUNDLE_REF,
        false,
        null);
  }

  private ResolvedLaunchDescriptorDto resolvedDescriptor(
      AuthoredWorldLaunchDescriptorEvidence evidence) {
    return new ResolvedLaunchDescriptorDto(
        evidence.launchDescriptorId(),
        evidence.canonicalTenantId().toString(),
        evidence.gameTemplateId(),
        evidence.controlPlaneRequestId(),
        evidence.versionId(),
        evidence.scriptPatchVersion(),
        evidence.runtimeFlagsJson(),
        evidence.generationConfigRevision(),
        evidence.versionStateEpoch(),
        evidence.releaseBundleId(),
        evidence.publishedReleaseBundleRef(),
        evidence.remapSetId(),
        evidence);
  }

  private PublishedReleaseBundleDto validBundle() {
    String artifactObjectKey = "artifacts/sha256/" + ARTIFACT_DIGEST.substring("sha256:".length());
    return new PublishedReleaseBundleDto(
        BUNDLE_ID,
        PRIVATE_TENANT_KEY,
        VERSION_ID,
        VERSION_NUMBER,
        "v1",
        WORKFLOW_ID,
        MANIFEST_HASH,
        List.of("world.navmesh"),
        validParticipants(),
        List.of("{\"commandId\":\"look\"}"),
        "generation-config-12",
        false,
        null,
        null,
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        BUNDLE_REF,
        1,
        List.of(
            new PublishedArtifactDigest(
                "world.navmesh",
                "world-navmesh",
                artifactObjectKey,
                ARTIFACT_DIGEST,
                "application/octet-stream",
                1)));
  }

  private List<PublishParticipantDigestDto> validParticipants() {
    return List.of(
        participant(PublishParticipantKey.WORLD_MANAGEMENT.name(), 3, null),
        participant(PublishParticipantKey.ENTITY_MANAGEMENT.name(), 2, null),
        participant(PublishParticipantKey.GAME_LOGIC.name(), 1, ABILITY_SCHEMA_DIGEST),
        participant(PublishParticipantKey.AUTOMATION_SCRIPTING.name(), 5, null),
        participant(PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE.name(), 1, null));
  }

  private PublishParticipantDigestDto participant(
      String participantKey, int digestSchemaVersion, String abilitySchemaDigest) {
    return new PublishParticipantDigestDto(
        participantKey,
        Long.toString(VERSION_ID),
        null,
        "commit-12",
        CONTENT_DIGEST,
        digestSchemaVersion,
        abilitySchemaDigest,
        null,
        null);
  }

  private Version canonicalVersion() {
    Version result = new Version();
    result.setId(VERSION_ID);
    result.setTenantId(PRIVATE_TENANT_KEY);
    result.setCanonicalTenantId(CANONICAL_TENANT_ID);
    result.setCanonicalVersionId(CANONICAL_VERSION_ID);
    result.setIdentitySourceGameRowId(73L);
    result.setIdentitySourceGameTenantKey(PRIVATE_TENANT_KEY);
    result.setIdentitySourceProvenanceKind("NEW_GAME_ROW");
    result.setVersionNumber((int) VERSION_NUMBER);
    result.setVersionState(VersionLifecycleState.PUBLISHED);
    result.setVersionStateEpoch(23L);
    return result;
  }

  private PublishedReleaseBundleDto copyBundle(
      PublishedReleaseBundleDto original,
      long id,
      String tenantId,
      long versionId,
      int versionNumber,
      String releaseRef,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      List<String> requiredKeys,
      List<PublishParticipantDigestDto> participants,
      Integer manifestSchemaVersion,
      String manifestHash,
      List<PublishedArtifactDigest> artifacts,
      boolean scriptOnly,
      String scriptPatchVersion) {
    return new PublishedReleaseBundleDto(
        id,
        tenantId,
        versionId,
        versionNumber,
        original.attestationSchemaVersion(),
        original.publishWorkflowId(),
        manifestHash,
        requiredKeys,
        participants,
        original.commandDefinitions(),
        original.generationConfigRevision(),
        scriptOnly,
        scriptPatchVersion,
        original.publishedAt(),
        canonicalTenantId,
        canonicalVersionId,
        releaseRef,
        manifestSchemaVersion,
        artifacts);
  }
}
