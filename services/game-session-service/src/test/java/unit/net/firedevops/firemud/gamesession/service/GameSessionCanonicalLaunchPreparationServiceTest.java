package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

  private final AuthoredWorldLaunchDescriptorClient descriptorClient =
      mock(AuthoredWorldLaunchDescriptorClient.class);
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository =
      mock(GameSessionCanonicalRealmCatalogRepository.class);
  private final GameSessionAuthoredWorldSourceRepository sourceRepository =
      mock(GameSessionAuthoredWorldSourceRepository.class);
  private final GameSessionCanonicalLaunchPreparationRepository preparationRepository =
      mock(GameSessionCanonicalLaunchPreparationRepository.class);
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

  private void stubNewPreparation(
      CreateCanonicalLaunchPreparationRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      IntakeReceipt intake,
      CanonicalRealmCatalogSnapshot catalog,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      CanonicalLaunchPreparationSnapshot snapshot) {
    when(preparationRepository.readByControlPlaneRequestId(NAMESPACE, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(snapshot));
    when(catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .thenReturn(Optional.of(catalog));
    when(sourceRepository.read(SOURCE_INTAKE_OPERATION, TENANT, catalog.worldSlug(), NAMESPACE))
        .thenReturn(Optional.of(intake));
    when(descriptorClient.resolve(descriptor.request()))
        .thenAnswer(
            ignored -> {
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
              return snapshot;
            });
  }

  private void resetRepositories() {
    org.mockito.Mockito.reset(
        descriptorClient, catalogRepository, sourceRepository, preparationRepository);
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
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request.descriptorRequest(catalog, source),
        "launch-descriptor-1",
        request.targetVersionIdPresent() ? request.targetVersionId() : 72,
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
