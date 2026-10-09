package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository.InvalidLaunchPreparationEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository.LaunchPreparationConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class GameSessionCanonicalLaunchPreparationServiceTest {
  private static final String NAMESPACE = "canonical-launch-test";
  private static final String CONTROL_PLANE_REQUEST = "launch-attempt-1";
  private static final UUID ACTOR = uuid(1);
  private static final UUID TENANT = uuid(2);
  private static final UUID SOURCE_OPERATION = uuid(3);
  private static final UUID SOURCE_INTAKE_OPERATION = uuid(4);
  private static final UUID INTAKE_REQUEST = uuid(5);
  private static final UUID REALM = uuid(6);
  private static final UUID CATALOG_REQUEST = uuid(7);
  private static final UUID RESERVATION_OWNER = uuid(20);
  private static final UUID ISSUANCE_ID = uuid(21);
  private static final UUID TOKEN_JTI = uuid(22);
  private static final UUID OWNER_ATTEMPT_ID = uuid(23);
  private static final UUID OWNER_MUTATION_ID = uuid(24);
  private static final UUID CLAIM_OWNER_ID = uuid(25);
  private static final String AUTHORIZATION_FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String LOGGING_WORKLOAD =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AuthoredWorldLaunchDescriptorClient descriptorClient =
      mock(AuthoredWorldLaunchDescriptorClient.class);
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository =
      mock(GameSessionCanonicalRealmCatalogRepository.class);
  private final GameSessionAuthoredWorldSourceRepository sourceRepository =
      mock(GameSessionAuthoredWorldSourceRepository.class);
  private final GameSessionCanonicalLaunchPreparationRepository preparationRepository =
      mock(GameSessionCanonicalLaunchPreparationRepository.class);
  private final GameSessionStartSessionOperatorAttemptRepository attemptRepository =
      mock(GameSessionStartSessionOperatorAttemptRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final GameSessionCanonicalLaunchPreparationService service =
      new GameSessionCanonicalLaunchPreparationService(
          descriptorClient,
          catalogRepository,
          sourceRepository,
          preparationRepository,
          transactionManager,
          NAMESPACE);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsAmbientTransactionBeforeAnyOwnerReadOrNetworkCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> service.prepare(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");

    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void rejectsAmbientTransactionBeforeCompleteBindingOwnerReadOrNetworkCall() {
    CanonicalLaunchPreparationSnapshot snapshot = preparedSnapshot();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> service.readCompleteBinding(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");

    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void readsCompleteBindingOnlyForExactCommittedPreparationAndPreservesBothSharedEvidenceValues() {
    CanonicalLaunchPreparationSnapshot snapshot = preparedSnapshot();
    CompleteLaunchBindingEvidence binding = completeBinding(snapshot.launchDescriptorEvidence());
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(snapshot));
    when(descriptorClient.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);

    assertThat(service.readCompleteBinding(snapshot)).isEqualTo(binding);

    ArgumentCaptor<GetLaunchDescriptorRequest> request =
        ArgumentCaptor.forClass(GetLaunchDescriptorRequest.class);
    verify(descriptorClient).getComplete(request.capture());
    GetLaunchDescriptorRequest selector = request.getValue();
    assertThat(UUID.fromString(selector.getRequestId())).isNotEqualTo(SOURCE_OPERATION);
    assertThat(selector)
        .isEqualTo(
            AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(
                new GetRequest(
                    UUID.fromString(selector.getRequestId()),
                    snapshot.launchDescriptorEvidence().request(),
                    snapshot.launchDescriptorEvidence().resultDigest())));
    assertThat(selector.getCanonicalTenantId()).isEqualTo(TENANT.toString());
    assertThat(selector.getWorldSlug()).isEqualTo(snapshot.launchDescriptorEvidence().worldSlug());
    assertThat(selector.getControlPlaneRequestId()).isEqualTo(CONTROL_PLANE_REQUEST);
    assertThat(selector.getExpectedRequestDigest())
        .isEqualTo(snapshot.launchDescriptorEvidence().requestDigest());
    assertThat(selector.getExpectedResultDigest())
        .isEqualTo(snapshot.launchDescriptorEvidence().resultDigest());
    verify(preparationRepository).readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
    verify(descriptorClient, never()).resolve(any());
    verify(descriptorClient, never()).get(any(GetRequest.class));
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void changedCommittedSourceFailsBeforeCompleteBindingNetworkRead() {
    CanonicalLaunchPreparationSnapshot requested = preparedSnapshot();
    AuthoredWorldSourceEvidence changedSource = source("emerald-grove");
    IntakeReceipt changedIntake = intake(changedSource);
    CanonicalRealmCatalogSnapshot changedCatalog = catalog(changedIntake);
    CanonicalLaunchPreparationSnapshot changedCommitted =
        snapshot(
            requested.request(),
            changedCatalog,
            changedIntake,
            descriptor(requested.request(), changedCatalog, changedSource));
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(changedCommitted));

    assertThatThrownBy(() -> service.readCompleteBinding(requested))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("changed before complete binding read");

    verify(preparationRepository).readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
    verifyNoInteractions(descriptorClient);
  }

  @Test
  void missingCommittedPreparationFailsBeforeCompleteBindingNetworkRead() {
    CanonicalLaunchPreparationSnapshot snapshot = preparedSnapshot();
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.readCompleteBinding(snapshot))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("missing before complete binding read");

    verifyNoInteractions(descriptorClient);
  }

  @Test
  void namespaceMismatchFailsBeforeCompleteBindingOwnerReadOrNetworkCall() {
    CanonicalLaunchPreparationSnapshot snapshot = preparedSnapshot();
    GameSessionCanonicalLaunchPreparationService otherNamespaceService =
        new GameSessionCanonicalLaunchPreparationService(
            descriptorClient,
            catalogRepository,
            sourceRepository,
            preparationRepository,
            transactionManager,
            NAMESPACE + "-other");

    assertThatThrownBy(() -> otherNamespaceService.readCompleteBinding(snapshot))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("namespace");

    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
  }

  @Test
  void changedCompleteDescriptorFailsAfterExactCommittedPreparationRead() {
    CanonicalLaunchPreparationSnapshot snapshot = preparedSnapshot();
    AuthoredWorldLaunchDescriptorEvidence original = snapshot.launchDescriptorEvidence();
    AuthoredWorldLaunchDescriptorEvidence changed =
        AuthoredWorldLaunchDescriptorEvidence.create(
            original.request(),
            original.launchDescriptorId() + "-changed",
            original.versionId(),
            original.scriptPatchVersionPresent(),
            original.scriptPatchVersion(),
            original.runtimeFlagsJson(),
            original.generationConfigRevision(),
            original.versionStateEpoch(),
            original.releaseBundleId(),
            original.publishedReleaseBundleRef(),
            original.remapSetIdPresent(),
            original.remapSetId());
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(snapshot));
    when(descriptorClient.getComplete(any(GetLaunchDescriptorRequest.class)))
        .thenReturn(completeBinding(changed));

    assertThatThrownBy(() -> service.readCompleteBinding(snapshot))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("differs from the committed launch descriptor");

    verify(preparationRepository).readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
    verify(descriptorClient).getComplete(any(GetLaunchDescriptorRequest.class));
  }

  @Test
  void resolvesThenGetsExactDescriptorOutsideShortOwnerTransactionAndReadsCommitBack() {
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(request, catalog, source);
    CanonicalLaunchPreparationSnapshot snapshot = snapshot(request, catalog, intake, descriptor);
    stubNewPreparation(request, source, intake, catalog, descriptor, snapshot);

    assertThat(service.prepare(request)).isEqualTo(snapshot);

    ArgumentCaptor<GetRequest> getRequest = ArgumentCaptor.forClass(GetRequest.class);
    verify(descriptorClient).resolve(descriptor.request());
    verify(descriptorClient).get(getRequest.capture());
    assertThat(getRequest.getValue().expectedRequest()).isEqualTo(descriptor.request());
    assertThat(getRequest.getValue().expectedResultDigest()).isEqualTo(descriptor.resultDigest());
    assertThat(getRequest.getValue().requestId()).isNotEqualTo(SOURCE_OPERATION);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.commitCount).isEqualTo(1);
    verify(preparationRepository).persistPrepared(request, catalog, intake, descriptor);
  }

  @Test
  void changedDurableCommitReadbackFailsWithoutRepairingTheStoredPreparation() {
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(request, catalog, source);
    CanonicalLaunchPreparationSnapshot written = snapshot(request, catalog, intake, descriptor);
    CanonicalLaunchPreparationSnapshot changedReadback =
        snapshot(request, catalog, intake, descriptor, uuid(14));
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(changedReadback));
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));
    when(descriptorClient.resolve(descriptor.request())).thenReturn(descriptor);
    when(descriptorClient.get(any(GetRequest.class))).thenReturn(descriptor);
    when(preparationRepository.persistPrepared(request, catalog, intake, descriptor))
        .thenReturn(written);

    assertThatThrownBy(() -> service.prepare(request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("changed during independent commit readback");

    verify(preparationRepository, times(1)).persistPrepared(request, catalog, intake, descriptor);
    verify(preparationRepository, times(2))
        .readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
  }

  @Test
  void exactRetryReturnsOriginalPreparationWithoutRepeatingSourceOrDescriptorCalls() {
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(request, catalog, source);
    CanonicalLaunchPreparationSnapshot original = snapshot(request, catalog, intake, descriptor);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(original));

    assertThat(service.prepare(request)).isEqualTo(original);

    verifyNoInteractions(descriptorClient, catalogRepository, sourceRepository);
    verify(preparationRepository).readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void authorizedExactRetryValidatesCurrentClaimBeforeReturningWithoutResolvingAgain()
      throws Exception {
    CreateCanonicalLaunchPreparationRequest request = request();
    CanonicalLaunchPreparationSnapshot original = preparedSnapshot();
    StartSessionPostAuthorizationExecutionTuple tuple = authorizedTuple(request);
    AttemptClaim claim = attemptClaim(request);
    AttemptSnapshot attempt = attemptSnapshot(tuple, accountProjection(tuple));
    when(attemptRepository.validateCurrentClaim(claim))
        .thenAnswer(
            ignored -> {
              assertOwnerTransaction();
              return attempt;
            });
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(original));

    assertThat(authorizedService().prepareAuthorized(claim, request)).isEqualTo(original);

    org.mockito.InOrder calls = inOrder(attemptRepository, preparationRepository);
    calls.verify(attemptRepository).validateCurrentClaim(claim);
    calls
        .verify(preparationRepository)
        .readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST);
    verifyNoInteractions(descriptorClient, catalogRepository, sourceRepository);
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
    assertThat(transactionManager.commitCount).isEqualTo(1);

    resetRepositories();
    when(attemptRepository.validateCurrentClaim(claim)).thenReturn(attempt);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(original));
    CreateCanonicalLaunchPreparationRequest changedSource =
        new CreateCanonicalLaunchPreparationRequest(
            request.controlPlaneRequestId(),
            request.actingAccountUuid(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.catalogCreationRequestId(),
            request.catalogRevision(),
            uuid(99),
            request.gameTemplateId(),
            request.requestedScriptPatchVersionPresent(),
            request.requestedScriptPatchVersion(),
            request.sourceVersionIdPresent(),
            request.sourceVersionId(),
            request.targetVersionIdPresent(),
            request.targetVersionId(),
            request.requestedRuntimeFlagsJsonPresent(),
            request.requestedRuntimeFlagsJson());

    assertThatThrownBy(() -> authorizedService().prepareAuthorized(claim, changedSource))
        .isInstanceOf(LaunchPreparationConflictException.class)
        .hasMessageContaining("changed");
    verifyNoInteractions(descriptorClient, catalogRepository, sourceRepository);
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
  }

  @Test
  void rejectsAuthorizedActorTenantTemplateAndDescriptorOverrideSubstitutionBeforeUpstreamCalls()
      throws Exception {
    CreateCanonicalLaunchPreparationRequest request = request();
    StartSessionPostAuthorizationExecutionTuple tuple = authorizedTuple(request);
    AttemptClaim claim = attemptClaim(request);
    AttemptSnapshot authorizedAttempt = attemptSnapshot(tuple, accountProjection(tuple));
    when(attemptRepository.validateCurrentClaim(claim)).thenReturn(authorizedAttempt);
    GameSessionCanonicalLaunchPreparationService authorizedService = authorizedService();

    assertThatThrownBy(
            () ->
                authorizedService.prepareAuthorized(
                    claim, requestVariant(request, uuid(98), TENANT, 71L, false, null)))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("actor, tenant, or template");
    assertThatThrownBy(
            () ->
                authorizedService.prepareAuthorized(
                    claim, requestVariant(request, ACTOR, uuid(97), 71L, false, null)))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("actor, tenant, or template");
    assertThatThrownBy(
            () ->
                authorizedService.prepareAuthorized(
                    claim, requestVariant(request, ACTOR, TENANT, 72L, false, null)))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("actor, tenant, or template");
    assertThatThrownBy(
            () ->
                authorizedService.prepareAuthorized(
                    claim, requestVariant(request, ACTOR, TENANT, 71L, true, 72L)))
        .isInstanceOf(LaunchPreparationConflictException.class)
        .hasMessageContaining("does not authorize launch descriptor overrides");

    verify(attemptRepository, times(4)).validateCurrentClaim(claim);
    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
  }

  @Test
  void authorizedPreparationChecksProjectionBeforeNetworkAndRevalidatesClaimAtCommit()
      throws Exception {
    CreateCanonicalLaunchPreparationRequest request = request();
    StartSessionPostAuthorizationExecutionTuple tuple = authorizedTuple(request);
    AttemptClaim claim = attemptClaim(request);
    AccountRedemptionProjection substitutedProjection =
        new AccountRedemptionProjection(
            "arfp/v1/other-key/" + "c".repeat(64),
            tuple.authorityEvidenceBundleBytes(),
            ISSUANCE_ID,
            23L);
    AttemptSnapshot substitutedAttempt = attemptSnapshot(tuple, substitutedProjection);
    when(attemptRepository.validateCurrentClaim(claim)).thenReturn(substitutedAttempt);

    assertThatThrownBy(() -> authorizedService().prepareAuthorized(claim, request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("differs from the original post-authorization tuple");
    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
    int commitsAfterRejectedProjection = transactionManager.commitCount;

    resetRepositories();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(request, catalog, source);
    CanonicalLaunchPreparationSnapshot prepared = snapshot(request, catalog, intake, descriptor);
    AttemptSnapshot exactAttempt = attemptSnapshot(tuple, accountProjection(tuple));
    when(attemptRepository.validateCurrentClaim(claim))
        .thenAnswer(
            ignored -> {
              assertOwnerTransaction();
              return exactAttempt;
            });
    stubNewPreparation(request, source, intake, catalog, descriptor, prepared, true);

    assertThat(authorizedService().prepareAuthorized(claim, request)).isEqualTo(prepared);
    verify(attemptRepository, times(2)).validateCurrentClaim(claim);
    assertThat(transactionManager.commitCount).isEqualTo(commitsAfterRejectedProjection + 2);
    verify(preparationRepository).persistPrepared(request, catalog, intake, descriptor);
  }

  @Test
  void changedRetryInputConflictsBeforeUpstreamResolutionOrMutation() {
    CreateCanonicalLaunchPreparationRequest originalRequest = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(originalRequest, catalog, source);
    CanonicalLaunchPreparationSnapshot original =
        snapshot(originalRequest, catalog, intake, descriptor);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(original));
    CreateCanonicalLaunchPreparationRequest changed =
        new CreateCanonicalLaunchPreparationRequest(
            CONTROL_PLANE_REQUEST,
            ACTOR,
            NAMESPACE,
            TENANT,
            REALM,
            CATALOG_REQUEST,
            1,
            SOURCE_INTAKE_OPERATION,
            71,
            true,
            "", // Present-empty remains distinct from absence.
            false,
            null,
            false,
            null,
            false,
            null);

    assertThatThrownBy(() -> service.prepare(changed))
        .isInstanceOf(LaunchPreparationConflictException.class)
        .hasMessageContaining("changed");

    verifyNoInteractions(descriptorClient, catalogRepository, sourceRepository);
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void catalogAndSourceSubstitutionFailBeforeDescriptorResolution() {
    CreateCanonicalLaunchPreparationRequest request = request();
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    CanonicalRealmCatalogSnapshot changedCatalog = catalog(intake(source()), uuid(88));
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(changedCatalog));

    assertThatThrownBy(() -> service.prepare(request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("exact committed initial catalog");
    verifyNoInteractions(descriptorClient, sourceRepository);

    resetRepositories();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source, uuid(89));
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));

    assertThatThrownBy(() -> service.prepare(request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("exact fresh source intake");
    verifyNoInteractions(descriptorClient);
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
  }

  @Test
  void namespaceTenantAndCatalogSelectorMismatchesFailBeforeDescriptorResolution() {
    CreateCanonicalLaunchPreparationRequest wrongNamespace =
        request(NAMESPACE + "-other", TENANT, CATALOG_REQUEST, 1);
    assertThatThrownBy(() -> service.prepare(wrongNamespace))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("namespace");
    verifyNoInteractions(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);

    resetRepositories();
    CreateCanonicalLaunchPreparationRequest wrongTenant =
        request(NAMESPACE, uuid(91), CATALOG_REQUEST, 1);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog(intake(source()))));

    assertThatThrownBy(() -> service.prepare(wrongTenant))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("exact committed initial catalog");
    verifyNoInteractions(descriptorClient, sourceRepository);

    resetRepositories();
    UUID changedCatalogRequestId = uuid(92);
    CreateCanonicalLaunchPreparationRequest wrongCatalog =
        request(NAMESPACE, TENANT, changedCatalogRequestId, 2);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    when(catalogRepository.readByRequest(NAMESPACE, changedCatalogRequestId))
        .thenReturn(Optional.of(catalog(intake(source()))));

    assertThatThrownBy(() -> service.prepare(wrongCatalog))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("exact committed initial catalog");
    verifyNoInteractions(descriptorClient, sourceRepository);
  }

  @Test
  void descriptorAndIndependentReadbackMustBothMatchCompleteSharedEvidence() {
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence.Request expected =
        request.descriptorRequest(catalog, source);
    AuthoredWorldLaunchDescriptorEvidence substituted =
        AuthoredWorldLaunchDescriptorEvidence.create(
            new AuthoredWorldLaunchDescriptorEvidence.Request(
                NAMESPACE,
                CONTROL_PLANE_REQUEST,
                TENANT,
                catalog.worldSlug(),
                uuid(90),
                source.evidenceDigest(),
                71,
                false,
                null,
                false,
                null,
                false,
                null,
                false,
                null),
            "descriptor-90",
            72,
            false,
            null,
            "{}",
            "generation-1",
            3,
            73,
            "bundle-73",
            false,
            null);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));
    when(descriptorClient.resolve(expected)).thenReturn(substituted);

    assertThatThrownBy(() -> service.prepare(request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("does not match");
    verify(descriptorClient, never()).get(any());
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());

    resetRepositories();
    AuthoredWorldLaunchDescriptorEvidence resolved = descriptor(request, catalog, source);
    AuthoredWorldLaunchDescriptorEvidence changedReadback =
        AuthoredWorldLaunchDescriptorEvidence.create(
            resolved.request(),
            "changed-descriptor-readback",
            74,
            false,
            null,
            "{}",
            "generation-1",
            3,
            73,
            "bundle-73",
            false,
            null);
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty());
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));
    when(descriptorClient.resolve(resolved.request()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return resolved;
            });
    when(descriptorClient.get(any(GetRequest.class)))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return changedReadback;
            });

    assertThatThrownBy(() -> service.prepare(request))
        .isInstanceOf(InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("readback differs");
    verify(preparationRepository, never()).persistPrepared(any(), any(), any(), any());
  }

  @Test
  void requestDigestBindsOptionalPresenceAndDescriptorDigest() {
    CreateCanonicalLaunchPreparationRequest absent = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    AuthoredWorldLaunchDescriptorEvidence absentDescriptor = descriptor(absent, catalog, source);
    CreateCanonicalLaunchPreparationRequest presentEmpty =
        new CreateCanonicalLaunchPreparationRequest(
            CONTROL_PLANE_REQUEST,
            ACTOR,
            NAMESPACE,
            TENANT,
            REALM,
            CATALOG_REQUEST,
            1,
            SOURCE_INTAKE_OPERATION,
            71,
            true,
            "",
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence presentEmptyDescriptor =
        descriptor(presentEmpty, catalog, source);

    assertThat(
            GameSessionCanonicalLaunchPreparationRepository.requestDigest(
                absent, catalog, intake, absentDescriptor))
        .isNotEqualTo(
            GameSessionCanonicalLaunchPreparationRepository.requestDigest(
                presentEmpty, catalog, intake, presentEmptyDescriptor));

    CanonicalRealmCatalogSnapshot changedCatalogPayload =
        new CanonicalRealmCatalogSnapshot(
            catalog.targetNamespace(),
            catalog.tenantId(),
            catalog.tenantSlug(),
            catalog.worldSlug(),
            catalog.realmId(),
            "changed-realm-slug",
            "Changed Realm Display Name",
            catalog.visible(),
            catalog.publicProduction(),
            catalog.stateScope(),
            uuid(93),
            catalog.characterCreationPolicy(),
            catalog.catalogRevision(),
            catalog.creationRequestId(),
            catalog.requestDigest(),
            catalog.receiptDigest(),
            intake);
    assertThat(
            GameSessionCanonicalLaunchPreparationRepository.requestDigest(
                absent, changedCatalogPayload, intake, absentDescriptor))
        .isNotEqualTo(
            GameSessionCanonicalLaunchPreparationRepository.requestDigest(
                absent, catalog, intake, absentDescriptor));
  }

  private GameSessionCanonicalLaunchPreparationService authorizedService() {
    return new GameSessionCanonicalLaunchPreparationService(
        descriptorClient,
        catalogRepository,
        sourceRepository,
        preparationRepository,
        attemptRepository,
        transactionManager,
        NAMESPACE);
  }

  private static void assertOwnerTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static AttemptClaim attemptClaim(CreateCanonicalLaunchPreparationRequest request) {
    return new AttemptClaim(
        NAMESPACE,
        request.controlPlaneRequestId(),
        OWNER_ATTEMPT_ID,
        OWNER_MUTATION_ID,
        CLAIM_OWNER_ID,
        29L);
  }

  private static CreateCanonicalLaunchPreparationRequest requestVariant(
      CreateCanonicalLaunchPreparationRequest original,
      UUID actor,
      UUID tenant,
      long gameTemplateId,
      boolean targetVersionPresent,
      Long targetVersion) {
    return new CreateCanonicalLaunchPreparationRequest(
        original.controlPlaneRequestId(),
        actor,
        original.targetNamespace(),
        tenant,
        original.realmId(),
        original.catalogCreationRequestId(),
        original.catalogRevision(),
        original.sourceIntakeOperationId(),
        gameTemplateId,
        false,
        null,
        false,
        null,
        targetVersionPresent,
        targetVersion,
        false,
        null);
  }

  private static AttemptSnapshot attemptSnapshot(
      StartSessionPostAuthorizationExecutionTuple tuple, AccountRedemptionProjection projection) {
    AttemptSnapshot snapshot = mock(AttemptSnapshot.class);
    when(snapshot.targetNamespace()).thenReturn(NAMESPACE);
    when(snapshot.controlPlaneRequestId()).thenReturn(tuple.controlPlaneRequestId());
    when(snapshot.canonicalTenantId()).thenReturn(TENANT);
    when(snapshot.ownerAttemptId()).thenReturn(OWNER_ATTEMPT_ID);
    when(snapshot.ownerMutationId()).thenReturn(OWNER_MUTATION_ID);
    when(snapshot.ownerFence()).thenReturn(29L);
    when(snapshot.phaseState()).thenReturn("OWNER_EXECUTION_PENDING");
    when(snapshot.postAuthorizationExecutionTuple()).thenReturn(tuple.canonicalBytes());
    when(snapshot.accountRedemptionProjection()).thenReturn(projection.canonicalBytes());
    return snapshot;
  }

  private static AccountRedemptionProjection accountProjection(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return new AccountRedemptionProjection(
        tuple.authorizationReferenceFingerprint(),
        tuple.authorityEvidenceBundleBytes(),
        bundle.issuanceOperationId(),
        Long.parseLong(tuple.issuanceFence()));
  }

  private static StartSessionPostAuthorizationExecutionTuple authorizedTuple(
      CreateCanonicalLaunchPreparationRequest request) throws IOException {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(request.gameTemplateId(), ACTOR),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Game Session source-qualified launch preparation");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            request.controlPlaneRequestId(), ACTOR, action);
    byte[] bundleBytes = authorityEvidenceBundle(preTuple);
    StartSessionAuthorityEvidenceBundle.BundleReference reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        LOGGING_WORKLOAD,
        AUTHORIZATION_FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        bundleBytes,
        reference);
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple preTuple) throws IOException {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", preTuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    preTuple.controlPlaneRequestId()),
            "mutationDigest", preTuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", preTuple.action().actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private void stubNewPreparation(
      CreateCanonicalLaunchPreparationRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      IntakeReceipt intake,
      CanonicalRealmCatalogSnapshot catalog,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      CanonicalLaunchPreparationSnapshot snapshot) {
    stubNewPreparation(request, sourceEvidence, intake, catalog, descriptor, snapshot, false);
  }

  private void stubNewPreparation(
      CreateCanonicalLaunchPreparationRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      IntakeReceipt intake,
      CanonicalRealmCatalogSnapshot catalog,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      CanonicalLaunchPreparationSnapshot snapshot,
      boolean verifyAuthorizedClaimRevalidation) {
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(snapshot));
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));
    when(descriptorClient.resolve(descriptor.request()))
        .thenAnswer(
            ignored -> {
              if (verifyAuthorizedClaimRevalidation) {
                verify(attemptRepository, times(1)).validateCurrentClaim(attemptClaim(request));
              }
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return descriptor;
            });
    when(descriptorClient.get(any(GetRequest.class)))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return descriptor;
            });
    when(preparationRepository.persistPrepared(request, catalog, intake, descriptor))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(descriptor.authoredWorldSourceOperationId())
                  .isEqualTo(sourceEvidence.operationId());
              if (verifyAuthorizedClaimRevalidation) {
                verify(attemptRepository, times(2)).validateCurrentClaim(attemptClaim(request));
              }
              return snapshot;
            });
  }

  private void resetRepositories() {
    org.mockito.Mockito.reset(
        descriptorClient,
        catalogRepository,
        sourceRepository,
        preparationRepository,
        attemptRepository);
  }

  private static CanonicalLaunchPreparationSnapshot preparedSnapshot() {
    CreateCanonicalLaunchPreparationRequest request = request();
    AuthoredWorldSourceEvidence source = source();
    IntakeReceipt intake = intake(source);
    CanonicalRealmCatalogSnapshot catalog = catalog(intake);
    return snapshot(request, catalog, intake, descriptor(request, catalog, source));
  }

  private static CreateCanonicalLaunchPreparationRequest request() {
    return request(NAMESPACE, TENANT, CATALOG_REQUEST, 1);
  }

  private static CreateCanonicalLaunchPreparationRequest request(
      String namespace, UUID tenant, UUID catalogRequest, long catalogRevision) {
    return new CreateCanonicalLaunchPreparationRequest(
        CONTROL_PLANE_REQUEST,
        ACTOR,
        namespace,
        tenant,
        REALM,
        catalogRequest,
        catalogRevision,
        SOURCE_INTAKE_OPERATION,
        71,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static AuthoredWorldSourceEvidence source() {
    return source("violet-wilds");
  }

  private static AuthoredWorldSourceEvidence source(String world) {
    String tenantSlug = "north-star";
    String displayName = "emerald-grove".equals(world) ? "Emerald Grove" : "Violet Wilds";
    UUID registrationId = uuid(10);
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationId, TENANT, tenantSlug, world, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationId,
            SOURCE_OPERATION,
            sourceRequestDigest,
            TENANT,
            tenantSlug,
            world,
            displayName,
            44,
            "fresh-source-key-44",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationId,
        SOURCE_OPERATION,
        sourceRequestDigest,
        TENANT,
        tenantSlug,
        world,
        displayName,
        44,
        "fresh-source-key-44",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static IntakeReceipt intake(AuthoredWorldSourceEvidence source) {
    return intake(source, SOURCE_INTAKE_OPERATION);
  }

  private static IntakeReceipt intake(AuthoredWorldSourceEvidence source, UUID operationId) {
    String requestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(INTAKE_REQUEST, source);
    return new IntakeReceipt(
        operationId,
        INTAKE_REQUEST,
        requestDigest,
        source,
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            operationId, requestDigest, source.evidenceDigest()));
  }

  private static CanonicalRealmCatalogSnapshot catalog(IntakeReceipt source) {
    return catalog(source, REALM);
  }

  private static CanonicalRealmCatalogSnapshot catalog(IntakeReceipt source, UUID realmId) {
    return new CanonicalRealmCatalogSnapshot(
        NAMESPACE,
        TENANT,
        source.source().tenantSlug(),
        source.source().worldSlug(),
        realmId,
        "violet-realm",
        "Violet Realm",
        true,
        true,
        "SHARED",
        uuid(12),
        "explicit-policy-v1",
        1,
        CATALOG_REQUEST,
        digest("c"),
        digest("d"),
        source);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      AuthoredWorldSourceEvidence source) {
    long targetVersionId =
        request.targetVersionIdPresent()
            ? java.util.Objects.requireNonNull(request.targetVersionId(), "targetVersionId")
            : 72L;
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request.descriptorRequest(catalog, source),
        "launch-descriptor-1",
        targetVersionId,
        request.requestedScriptPatchVersionPresent(),
        request.requestedScriptPatchVersion(),
        "{}",
        "generation-1",
        3,
        73,
        "bundle-73",
        false,
        null);
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    String publishCommitId = "publish-commit-902";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        publishCommitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest("d") : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence releaseAttestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            uuid(15),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:tenant:version:request",
            publishCommitId,
            participants,
            digest("b"),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, releaseAttestation);
  }

  private static CanonicalLaunchPreparationSnapshot snapshot(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return snapshot(request, catalog, source, descriptor, uuid(13));
  }

  private static CanonicalLaunchPreparationSnapshot snapshot(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      UUID operationId) {
    String requestDigest =
        GameSessionCanonicalLaunchPreparationRepository.requestDigest(
            request, catalog, source, descriptor);
    return new CanonicalLaunchPreparationSnapshot(
        operationId,
        request,
        catalog,
        source,
        descriptor,
        requestDigest,
        GameSessionCanonicalLaunchPreparationRepository.receiptDigest(
            operationId, request, catalog, source, descriptor, requestDigest));
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private TransactionDefinition startedWith;
    private int commitCount;

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
      TransactionSynchronizationManager.clear();
    }
  }
}
