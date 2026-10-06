package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.service.CanonicalStartingGameInstanceOwner;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociationService;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalStartingGameInstanceOwnerTest {
  private static final String NAMESPACE = "starting-owner-test";
  private static final UUID TENANT = uuid(1);
  private static final UUID REALM = uuid(2);
  private static final UUID CATALOG_REQUEST = uuid(3);
  private static final UUID SOURCE_INTAKE_OPERATION = uuid(4);
  private static final UUID INTAKE_REQUEST = uuid(5);
  private static final UUID SOURCE_REGISTRATION_REQUEST = uuid(6);
  private static final UUID SOURCE_OPERATION = uuid(7);
  private static final UUID ACTING_ACCOUNT = uuid(8);
  private static final UUID ASSOCIATION_REQUEST = uuid(9);
  private static final UUID TENANT_ASSOCIATION_OPERATION = uuid(10);
  private static final UUID INSTANCE_UUID = uuid(11);
  private static final long GAME_SESSION_TENANT_ID = 901L;
  private static final long SOURCE_GAME_ROW_ID = 9_001L;
  private static final String SOURCE_GAME_TENANT_KEY = "gd-private-tenant-9002";
  private static final String CONTROL_PLANE_REQUEST = "canonical-start-1";
  private static final long GAME_TEMPLATE_ID = 71L;
  private static final long INSTANCE_ID = 902L;

  private final FreshGameSessionTenantAssociationService tenantAssociationService =
      mock(FreshGameSessionTenantAssociationService.class);
  private final GameSessionCanonicalLaunchPreparationService launchPreparationService =
      mock(GameSessionCanonicalLaunchPreparationService.class);
  private final CanonicalGameInstanceLaunchAssociationRepository launchAssociationRepository =
      mock(CanonicalGameInstanceLaunchAssociationRepository.class);
  private final GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void createsAndReadsBackAnUnpinnedStartingInstanceWithItsExactCompleteAssociation() {
    Fixture fixture = fixture();
    stubComposition(fixture);
    AtomicReference<GameInstance> persisted = new AtomicReference<>();
    AtomicReference<CanonicalGameInstanceLaunchAssociation> captured = new AtomicReference<>();
    stubOwnerWrite(fixture, persisted, captured, Optional.empty());

    CanonicalGameInstanceLaunchAssociation result =
        owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST);

    assertThat(result).isEqualTo(captured.get());
    assertThat(result.launchBindingEvidence()).isEqualTo(fixture.binding());
    assertThat(result.currentGameInstanceStatus())
        .isEqualTo(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING);
    assertThat(result.capturedStartingRowVersion()).isZero();
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    assertThat(transactionManager.commitCount).isEqualTo(1);

    ArgumentCaptor<GameInstance> newInstance = ArgumentCaptor.forClass(GameInstance.class);
    verify(gameInstanceRepository).save(newInstance.capture());
    GameInstance candidate = newInstance.getValue();
    assertThat(candidate.getId()).isNull();
    assertThat(candidate.getGameInstanceUuid()).isNull();
    assertThat(candidate.getTenantId()).isEqualTo(GAME_SESSION_TENANT_ID);
    assertThat(candidate.getOwnerAccountId()).isEqualTo(ACTING_ACCOUNT.toString());
    assertThat(candidate.getStatus()).isEqualTo("STARTING");
    assertThat(candidate.getRuntimeVersion())
        .isEqualTo(Long.toString(fixture.binding().descriptor().versionId()));
    assertThat(candidate.getRunOwnedStartRequestId()).isEqualTo(CONTROL_PLANE_REQUEST);
    assertThat(candidate.getRunOwnedStartRequestDigest())
        .isEqualTo(expectedRunOwnedStartDigest(fixture.request()));
    assertThat(candidate.getRunOwnedStartPublishedReleaseBundleRef())
        .isEqualTo(fixture.binding().descriptor().publishedReleaseBundleRef());
    assertThat(candidate.getScriptPatchVersion()).isNull();
    assertThat(candidate.getScriptPinEpoch()).isNull();
    assertThat(candidate.getScriptPatchPinnedControlPlaneRequestId()).isNull();
    verify(launchAssociationRepository).lockRequestForStartingGameInstance(CONTROL_PLANE_REQUEST);
    verify(launchAssociationRepository)
        .capture(fixture.tenantAssociation(), INSTANCE_ID, fixture.binding());
    assertThat(transactionManager.commitCount).isEqualTo(1);
  }

  @Test
  void exactRetryReusesThePersistedInstanceAndAssociationWithoutSavingAnotherRow() {
    Fixture fixture = fixture();
    stubComposition(fixture);
    GameInstance existing = persistedInstance(fixture, "STARTING", 0L);
    CanonicalGameInstanceLaunchAssociation association = association(fixture, existing, 0L);
    AtomicReference<GameInstance> persisted = new AtomicReference<>(existing);
    AtomicReference<CanonicalGameInstanceLaunchAssociation> captured =
        new AtomicReference<>(association);
    stubOwnerWrite(fixture, persisted, captured, Optional.of(existing));

    assertThat(owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST))
        .isEqualTo(association);

    verify(gameInstanceRepository, never()).save(any(GameInstance.class));
    verify(launchAssociationRepository)
        .capture(fixture.tenantAssociation(), INSTANCE_ID, fixture.binding());
    assertThat(transactionManager.commitCount).isEqualTo(1);
  }

  @Test
  void changedPersistedOwnerAccountConflictsBeforeCaptureOrMutation() {
    Fixture fixture = fixture();
    stubComposition(fixture);
    GameInstance changedOwner = persistedInstance(fixture, "STARTING", 0L);
    changedOwner.setOwnerAccountId(uuid(99).toString());
    AtomicReference<GameInstance> persisted = new AtomicReference<>(changedOwner);
    AtomicReference<CanonicalGameInstanceLaunchAssociation> captured = new AtomicReference<>();
    when(gameInstanceRepository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(
            GAME_SESSION_TENANT_ID, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(changedOwner));
    stubRequestLock();

    assertThatThrownBy(() -> owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST))
        .isInstanceOf(
            CanonicalGameInstanceLaunchAssociationRepository
                .CanonicalGameInstanceLaunchAssociationConflictException.class)
        .hasMessageContaining("conflicts with the persisted owner instance");

    verify(gameInstanceRepository, never()).save(any(GameInstance.class));
    verify(launchAssociationRepository, never())
        .capture(any(), anyLong(), any(CompleteLaunchBindingEvidence.class));
    assertThat(transactionManager.rollbackCount).isEqualTo(1);
    assertThat(persisted.get()).isSameAs(changedOwner);
    assertThat(captured.get()).isNull();
  }

  @Test
  void changedCompleteBindingOnExactRetryConflictsBeforeCreatingAnotherInstance() {
    Fixture fixture = fixture();
    stubComposition(fixture);
    CompleteLaunchBindingEvidence changedBinding =
        completeBinding(fixture.binding().descriptor(), uuid(15));
    when(launchPreparationService.readCompleteBinding(fixture.preparation()))
        .thenReturn(changedBinding);
    GameInstance existing = persistedInstance(fixture, "STARTING", 0L);
    AtomicReference<GameInstance> persisted = new AtomicReference<>(existing);
    AtomicReference<CanonicalGameInstanceLaunchAssociation> captured =
        new AtomicReference<>(association(fixture, existing, 0L));
    stubOwnerWrite(fixture, persisted, captured, Optional.of(existing));
    when(launchAssociationRepository.capture(
            fixture.tenantAssociation(), INSTANCE_ID, changedBinding))
        .thenThrow(
            new CanonicalGameInstanceLaunchAssociationRepository
                .CanonicalGameInstanceLaunchAssociationConflictException(
                "Launch association request was reused with a changed source binding"));

    assertThatThrownBy(() -> owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST))
        .isInstanceOf(
            CanonicalGameInstanceLaunchAssociationRepository
                .CanonicalGameInstanceLaunchAssociationConflictException.class)
        .hasMessageContaining("changed source binding");

    verify(gameInstanceRepository, never()).save(any(GameInstance.class));
    verify(launchAssociationRepository)
        .capture(fixture.tenantAssociation(), INSTANCE_ID, changedBinding);
    assertThat(transactionManager.rollbackCount).isEqualTo(1);
    assertThat(captured.get()).isEqualTo(association(fixture, existing, 0L));
  }

  @Test
  void rejectsFreshTenantSourceThatDoesNotJoinThePreparedAuthoredSource() {
    Fixture fixture = fixture();
    RuntimeTenantIdentityEvidence changedSource =
        new RuntimeTenantIdentityEvidence(
            1,
            NAMESPACE,
            ASSOCIATION_REQUEST,
            TENANT,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY + "-changed",
            "NEW_GAME_ROW");
    FreshGameSessionTenantAssociation changedAssociation =
        new FreshGameSessionTenantAssociation(
            TENANT_ASSOCIATION_OPERATION, GAME_SESSION_TENANT_ID, changedSource);
    when(tenantAssociationService.associate(ASSOCIATION_REQUEST, TENANT))
        .thenReturn(changedAssociation);
    when(launchPreparationService.prepare(fixture.request())).thenReturn(fixture.preparation());

    assertThatThrownBy(() -> owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match the exact authored-world source intake");

    verify(launchPreparationService, never()).readCompleteBinding(any());
    verifyNoInteractions(launchAssociationRepository, gameInstanceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void rejectsAmbientTransactionBeforeAuthenticatedSourceOrPreparationReads() {
    Fixture fixture = fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> owner().createStartingInstance(fixture.request(), ASSOCIATION_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient transaction");

    verifyNoInteractions(
        tenantAssociationService,
        launchPreparationService,
        launchAssociationRepository,
        gameInstanceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  private CanonicalStartingGameInstanceOwner owner() {
    return new CanonicalStartingGameInstanceOwner(
        tenantAssociationService,
        launchPreparationService,
        launchAssociationRepository,
        gameInstanceRepository,
        transactionManager,
        NAMESPACE);
  }

  private void stubComposition(Fixture fixture) {
    when(tenantAssociationService.associate(ASSOCIATION_REQUEST, TENANT))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return fixture.tenantAssociation();
            });
    when(launchPreparationService.prepare(fixture.request()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return fixture.preparation();
            });
    when(launchPreparationService.readCompleteBinding(fixture.preparation()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return fixture.binding();
            });
  }

  private void stubOwnerWrite(
      Fixture fixture,
      AtomicReference<GameInstance> persisted,
      AtomicReference<CanonicalGameInstanceLaunchAssociation> captured,
      Optional<GameInstance> existing) {
    stubRequestLock();
    when(gameInstanceRepository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(
            GAME_SESSION_TENANT_ID, CONTROL_PLANE_REQUEST))
        .thenReturn(existing);
    when(gameInstanceRepository.save(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              assertOwnerTransaction();
              GameInstance candidate = invocation.getArgument(0);
              assertThat(candidate.getId()).isNull();
              assertThat(candidate.getGameInstanceUuid()).isNull();
              GameInstance stored = persistedInstance(candidate, INSTANCE_ID, INSTANCE_UUID, 0L);
              persisted.set(stored);
              return stored;
            });
    when(launchAssociationRepository.capture(
            fixture.tenantAssociation(), INSTANCE_ID, fixture.binding()))
        .thenAnswer(
            ignored -> {
              assertOwnerTransaction();
              CanonicalGameInstanceLaunchAssociation association =
                  association(fixture, persisted.get(), 0L);
              captured.set(association);
              return association;
            });
    when(launchAssociationRepository.read(CONTROL_PLANE_REQUEST))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.ofNullable(captured.get());
            });
    when(gameInstanceRepository.findById(INSTANCE_ID))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.ofNullable(persisted.get());
            });
  }

  private void stubRequestLock() {
    doAnswer(
            ignored -> {
              assertOwnerTransaction();
              return null;
            })
        .when(launchAssociationRepository)
        .lockRequestForStartingGameInstance(CONTROL_PLANE_REQUEST);
  }

  private void assertOwnerTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static Fixture fixture() {
    AuthoredWorldSourceEvidence source = sourceEvidence();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(request, catalog, source);
    String requestDigest =
        GameSessionCanonicalLaunchPreparationRepository.requestDigest(
            request, catalog, intake, descriptor);
    UUID preparationOperationId = uuid(12);
    CanonicalLaunchPreparationSnapshot preparation =
        new CanonicalLaunchPreparationSnapshot(
            preparationOperationId,
            request,
            catalog,
            intake,
            descriptor,
            requestDigest,
            GameSessionCanonicalLaunchPreparationRepository.receiptDigest(
                preparationOperationId, request, catalog, intake, descriptor, requestDigest));
    RuntimeTenantIdentityEvidence tenantSource =
        new RuntimeTenantIdentityEvidence(
            1,
            NAMESPACE,
            ASSOCIATION_REQUEST,
            TENANT,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    FreshGameSessionTenantAssociation tenantAssociation =
        new FreshGameSessionTenantAssociation(
            TENANT_ASSOCIATION_OPERATION, GAME_SESSION_TENANT_ID, tenantSource);
    return new Fixture(request, preparation, completeBinding(descriptor), tenantAssociation);
  }

  private static CreateCanonicalLaunchPreparationRequest request() {
    return new CreateCanonicalLaunchPreparationRequest(
        CONTROL_PLANE_REQUEST,
        ACTING_ACCOUNT,
        NAMESPACE,
        TENANT,
        REALM,
        CATALOG_REQUEST,
        1L,
        SOURCE_INTAKE_OPERATION,
        GAME_TEMPLATE_ID,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String tenantSlug = "owner-tenant";
    String worldSlug = "owner-world";
    String displayName = "Owner World";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, SOURCE_REGISTRATION_REQUEST, TENANT, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_REQUEST,
            SOURCE_OPERATION,
            sourceRequestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            displayName,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        SOURCE_REGISTRATION_REQUEST,
        SOURCE_OPERATION,
        sourceRequestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        displayName,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static IntakeReceipt intake(AuthoredWorldSourceEvidence source) {
    String requestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(INTAKE_REQUEST, source);
    return new IntakeReceipt(
        SOURCE_INTAKE_OPERATION,
        INTAKE_REQUEST,
        requestDigest,
        source,
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            SOURCE_INTAKE_OPERATION, requestDigest, source.evidenceDigest()));
  }

  private static CanonicalRealmCatalogSnapshot catalog(IntakeReceipt source) {
    return new CanonicalRealmCatalogSnapshot(
        NAMESPACE,
        TENANT,
        source.source().tenantSlug(),
        source.source().worldSlug(),
        REALM,
        "owner-realm",
        "Owner Realm",
        true,
        true,
        "SHARED",
        uuid(13),
        "explicit-policy-v1",
        1,
        CATALOG_REQUEST,
        digest('c'),
        digest('d'),
        source);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      AuthoredWorldSourceEvidence source) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request.descriptorRequest(catalog, source),
        "launch-descriptor-1",
        72L,
        false,
        null,
        "{}",
        "generation-1",
        3L,
        73L,
        "bundle-73",
        false,
        null);
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return completeBinding(descriptor, uuid(14));
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor, UUID attestationOperationId) {
    String commitId = "publish-commit-902";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest('e') : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            attestationOperationId,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:tenant:version:request",
            commitId,
            participants,
            digest('f'),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static GameInstance persistedInstance(Fixture fixture, String status, long rowVersion) {
    return persistedInstance(
        instanceCandidate(fixture), INSTANCE_ID, INSTANCE_UUID, rowVersion, status);
  }

  private static GameInstance persistedInstance(
      GameInstance candidate, long id, UUID gameInstanceUuid, long rowVersion) {
    return persistedInstance(candidate, id, gameInstanceUuid, rowVersion, candidate.getStatus());
  }

  private static GameInstance persistedInstance(
      GameInstance candidate, long id, UUID gameInstanceUuid, long rowVersion, String status) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setGameInstanceUuid(gameInstanceUuid);
    instance.setTenantId(candidate.getTenantId());
    instance.setRuntimeVersion(candidate.getRuntimeVersion());
    instance.setScriptPatchVersion(candidate.getScriptPatchVersion());
    instance.setScriptPatchBaseVersionId(candidate.getScriptPatchBaseVersionId());
    instance.setScriptPinEpoch(candidate.getScriptPinEpoch());
    instance.setGameTemplateId(candidate.getGameTemplateId());
    instance.setLaunchDescriptorId(candidate.getLaunchDescriptorId());
    instance.setVersionId(candidate.getVersionId());
    instance.setReleaseBundleId(candidate.getReleaseBundleId());
    instance.setVersionStateEpoch(candidate.getVersionStateEpoch());
    instance.setGenerationConfigRevision(candidate.getGenerationConfigRevision());
    instance.setRemapSetId(candidate.getRemapSetId());
    instance.setScriptPatchPinnedControlPlaneRequestId(
        candidate.getScriptPatchPinnedControlPlaneRequestId());
    instance.setOwnerAccountId(candidate.getOwnerAccountId());
    instance.setLegacyOwnerAccountId(candidate.getLegacyOwnerAccountId());
    instance.setStatus(status);
    instance.setRowVersion(rowVersion);
    instance.setRunOwnedStartRequestId(candidate.getRunOwnedStartRequestId());
    instance.setRunOwnedStartRequestDigest(candidate.getRunOwnedStartRequestDigest());
    instance.setRunOwnedStartPublishedReleaseBundleRef(
        candidate.getRunOwnedStartPublishedReleaseBundleRef());
    return instance;
  }

  private static GameInstance instanceCandidate(Fixture fixture) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = fixture.binding().descriptor();
    GameInstance instance = new GameInstance();
    instance.setTenantId(GAME_SESSION_TENANT_ID);
    instance.setRuntimeVersion(Long.toString(descriptor.versionId()));
    instance.setGameTemplateId(descriptor.gameTemplateId());
    instance.setLaunchDescriptorId(descriptor.launchDescriptorId());
    instance.setVersionId(descriptor.versionId());
    instance.setReleaseBundleId(descriptor.releaseBundleId());
    instance.setVersionStateEpoch(descriptor.versionStateEpoch());
    instance.setGenerationConfigRevision(descriptor.generationConfigRevision());
    instance.setRemapSetId(descriptor.remapSetId());
    instance.setOwnerAccountId(ACTING_ACCOUNT.toString());
    instance.setStatus("STARTING");
    instance.setRunOwnedStartRequestId(CONTROL_PLANE_REQUEST);
    instance.setRunOwnedStartRequestDigest(expectedRunOwnedStartDigest(fixture.request()));
    instance.setRunOwnedStartPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef());
    return instance;
  }

  private static CanonicalGameInstanceLaunchAssociation association(
      Fixture fixture, GameInstance instance, long capturedRowVersion) {
    return new CanonicalGameInstanceLaunchAssociation(
        NAMESPACE,
        GAME_SESSION_TENANT_ID,
        TENANT_ASSOCIATION_OPERATION,
        TENANT,
        instance.getGameInstanceUuid(),
        fixture.preparation().catalogSnapshot().worldSlug(),
        fixture.preparation().catalogSnapshot().playableStateNamespaceId(),
        net.firedevops.firemud.common.publication.RealmEntryPolicy.StateScope.SHARED,
        true,
        CONTROL_PLANE_REQUEST,
        fixture.binding().descriptor().launchDescriptorId(),
        capturedRowVersion,
        fixture.binding(),
        CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.valueOf(
            instance.getStatus()),
        instance.getRowVersion());
  }

  private static String expectedRunOwnedStartDigest(
      CreateCanonicalLaunchPreparationRequest request) {
    StringBuilder preimage = new StringBuilder();
    appendDigestField(preimage, "schema", "firemud.run-owned-initial-launch/v1");
    appendDigestField(preimage, "tenantId", Long.toString(GAME_SESSION_TENANT_ID));
    appendDigestField(preimage, "gameTemplateId", Long.toString(request.gameTemplateId()));
    appendDigestField(preimage, "controlPlaneRequestId", request.controlPlaneRequestId());
    appendDigestField(preimage, "ownerAccountId", request.actingAccountUuid().toString());
    appendDigestField(preimage, "replaceExistingFirst", "false");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(preimage.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void appendDigestField(StringBuilder preimage, String name, String value) {
    preimage
        .append(name.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(name)
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value);
  }

  private static String digest(char character) {
    return "sha256:" + String.valueOf(character).repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Fixture(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalLaunchPreparationSnapshot preparation,
      CompleteLaunchBindingEvidence binding,
      FreshGameSessionTenantAssociation tenantAssociation) {}

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private TransactionDefinition startedWith;
    private int commitCount;
    private int rollbackCount;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitCount++;
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbackCount++;
      TransactionSynchronizationManager.clear();
    }
  }
}
