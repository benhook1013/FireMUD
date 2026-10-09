package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateLaunchConfigView;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class LaunchDescriptorServiceImplTest {
  private static final String NAMESPACE = "test";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_REGISTRATION_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final String WORLD_SLUG = "silver-march";
  private static final String PRIVATE_SOURCE_TENANT_KEY = "game-owner-key-901";
  private static final String PUBLISHED_RELEASE_BUNDLE_REF = "opaque-release-reference-from-owner";
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);

  @Mock private GameTemplateRepository gameTemplateRepository;
  @Mock private LaunchDescriptorRepository launchDescriptorRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;
  @Mock private TemplateRemapSetService templateRemapSetService;
  @Mock private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;

  private LaunchDescriptorServiceImpl service;

  @Test
  void completeSelectorV2ResolvesTheUnchangedDescriptorV1Binding() throws Exception {
    var request = request("cp-selector-v2", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    var bundle = selectorBundle(false);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L))
        .thenReturn(bundle);

    var resolved = service.resolveLaunchDescriptor(request);

    assertEquals(7L, resolved.versionId());
    assertEquals(11L, resolved.releaseBundleId());
    assertEquals(PUBLISHED_RELEASE_BUNDLE_REF, resolved.publishedReleaseBundleRef());
    assertEquals(
        AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION,
        resolved.authoredWorldBinding().schemaVersion());
    assertEquals(request.requestDigest(), resolved.authoredWorldBinding().requestDigest());
    assertEquals(17L, resolved.versionStateEpoch());
    verify(launchDescriptorRepository).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void selectorV2WithoutCompleteParticipantProofCannotResolveOrFreezeSyntheticSuccess()
      throws Exception {
    var request = request("cp-incomplete-selector-v2", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L))
        .thenReturn(selectorBundle(true));

    assertThrows(IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));
    verify(launchDescriptorRepository, never()).insertImmutable(any(LaunchDescriptor.class));
  }

  private PublishedReleaseBundleDto selectorBundle(boolean omitParticipant) throws Exception {
    // Stipulated original component bytes; this fixture does not authenticate a producer or admit
    // gameplay.
    WorldPublishedStartLocationEvidence evidence =
        PublishedWorldSelectorFixtures.evidence(
            new TargetProof(
                CANONICAL_TENANT_ID,
                CANONICAL_VERSION_ID,
                7L,
                PRIVATE_SOURCE_TENANT_KEY,
                1L,
                PRIVATE_SOURCE_TENANT_KEY,
                "NEW_GAME_ROW"));
    var participants = PublishedWorldSelectorFixtures.participants(7L, evidence);
    return new PublishedReleaseBundleDto(
        11L,
        PRIVATE_SOURCE_TENANT_KEY,
        7L,
        8,
        "v2",
        evidence.request().publishWorkflowId(),
        MANIFEST_HASH,
        List.of(),
        omitParticipant ? participants.subList(0, 4) : participants,
        List.of(),
        "genrev-1",
        false,
        null,
        LocalDateTime.now(),
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        PUBLISHED_RELEASE_BUNDLE_REF,
        1,
        List.of(),
        evidence);
  }

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new LaunchDescriptorServiceImpl(
            gameTemplateRepository,
            launchDescriptorRepository,
            versionRepository,
            publishedReleaseBundleService,
            templateRemapSetService,
            authoredWorldSourceRepository,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);
  }

  @Test
  void resolveLaunchDescriptorPersistsCanonicalBindingAndResolvedRelease() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-1", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    AtomicReference<LaunchDescriptor> inserted = new AtomicReference<>();
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(
            invocation -> {
              LaunchDescriptor descriptor = invocation.getArgument(0);
              inserted.set(descriptor);
              return descriptor;
            });

    var resolved = service.resolveLaunchDescriptor(request);

    assertEquals(CANONICAL_TENANT_ID.toString(), resolved.canonicalTenantId());
    assertEquals(9L, resolved.gameTemplateId());
    assertEquals(7L, resolved.versionId());
    assertEquals("genrev-1", resolved.generationConfigRevision());
    assertEquals(17L, resolved.versionStateEpoch());
    assertEquals(11L, resolved.releaseBundleId());
    assertEquals("{}", resolved.runtimeFlagsJson());
    assertEquals(PUBLISHED_RELEASE_BUNDLE_REF, resolved.publishedReleaseBundleRef());
    assertNotNull(resolved.authoredWorldBinding());
    assertEquals(request.requestDigest(), resolved.authoredWorldBinding().requestDigest());
    assertEquals(
        PRIVATE_SOURCE_TENANT_KEY,
        inserted.get().getTenantId(),
        "The private source key remains owner-local and is not the canonical tenant UUID");
    assertEquals(CANONICAL_TENANT_ID.toString(), inserted.get().getCanonicalTenantId());
    assertDescriptorSourceTuple(inserted.get(), sourceEvidence(WORLD_SLUG));
    verify(authoredWorldSourceRepository)
        .read(SOURCE_OPERATION_ID, CANONICAL_TENANT_ID, WORLD_SLUG, NAMESPACE);
    verify(gameTemplateRepository).findLaunchConfigByTenantIdAndId(PRIVATE_SOURCE_TENANT_KEY, 9L);
    verify(gameTemplateRepository, never())
        .findLaunchConfigByTenantIdAndId(CANONICAL_TENANT_ID.toString(), 9L);
  }

  @Test
  void malformedRequestedRuntimeFlagsFreezeConfigurationDenialWithoutSuccessfulDescriptor() {
    String requestedFlags = "{\"pvpEnabled\":false} trailing";
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-invalid-requested-runtime-flags", 9L, requestedFlags);
    stubSuccessfulLaunch(request, 7L, 11L);

    assertFrozenRuntimeFlagsDenial(request);

    verify(publishedReleaseBundleService, never())
        .getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L);
  }

  @Test
  void malformedTemplateRuntimeFlagsFreezeConfigurationDenialWithoutSuccessfulDescriptor() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-invalid-template-flags", 9L);
    GameTemplateLaunchConfigView template = stubTemplate(request, 7L, null);
    when(template.getDefaultRuntimeFlagsJson()).thenReturn("not-json");
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);

    assertFrozenRuntimeFlagsDenial(request);

    verify(publishedReleaseBundleService, never())
        .getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L);
  }

  @Test
  void nonObjectRuntimeFlagsCannotBeBoundAsLaunchConfiguration() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-non-object-runtime-flags", 9L, "[]");
    stubSuccessfulLaunch(request, 7L, 11L);

    assertFrozenRuntimeFlagsDenial(request);
  }

  @Test
  void validRequestedRuntimeFlagsPreserveOriginalJsonAndRequestDigest() {
    String requestedFlags = "{ \"pvpEnabled\" : false }";
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-valid-requested-runtime-flags", 9L, requestedFlags);
    stubSuccessfulLaunch(request, 7L, 11L);
    AtomicReference<LaunchDescriptor> inserted = new AtomicReference<>();
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(
            invocation -> {
              LaunchDescriptor descriptor = invocation.getArgument(0);
              inserted.set(descriptor);
              return descriptor;
            });

    var resolved = service.resolveLaunchDescriptor(request);

    assertEquals(requestedFlags, resolved.runtimeFlagsJson());
    assertEquals(request.requestDigest(), resolved.authoredWorldBinding().requestDigest());
    assertEquals(requestedFlags, inserted.get().getRuntimeFlagsJson());
    assertEquals(resolved.authoredWorldBinding().resultDigest(), inserted.get().getResultDigest());
  }

  @Test
  void requestedRuntimeFlagsStillCannotOverrideTemplateOwnedValues() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-template-owned-runtime-flags", 9L, "{\"pvpEnabled\":false}");
    GameTemplateLaunchConfigView template = stubTemplate(request, 7L, null);
    when(template.getDefaultRuntimeFlagsJson()).thenReturn("{\"pvpEnabled\":true}");
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);

    assertFrozenRuntimeFlagsDenial(request);
  }

  @Test
  void exactRetryReturnsStoredResultWithoutReadingChangedTemplateDefaultsVersionOrRelease() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-retry", 9L);
    GameTemplateLaunchConfigView template = stubTemplate(request, 7L, null);
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);
    stubBundle(7L, 11L, "v1");
    AuthoredWorldSourceEvidence source = sourceEvidence(WORLD_SLUG);
    stubSource(request, source);
    AtomicReference<LaunchDescriptor> stored = new AtomicReference<>();
    when(launchDescriptorRepository.findBoundByRequest(NAMESPACE, CANONICAL_TENANT_ID, "cp-retry"))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(launchDescriptorRepository.findByPrivateRequest(PRIVATE_SOURCE_TENANT_KEY, "cp-retry"))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(
            invocation -> {
              LaunchDescriptor descriptor = invocation.getArgument(0);
              stored.set(descriptor);
              return descriptor;
            });

    var first = service.resolveLaunchDescriptor(request);
    when(template.getDefaultVersionId()).thenReturn(8L);
    when(template.getDefaultScriptPatchVersion()).thenReturn("patch-1");
    clearInvocations(template);
    when(versionRepository.findById(8L)).thenReturn(Optional.empty());
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 8L))
        .thenThrow(new PublishedReleaseBundleNotFoundException(PRIVATE_SOURCE_TENANT_KEY, 8L));

    var retry = service.resolveLaunchDescriptor(request);

    assertEquals(first, retry);
    assertEquals(7L, retry.versionId());
    verify(gameTemplateRepository, times(1))
        .findLaunchConfigByTenantIdAndId(PRIVATE_SOURCE_TENANT_KEY, 9L);
    verifyNoInteractions(template);
    verify(versionRepository, times(1)).findById(7L);
    verify(versionRepository, never()).findById(8L);
    verify(publishedReleaseBundleService, times(1))
        .getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L);
    verify(publishedReleaseBundleService, never())
        .getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 8L);
    verify(launchDescriptorRepository, times(1)).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void changedSourceScopeTemplateOrRequestCannotReplaceOriginalDescriptor() {
    AuthoredWorldLaunchDescriptorEvidence.Request original = request("cp-conflict", 9L);
    stubSuccessfulLaunch(original, 7L, 11L);
    LaunchDescriptor stored = descriptorFor(original, 7L, 11L);
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, "cp-conflict"))
        .thenReturn(Optional.of(stored));
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AuthoredWorldLaunchDescriptorEvidence.Request changedTemplate = request("cp-conflict", 10L);
    IllegalArgumentException templateConflict =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(changedTemplate));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: control-plane request identity was reused with changed input",
        templateConflict.getMessage());

    AuthoredWorldLaunchDescriptorEvidence.Request changedRequest =
        request("cp-conflict", 9L, true, "other-patch");
    IllegalArgumentException requestConflict =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(changedRequest));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: control-plane request identity was reused with changed input",
        requestConflict.getMessage());

    UUID otherOperation = UUID.fromString("42345678-1234-4234-8234-123456789abc");
    AuthoredWorldLaunchDescriptorEvidence.Request changedSource =
        request("cp-conflict", 9L, otherOperation, WORLD_SLUG, CANONICAL_TENANT_ID);
    when(authoredWorldSourceRepository.read(
            otherOperation, CANONICAL_TENANT_ID, WORLD_SLUG, NAMESPACE))
        .thenReturn(Optional.empty());
    IllegalArgumentException sourceConflict =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(changedSource));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: control-plane request identity was reused with changed input",
        sourceConflict.getMessage());

    UUID otherCanonicalTenant = UUID.fromString("52345678-1234-4234-8234-123456789abc");
    AuthoredWorldLaunchDescriptorEvidence.Request changedTenantScope =
        request("cp-conflict", 9L, SOURCE_OPERATION_ID, WORLD_SLUG, otherCanonicalTenant);
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, otherCanonicalTenant, WORLD_SLUG, NAMESPACE))
        .thenReturn(Optional.empty());
    IllegalArgumentException scopeConflict =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.resolveLaunchDescriptor(changedTenantScope));
    assertEquals(
        "AUTHORED_WORLD_SOURCE_NOT_FOUND: exact committed source evidence is missing",
        scopeConflict.getMessage());

    AuthoredWorldLaunchDescriptorEvidence.Request changedWorldScope =
        request("cp-conflict", 9L, SOURCE_OPERATION_ID, "other-world", CANONICAL_TENANT_ID);
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, CANONICAL_TENANT_ID, "other-world", NAMESPACE))
        .thenReturn(Optional.empty());
    IllegalArgumentException worldConflict =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.resolveLaunchDescriptor(changedWorldScope));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: control-plane request identity was reused with changed input",
        worldConflict.getMessage());
    verify(launchDescriptorRepository, never()).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void exactReadbackReturnsOriginalEvidenceAndRejectsDigestOrScopeSubstitution() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-read", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    LaunchDescriptor stored = descriptorFor(request, 7L, 11L);
    when(launchDescriptorRepository.findBoundByRequest(NAMESPACE, CANONICAL_TENANT_ID, "cp-read"))
        .thenReturn(Optional.of(stored));
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var original = service.resolveLaunchDescriptor(request);

    var readback =
        service.getLaunchDescriptor(
            UUID.fromString("62345678-1234-4234-8234-123456789abc"),
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            "cp-read",
            original.authoredWorldBinding().requestDigest(),
            original.authoredWorldBinding().resultDigest());

    assertEquals(original, readback);
    IllegalArgumentException changedRequestDigest =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.getLaunchDescriptor(
                    UUID.fromString("72345678-1234-4234-8234-123456789abc"),
                    CANONICAL_TENANT_ID,
                    WORLD_SLUG,
                    "cp-read",
                    "sha256:" + "b".repeat(64),
                    original.authoredWorldBinding().resultDigest()));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: exact readback selector or digest does not match",
        changedRequestDigest.getMessage());

    IllegalArgumentException changedResultDigest =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.getLaunchDescriptor(
                    UUID.fromString("82345678-1234-4234-8234-123456789abc"),
                    CANONICAL_TENANT_ID,
                    WORLD_SLUG,
                    "cp-read",
                    original.authoredWorldBinding().requestDigest(),
                    "sha256:" + "c".repeat(64)));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: exact readback selector or digest does not match",
        changedResultDigest.getMessage());

    IllegalArgumentException changedWorld =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.getLaunchDescriptor(
                    UUID.fromString("92345678-1234-4234-8234-123456789abc"),
                    CANONICAL_TENANT_ID,
                    "other-world",
                    "cp-read",
                    original.authoredWorldBinding().requestDigest(),
                    original.authoredWorldBinding().resultDigest()));
    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: exact readback selector or digest does not match",
        changedWorld.getMessage());
    verify(launchDescriptorRepository, never()).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void selectedPatchWithoutExactPublicationAndReadinessEvidenceCannotResolve() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-patch", 9L);
    GameTemplateLaunchConfigView template = stubTemplate(request, 7L, "patch-1");
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "SCRIPT_PATCH_NOT_READY: exact published-for-base and Automation READY evidence is unavailable",
        thrown.getMessage());
    verify(template).getDefaultScriptPatchVersion();
    org.mockito.ArgumentCaptor<LaunchDescriptor> failure =
        org.mockito.ArgumentCaptor.forClass(LaunchDescriptor.class);
    verify(launchDescriptorRepository).insertImmutable(failure.capture());
    assertEquals(LaunchDescriptor.OUTCOME_FAILED, failure.getValue().getOutcomeStatus());
    assertEquals("SCRIPT_PATCH_NOT_READY", failure.getValue().getFailureCode());
    assertEquals(request.requestDigest(), failure.getValue().getRequestDigest());
    assertDescriptorSourceTuple(failure.getValue(), sourceEvidence(WORLD_SLUG));
    assertEquals(null, failure.getValue().getLaunchDescriptorId());
    assertEquals(null, failure.getValue().getVersionId());
  }

  @Test
  void sourceAndTransientDependencyFailuresDoNotCreateFrozenOutcomes() {
    AuthoredWorldLaunchDescriptorEvidence.Request missingSource = request("cp-no-source", 9L);
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, CANONICAL_TENANT_ID, WORLD_SLUG, NAMESPACE))
        .thenReturn(Optional.empty());

    IllegalArgumentException sourceFailure =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(missingSource));
    assertEquals(
        "AUTHORED_WORLD_SOURCE_NOT_FOUND: exact committed source evidence is missing",
        sourceFailure.getMessage());

    AuthoredWorldLaunchDescriptorEvidence.Request changedSourceEvidence =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "cp-changed-source",
            CANONICAL_TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            "sha256:" + "f".repeat(64),
            9L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    stubSource(changedSourceEvidence, sourceEvidence(WORLD_SLUG));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resolveLaunchDescriptor(changedSourceEvidence));

    AuthoredWorldLaunchDescriptorEvidence.Request unavailableDependency =
        request("cp-unavailable", 9L);
    stubSource(unavailableDependency, sourceEvidence(WORLD_SLUG));
    when(gameTemplateRepository.findLaunchConfigByTenantIdAndId(PRIVATE_SOURCE_TENANT_KEY, 9L))
        .thenThrow(new DataAccessResourceFailureException("temporary database outage"));

    assertThrows(
        DataAccessResourceFailureException.class,
        () -> service.resolveLaunchDescriptor(unavailableDependency));
    verify(launchDescriptorRepository, never()).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void exactRetryOfStoredFailureReturnsItsOriginalCodeAndMessageBeforeMutableReads() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-frozen-failure", 9L);
    AuthoredWorldSourceEvidence source = sourceEvidence(WORLD_SLUG);
    LaunchDescriptor failure = new LaunchDescriptor();
    failure.setTenantId(PRIVATE_SOURCE_TENANT_KEY);
    failure.setControlPlaneRequestId(request.controlPlaneRequestId());
    failure.setRequestHash(request.requestDigest());
    failure.setDescriptorSchemaVersion(AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION);
    failure.setTargetNamespace(NAMESPACE);
    failure.setCanonicalTenantId(CANONICAL_TENANT_ID.toString());
    failure.setAuthoredWorldSourceTenantSlug(source.tenantSlug());
    failure.setWorldSlug(WORLD_SLUG);
    failure.setAuthoredWorldSourceOperationId(SOURCE_OPERATION_ID.toString());
    failure.setAuthoredWorldSourceGameRowId(source.sourceGameRowId());
    failure.setAuthoredWorldSourceGameTenantKey(source.sourceGameTenantKey());
    failure.setAuthoredWorldSourceProvenanceKind(source.provenanceKind());
    failure.setAuthoredWorldSourceEvidenceDigest(request.authoredWorldSourceEvidenceDigest());
    failure.setRequestDigest(request.requestDigest());
    failure.setOriginalRequestJson(new ObjectMapper().writeValueAsString(request));
    failure.setSourceEvidenceJson(new ObjectMapper().writeValueAsString(source));
    failure.setOutcomeStatus(LaunchDescriptor.OUTCOME_FAILED);
    failure.setFailureCode("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED");
    failure.setFailureMessage("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: frozen phase denial");
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, request.controlPlaneRequestId()))
        .thenReturn(Optional.of(failure));
    stubSource(request, source);

    FrozenLaunchDescriptorDenialException thrown =
        assertThrows(
            FrozenLaunchDescriptorDenialException.class,
            () -> service.resolveLaunchDescriptor(request));

    assertEquals("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED", thrown.failureCode());
    assertEquals("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: frozen phase denial", thrown.getMessage());
    verify(gameTemplateRepository, never())
        .findLaunchConfigByTenantIdAndId(PRIVATE_SOURCE_TENANT_KEY, 9L);
    verify(launchDescriptorRepository, never()).insertImmutable(any(LaunchDescriptor.class));
  }

  @Test
  void tamperedStoredPatchCannotReplayOutsideItsCommittedResultDigest() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-tampered", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    LaunchDescriptor stored = descriptorFor(request, 7L, 11L);
    stored.setScriptPatchVersion("patch-1");
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, "cp-tampered"))
        .thenReturn(Optional.of(stored));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: stored resolved result digest is inconsistent",
        thrown.getMessage());
  }

  @Test
  void storedDescriptorReplayRejectsChangedAuthoredSourceTuple() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-tampered-source", 9L);
    stubSuccessfulLaunch(request, 7L, 11L);
    LaunchDescriptor stored = descriptorFor(request, 7L, 11L);
    stored.setAuthoredWorldSourceGameRowId(902L);
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, request.controlPlaneRequestId()))
        .thenReturn(Optional.of(stored));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "LAUNCH_DESCRIPTOR_CONFLICT: stored descriptor is not the exact immutable request",
        thrown.getMessage());
  }

  @Test
  void resolveLaunchDescriptorPreservesTemplatePhaseGate() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-phase", 9L);
    GameTemplateLaunchConfigView template = stubTemplate(request, 7L, null);
    when(template.getTemplateReferencePhase()).thenReturn(TemplateReferencePhase.LEGACY);
    stubSource(request, sourceEvidence(WORLD_SLUG));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: template reference phase is not enforced",
        thrown.getMessage());
  }

  @Test
  void resolveLaunchDescriptorFailsClosedOnUnsupportedAttestationSchema() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-schema", 9L);
    stubTemplate(request, 7L, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L))
        .thenReturn(releaseBundle(7L, 11L, "v999"));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        true,
        thrown
            .getMessage()
            .startsWith(
                "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema"));
  }

  @Test
  void resolveLaunchDescriptorReportsMissingReleaseBundleDeterministically() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-missing-release", 9L);
    stubTemplate(request, 7L, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L))
        .thenThrow(new PublishedReleaseBundleNotFoundException(PRIVATE_SOURCE_TENANT_KEY, 7L));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "RELEASE_BUNDLE_NOT_FOUND: no published release bundle for the resolved version",
        thrown.getMessage());
  }

  @Test
  void missingOpaqueBundleReferenceDeniesCreationAndExactRetryWithoutSynthesizing() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-missing-reference", 9L);
    stubTemplate(request, 7L, null);
    AuthoredWorldSourceEvidence source = sourceEvidence(WORLD_SLUG);
    stubSource(request, source);
    stubVersion(7L, VersionLifecycleState.PUBLISHED, 17L, null);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L))
        .thenReturn(releaseBundle(7L, 11L, "v1", null));
    AtomicReference<LaunchDescriptor> stored = new AtomicReference<>();
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, request.controlPlaneRequestId()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(launchDescriptorRepository.findByPrivateRequest(
            PRIVATE_SOURCE_TENANT_KEY, request.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(
            invocation -> {
              LaunchDescriptor descriptor = invocation.getArgument(0);
              stored.set(descriptor);
              return descriptor;
            });

    IllegalArgumentException first =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));
    IllegalArgumentException retry =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "RELEASE_BUNDLE_NOT_FOUND: published release bundle has no persisted opaque reference",
        first.getMessage());
    assertEquals(first.getMessage(), retry.getMessage());
    assertEquals(LaunchDescriptor.OUTCOME_FAILED, stored.get().getOutcomeStatus());
    assertEquals("RELEASE_BUNDLE_NOT_FOUND", stored.get().getFailureCode());
    assertNull(stored.get().getPublishedReleaseBundleRef());
    verify(publishedReleaseBundleService, times(1))
        .getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 7L);
  }

  @Test
  void resolveLaunchDescriptorAcceptsEqualLargeReleaseVersionIdsByValue() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-large-version", 9L);
    Long resolvedVersionId = boxedLargeVersionId();
    Long bundleVersionId = boxedLargeVersionId();
    assertNotSame(resolvedVersionId, bundleVersionId);
    stubTemplate(request, resolvedVersionId, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(1_000L, VersionLifecycleState.PUBLISHED, 17L, null);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 1_000L))
        .thenReturn(releaseBundle(bundleVersionId, 11L, "v1"));
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, request.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.findByPrivateRequest(
            PRIVATE_SOURCE_TENANT_KEY, request.controlPlaneRequestId()))
        .thenReturn(Optional.empty());

    var resolved = service.resolveLaunchDescriptor(request);

    assertEquals(CANONICAL_TENANT_ID.toString(), resolved.canonicalTenantId());
    assertEquals(9L, resolved.gameTemplateId());
    assertEquals(1_000L, resolved.versionId());
    assertEquals(11L, resolved.releaseBundleId());
    assertNotNull(resolved.authoredWorldBinding());
    assertEquals(request.requestDigest(), resolved.authoredWorldBinding().requestDigest());
    assertEquals(PUBLISHED_RELEASE_BUNDLE_REF, resolved.publishedReleaseBundleRef());
  }

  @Test
  void resolveLaunchDescriptorRejectsReleaseBundleForDifferentVersionDeterministically() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("cp-mismatched-release", 9L);
    stubTemplate(request, 1_000L, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(1_000L, VersionLifecycleState.PUBLISHED, 17L, null);
    when(publishedReleaseBundleService.getPublishedReleaseBundle(PRIVATE_SOURCE_TENANT_KEY, 1_000L))
        .thenReturn(releaseBundle(1_001L, 11L, "v1"));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "RELEASE_BUNDLE_NOT_FOUND: release bundle is not owned by the resolved source version",
        thrown.getMessage());
  }

  @Test
  void resolveLaunchDescriptorRequiresRemapForCrossVersionReplacementLaunch() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-remap-required", 9L, 7L, 8L);
    stubTemplate(request, 8L, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(8L, VersionLifecycleState.PUBLISHED, 18L, null);
    when(templateRemapSetService.findApprovedTemplateRemapSet(PRIVATE_SOURCE_TENANT_KEY, 7L, 8L))
        .thenReturn(Optional.empty());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> service.resolveLaunchDescriptor(request));

    assertEquals(
        "LAUNCH_REMAP_REQUIRED: replacement-instance launch requires an approved remapSetId",
        thrown.getMessage());
  }

  @Test
  void resolveLaunchDescriptorUsesApprovedRemapSetForCrossVersionReplacementLaunch() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        request("cp-remap-approved", 9L, 7L, 8L);
    stubTemplate(request, 8L, null);
    stubSource(request, sourceEvidence(WORLD_SLUG));
    stubVersion(8L, VersionLifecycleState.PUBLISHED, 18L, null);
    when(templateRemapSetService.findApprovedTemplateRemapSet(PRIVATE_SOURCE_TENANT_KEY, 7L, 8L))
        .thenReturn(
            Optional.of(
                new TemplateRemapSetDto(
                    "remap-1",
                    PRIVATE_SOURCE_TENANT_KEY,
                    7L,
                    8L,
                    TemplateRemapSetStatus.APPROVED,
                    "preparation",
                    "approved",
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    List.of())));
    stubBundle(8L, 11L, "v1");
    when(launchDescriptorRepository.insertImmutable(any(LaunchDescriptor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var resolved = service.resolveLaunchDescriptor(request);

    assertEquals("remap-1", resolved.remapSetId());
    assertEquals("remap-1", resolved.authoredWorldBinding().remapSetId());
  }

  private void stubSuccessfulLaunch(
      AuthoredWorldLaunchDescriptorEvidence.Request request, long versionId, long bundleId) {
    stubTemplate(request, versionId, null);
    stubSource(request, sourceEvidence(request.worldSlug()));
    stubVersion(versionId, VersionLifecycleState.PUBLISHED, 17L, null);
    stubBundle(versionId, bundleId, "v1");
    when(launchDescriptorRepository.findBoundByRequest(
            NAMESPACE, CANONICAL_TENANT_ID, request.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.findByPrivateRequest(
            PRIVATE_SOURCE_TENANT_KEY, request.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
  }

  private GameTemplateLaunchConfigView stubTemplate(
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      Long defaultVersionId,
      String defaultPatch) {
    GameTemplateLaunchConfigView template = Mockito.mock(GameTemplateLaunchConfigView.class);
    when(template.getId()).thenReturn(request.gameTemplateId());
    when(template.getTenantId()).thenReturn(PRIVATE_SOURCE_TENANT_KEY);
    when(template.getDefaultVersionId()).thenReturn(defaultVersionId);
    when(template.getDefaultScriptPatchVersion()).thenReturn(defaultPatch);
    when(template.getDefaultRuntimeFlagsJson()).thenReturn("{}");
    when(template.getTemplateReferencePhase()).thenReturn(TemplateReferencePhase.ENFORCED);
    when(gameTemplateRepository.findLaunchConfigByTenantIdAndId(
            PRIVATE_SOURCE_TENANT_KEY, request.gameTemplateId()))
        .thenReturn(Optional.of(template));
    return template;
  }

  private void stubSource(
      AuthoredWorldLaunchDescriptorEvidence.Request request, AuthoredWorldSourceEvidence source) {
    when(authoredWorldSourceRepository.read(
            request.authoredWorldSourceOperationId(),
            request.canonicalTenantId(),
            request.worldSlug(),
            NAMESPACE))
        .thenReturn(Optional.of(source));
  }

  private void stubVersion(
      long versionId, VersionLifecycleState state, long epoch, String scriptPatchVersion) {
    Version version = new Version();
    version.setId(versionId);
    version.setTenantId(PRIVATE_SOURCE_TENANT_KEY);
    version.setVersionNumber(8);
    version.setVersionState(state);
    version.setVersionStateEpoch(epoch);
    version.setScriptPatchVersion(scriptPatchVersion);
    version.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
  }

  private void stubBundle(long versionId, long bundleId, String schemaVersion) {
    when(publishedReleaseBundleService.getPublishedReleaseBundle(
            PRIVATE_SOURCE_TENANT_KEY, versionId))
        .thenReturn(releaseBundle(versionId, bundleId, schemaVersion));
  }

  private PublishedReleaseBundleDto releaseBundle(
      Long versionId, long bundleId, String schemaVersion) {
    return releaseBundle(versionId, bundleId, schemaVersion, PUBLISHED_RELEASE_BUNDLE_REF);
  }

  private PublishedReleaseBundleDto releaseBundle(
      Long versionId, long bundleId, String schemaVersion, String publishedReleaseBundleRef) {
    return new PublishedReleaseBundleDto(
        bundleId,
        PRIVATE_SOURCE_TENANT_KEY,
        versionId,
        8,
        schemaVersion,
        "workflow-1",
        MANIFEST_HASH,
        List.of(),
        List.of(),
        "genrev-1",
        false,
        null,
        LocalDateTime.now(),
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        publishedReleaseBundleRef,
        1,
        List.of());
  }

  @SuppressWarnings("removal")
  private Long boxedLargeVersionId() {
    return new Long(1_000L);
  }

  private LaunchDescriptor descriptorFor(
      AuthoredWorldLaunchDescriptorEvidence.Request request, long versionId, long bundleId) {
    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "ld-stored-" + request.controlPlaneRequestId(),
            versionId,
            false,
            null,
            "{}",
            "genrev-1",
            17L,
            bundleId,
            PUBLISHED_RELEASE_BUNDLE_REF,
            false,
            null);
    AuthoredWorldSourceEvidence source = sourceEvidence(request.worldSlug());
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId(evidence.launchDescriptorId());
    descriptor.setTenantId(PRIVATE_SOURCE_TENANT_KEY);
    descriptor.setGameTemplateId(request.gameTemplateId());
    descriptor.setControlPlaneRequestId(request.controlPlaneRequestId());
    descriptor.setRequestHash(evidence.requestDigest());
    descriptor.setVersionId(evidence.versionId());
    descriptor.setRuntimeFlagsJson(evidence.runtimeFlagsJson());
    descriptor.setGenerationConfigRevision(evidence.generationConfigRevision());
    descriptor.setVersionStateEpoch(evidence.versionStateEpoch());
    descriptor.setReleaseBundleId(evidence.releaseBundleId());
    descriptor.setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef());
    descriptor.setDescriptorSchemaVersion(evidence.schemaVersion());
    descriptor.setTargetNamespace(evidence.targetNamespace());
    descriptor.setCanonicalTenantId(evidence.canonicalTenantId().toString());
    descriptor.setAuthoredWorldSourceTenantSlug(source.tenantSlug());
    descriptor.setWorldSlug(evidence.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(
        evidence.authoredWorldSourceOperationId().toString());
    descriptor.setAuthoredWorldSourceGameRowId(source.sourceGameRowId());
    descriptor.setAuthoredWorldSourceGameTenantKey(source.sourceGameTenantKey());
    descriptor.setAuthoredWorldSourceProvenanceKind(source.provenanceKind());
    descriptor.setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest());
    descriptor.setRequestDigest(evidence.requestDigest());
    descriptor.setResultDigest(evidence.resultDigest());
    descriptor.setOriginalRequestJson(new ObjectMapper().writeValueAsString(request));
    descriptor.setSourceEvidenceJson(new ObjectMapper().writeValueAsString(source));
    return descriptor;
  }

  private AuthoredWorldSourceEvidence sourceEvidence(String worldSlug) {
    String tenantSlug = "silver-company";
    String displayName = "Silver March 🐉";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_ID,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        SOURCE_REGISTRATION_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        CANONICAL_TENANT_ID,
        tenantSlug,
        worldSlug,
        displayName,
        901L,
        PRIVATE_SOURCE_TENANT_KEY,
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            displayName,
            901L,
            PRIVATE_SOURCE_TENANT_KEY,
            "NEW_GAME_ROW"));
  }

  private void assertDescriptorSourceTuple(
      LaunchDescriptor descriptor, AuthoredWorldSourceEvidence source) {
    assertEquals(source.targetNamespace(), descriptor.getTargetNamespace());
    assertEquals(source.canonicalTenantId().toString(), descriptor.getCanonicalTenantId());
    assertEquals(source.tenantSlug(), descriptor.getAuthoredWorldSourceTenantSlug());
    assertEquals(source.worldSlug(), descriptor.getWorldSlug());
    assertEquals(source.operationId().toString(), descriptor.getAuthoredWorldSourceOperationId());
    assertEquals(source.sourceGameRowId(), descriptor.getAuthoredWorldSourceGameRowId());
    assertEquals(source.sourceGameTenantKey(), descriptor.getAuthoredWorldSourceGameTenantKey());
    assertEquals(source.provenanceKind(), descriptor.getAuthoredWorldSourceProvenanceKind());
    assertEquals(source.evidenceDigest(), descriptor.getAuthoredWorldSourceEvidenceDigest());
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request request(
      String controlPlaneRequestId, long gameTemplateId) {
    return request(
        controlPlaneRequestId,
        gameTemplateId,
        SOURCE_OPERATION_ID,
        WORLD_SLUG,
        CANONICAL_TENANT_ID);
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request request(
      String controlPlaneRequestId, long gameTemplateId, String requestedRuntimeFlagsJson) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        NAMESPACE,
        controlPlaneRequestId,
        CANONICAL_TENANT_ID,
        WORLD_SLUG,
        SOURCE_OPERATION_ID,
        sourceEvidence(WORLD_SLUG).evidenceDigest(),
        gameTemplateId,
        false,
        null,
        false,
        null,
        false,
        null,
        true,
        requestedRuntimeFlagsJson);
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request request(
      String controlPlaneRequestId,
      long gameTemplateId,
      long sourceVersionId,
      long targetVersionId) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        NAMESPACE,
        controlPlaneRequestId,
        CANONICAL_TENANT_ID,
        WORLD_SLUG,
        SOURCE_OPERATION_ID,
        sourceEvidence(WORLD_SLUG).evidenceDigest(),
        gameTemplateId,
        false,
        null,
        true,
        sourceVersionId,
        true,
        targetVersionId,
        false,
        null);
  }

  private void assertFrozenRuntimeFlagsDenial(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    FrozenLaunchDescriptorDenialException thrown =
        assertThrows(
            FrozenLaunchDescriptorDenialException.class,
            () -> service.resolveLaunchDescriptor(request));
    assertEquals("INVALID_TEMPLATE_CONFIGURATION", thrown.failureCode());

    org.mockito.ArgumentCaptor<LaunchDescriptor> failure =
        org.mockito.ArgumentCaptor.forClass(LaunchDescriptor.class);
    verify(launchDescriptorRepository).insertImmutable(failure.capture());
    assertEquals(LaunchDescriptor.OUTCOME_FAILED, failure.getValue().getOutcomeStatus());
    assertEquals("INVALID_TEMPLATE_CONFIGURATION", failure.getValue().getFailureCode());
    assertNull(failure.getValue().getLaunchDescriptorId());
    assertNull(failure.getValue().getVersionId());
    assertNull(failure.getValue().getRuntimeFlagsJson());
    assertNull(failure.getValue().getResultDigest());
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request request(
      String controlPlaneRequestId,
      long gameTemplateId,
      boolean requestedPatchPresent,
      String requestedPatch) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        NAMESPACE,
        controlPlaneRequestId,
        CANONICAL_TENANT_ID,
        WORLD_SLUG,
        SOURCE_OPERATION_ID,
        sourceEvidence(WORLD_SLUG).evidenceDigest(),
        gameTemplateId,
        requestedPatchPresent,
        requestedPatch,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request request(
      String controlPlaneRequestId,
      long gameTemplateId,
      UUID operationId,
      String worldSlug,
      UUID canonicalTenantId) {
    String evidenceDigest =
        SOURCE_OPERATION_ID.equals(operationId)
                && WORLD_SLUG.equals(worldSlug)
                && CANONICAL_TENANT_ID.equals(canonicalTenantId)
            ? sourceEvidence(WORLD_SLUG).evidenceDigest()
            : "sha256:" + "a".repeat(64);
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        NAMESPACE,
        controlPlaneRequestId,
        canonicalTenantId,
        worldSlug,
        operationId,
        evidenceDigest,
        gameTemplateId,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  @Test
  void legacyNumericResolveFailsClosedBeforeAnyOwnerInteraction() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.resolveLaunchDescriptor("1", 9L, "cp-1", null, null, null, null));

    assertEquals(
        "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
            + " required to resolve a launch descriptor",
        thrown.getMessage());
    verifyNoInteractions(
        gameTemplateRepository,
        launchDescriptorRepository,
        versionRepository,
        publishedReleaseBundleService,
        templateRemapSetService,
        authoredWorldSourceRepository);
  }
}
