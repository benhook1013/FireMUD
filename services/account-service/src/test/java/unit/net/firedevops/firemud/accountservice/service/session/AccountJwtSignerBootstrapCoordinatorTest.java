package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessPromotionProof;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedPromotion;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksPrepublicationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.BootstrapOperationException;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.BootstrapProgress;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.FailureCode;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.Stage;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountJwtSignerBootstrapCoordinatorTest {
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String MATERIALIZER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final String API_DIGEST = "d".repeat(64);
  private static final String API_REVISION = "api-revision-1";
  private static final String PUBLIC_JWKS = "{\"keys\":[]}";

  @Test
  void repeatedEnrollmentReturnsOnlyExactPinsWithoutSelectingAGeneration() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    ConfigMapSnapshot changedSnapshot =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            "42",
            Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, "{\"keys\":[{}]}"));
    when(fixture.client().observe()).thenReturn(fixture.snapshot(), changedSnapshot);

    var first = fixture.coordinator().enrollOnce();
    var retry = fixture.coordinator().enrollOnce();

    assertThat(first).isEqualTo(retry);
    assertThat(first.environmentId()).isEqualTo("prod");
    assertThat(first.clusterId()).isEqualTo("prod-cluster-1");
    assertThat(first.namespace()).isEqualTo("firemud-prod");
    assertThat(first.expectedClusterIncarnationUid()).isEqualTo(CLUSTER_UID);
    assertThat(first.expectedNamespaceUid()).isEqualTo(NAMESPACE_UID);
    assertThat(first.materializerTrustBindingDigest()).isEqualTo(fixture.binding().bindingDigest());
    assertThat(first.materializerTrustConfigRevision())
        .isEqualTo(fixture.binding().configRevision());
    assertThat(first.apiBindingDigest()).isEqualTo(API_DIGEST);
    assertThat(first.apiConfigRevision()).isEqualTo(API_REVISION);
    assertThat(first.apiServerUrl()).isEqualTo("https://kubernetes.default.svc:6443/");
    assertThat(first.apiTlsServerName()).isEqualTo("kubernetes.default.svc");
    assertThat(first.apiServingCaSha256()).isEqualTo("b".repeat(64));
    assertThat(first.apiExpectedUsername())
        .isEqualTo("system:serviceaccount:firemud-prod:account-service");
    assertThat(first.publicConfigMapName()).isEqualTo("jwt-jwks");
    assertThat(first.publicConfigMapUid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(first.publicConfigMapResourceVersion()).isEqualTo("41");
    assertThat(first.publicConfigMapSnapshotDigest())
        .isEqualTo(
            AccountJwtJwksPublicationRepository.snapshotDigest(
                Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, PUBLIC_JWKS)));

    ArgumentCaptor<EnrollmentIdentity> enrollment =
        ArgumentCaptor.forClass(EnrollmentIdentity.class);
    verify(fixture.repository(), times(2))
        .initialize(eq(fixture.binding().accountBinding()), enrollment.capture());
    assertThat(enrollment.getAllValues())
        .allSatisfy(
            value -> {
              assertThat(value.expectedClusterIncarnationUid()).isEqualTo(CLUSTER_UID);
              assertThat(value.expectedNamespaceUid()).isEqualTo(NAMESPACE_UID);
              assertThat(value.materializerTrustBindingDigest())
                  .isEqualTo(fixture.binding().bindingDigest());
              assertThat(value.materializerTrustConfigRevision())
                  .isEqualTo(fixture.binding().configRevision());
              assertThat(value.apiBindingDigest()).isEqualTo(API_DIGEST);
              assertThat(value.apiConfigRevision()).isEqualTo(API_REVISION);
              assertThat(value.publicConfigMapUid()).isEqualTo(CONFIG_MAP_UID);
            });
    assertThat(enrollment.getAllValues().get(0).publicConfigMapResourceVersion()).isEqualTo("41");
    assertThat(enrollment.getAllValues().get(1).publicConfigMapResourceVersion()).isEqualTo("42");
    assertThat(enrollment.getAllValues().get(0).publicConfigMapSnapshotDigest())
        .isEqualTo(
            AccountJwtJwksPublicationRepository.snapshotDigest(
                Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, PUBLIC_JWKS)));
    assertThat(enrollment.getAllValues().get(1).publicConfigMapSnapshotDigest())
        .isEqualTo(
            AccountJwtJwksPublicationRepository.snapshotDigest(
                Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, "{\"keys\":[{}]}")));
    assertThat(first.publicConfigMapResourceVersion()).isEqualTo("41");
    assertThat(retry.publicConfigMapResourceVersion()).isEqualTo("41");
    verify(fixture.repository(), never()).ensureCurrentGenerationRequest(any(), any());
    verify(fixture.repository(), never()).readCurrentGenerationRequest(any(), any());
    verify(fixture.prepublication(), never()).prepublishCurrentGeneration();
    verify(fixture.prepublication(), never()).observeCurrentMountedCorrespondence();
  }

  @Test
  void rejectsMismatchedProtectedBindingsBeforeEnrollmentOrRemoteObservation() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    when(fixture.client().identity())
        .thenReturn(
            new BindingIdentity(
                API_DIGEST,
                API_REVISION,
                "prod",
                "different-cluster",
                "firemud-prod",
                CLUSTER_UID,
                NAMESPACE_UID,
                "https://kubernetes.default.svc:6443/",
                "kubernetes.default.svc",
                "a".repeat(64),
                "system:serviceaccount:firemud-prod:account-service"));

    assertThatThrownBy(fixture.coordinator()::enrollOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.PROTECTED_BINDING_MISMATCH);

    verify(fixture.client(), never()).observe();
    verifyNoInteractions(fixture.repository(), fixture.prepublication());
  }

  @Test
  void ambiguousLiveConfigMapObservationDoesNotInitializeOrSelectLifecycleState() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    when(fixture.client().observe())
        .thenThrow(new AccountJwtJwksConfigMapClient.UncertainOutcomeException());

    assertThatThrownBy(fixture.coordinator()::enrollOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.PUBLIC_CONFIG_MAP_UNAVAILABLE);

    verifyNoInteractions(fixture.repository(), fixture.prepublication());
  }

  @Test
  void accountStateAmbiguityDoesNotSelectOrPublishGeneration() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    when(fixture
            .repository()
            .initialize(eq(fixture.binding().accountBinding()), any(EnrollmentIdentity.class)))
        .thenThrow(
            new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
                "test ambiguity"));

    assertThatThrownBy(fixture.coordinator()::enrollOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.ACCOUNT_STATE_AMBIGUOUS);

    verify(fixture.repository(), never()).ensureCurrentGenerationRequest(any(), any());
    verify(fixture.repository(), never()).readCurrentGenerationRequest(any(), any());
    verifyNoInteractions(fixture.prepublication());
  }

  @Test
  void materializerTrustIsRecheckedBeforeTheEnrollmentTransactionCompletes() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    Binding changedBinding = materializerBinding("trust-revision-2");
    when(fixture.materializerTrust().current())
        .thenReturn(
            Optional.of(fixture.binding()),
            Optional.of(fixture.binding()),
            Optional.of(fixture.binding()),
            Optional.of(changedBinding));

    assertThatThrownBy(fixture.coordinator()::enrollOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.PROTECTED_BINDING_CHANGED);

    verify(fixture.repository())
        .initialize(eq(fixture.binding().accountBinding()), any(EnrollmentIdentity.class));
    verify(fixture.repository(), never()).ensureCurrentGenerationRequest(any(), any());
    verify(fixture.repository(), never()).readCurrentGenerationRequest(any(), any());
    verifyNoInteractions(fixture.prepublication());
  }

  @Test
  void validatorAcceptanceRemainsAnExplicitBlockerAfterPublicationAndMountCorrespondence() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID operationId = fixture.request().operationId();
    var publication = mock(AccountJwtJwksPublicationRepository.PublicationReceipt.class);
    var mounted = mock(AccountJwtJwksPublicationRepository.MountObservation.class);
    when(publication.operationId()).thenReturn(operationId);
    when(mounted.operationId()).thenReturn(operationId);
    when(fixture
            .repository()
            .readCurrentGenerationRequest(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return fixture.request();
            });
    when(fixture.prepublication().prepublishCurrentGeneration())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return publication;
            });
    when(fixture.prepublication().observeCurrentMountedCorrespondence())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return mounted;
            });

    BootstrapProgress progress =
        fixture.coordinator().reconcileCurrentGenerationOnce(fixture.request().operationId());

    assertThat(progress.stage()).isEqualTo(Stage.BLOCKED_FOR_VALIDATOR_ACCEPTANCE);
    assertThat(progress.generationReadbackComplete()).isTrue();
    assertThat(progress.publicKeyPrepublished()).isTrue();
    assertThat(progress.mountedKeyCorrespondence()).isTrue();
    assertThat(progress.validatorInventoryStatus()).isEqualTo("PARTIAL_UNCONFIRMED");
    assertThat(progress.validatorAcceptanceComplete()).isFalse();
    assertThat(progress.signerPromotionCommitted()).isFalse();
    assertThat(progress.issuanceReady()).isFalse();
    assertThat(progress.blockers())
        .containsExactly(
            FailureCode.VALIDATOR_ACCEPTANCE_INCOMPLETE,
            FailureCode.SIGNER_PROMOTION_NOT_ATTEMPTED);
    verify(fixture.repository(), never()).initialize(any(), any());
    verify(fixture.repository(), never()).ensureCurrentGenerationRequest(any(), any());
  }

  @Test
  void remoteConfigMapReadOccursOutsideAccountTransaction() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET, insideTransaction);
    when(fixture.client().observe())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return fixture.snapshot();
            });

    fixture.coordinator().enrollOnce();

    assertThat(insideTransaction.get()).isFalse();
  }

  @Test
  void emptyInitialEnrollmentIsNoPromotionUntilAccountSelectsGeneration() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    DesiredState initial =
        new DesiredState(
            fixture.binding().accountBinding(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(enrollment(fixture.binding())));
    when(fixture.repository().readEnrollmentState(fixture.binding().accountBinding()))
        .thenReturn(Optional.of(initial));

    assertThat(fixture.coordinator().prepareCurrentPromotionOnce()).isEmpty();

    verify(fixture.repository()).readEnrollmentState(fixture.binding().accountBinding());
    verify(fixture.repository(), never()).readCurrentGenerationRequest(any(), any());
    verify(fixture.prepublication(), never()).prepublishCurrentGeneration();
    verify(fixture.inventorySource(), never()).observe(any());
  }

  @Test
  void validEarlyGenerationPhasesHaveNoPromotionUntilGenerationIsRecorded() {
    for (GenerationPhase phase :
        List.of(GenerationPhase.OBSERVE_PRIVATE_SECRET, GenerationPhase.GENERATE_PENDING)) {
      AtomicBoolean insideTransaction = new AtomicBoolean();
      Fixture fixture = fixture(phase, insideTransaction);
      stubCurrentGenerationRequest(fixture, insideTransaction);

      assertThat(fixture.coordinator().prepareCurrentPromotionOnce()).isEmpty();

      verify(fixture.repository())
          .readCurrentGenerationRequest(
              fixture.binding().accountBinding(), trustFence(fixture.binding()));
      verify(fixture.prepublication(), never()).prepublishCurrentGeneration();
      verify(fixture.inventorySource(), never()).observe(any());
    }
  }

  @Test
  void missingEnrollmentReadbackIsQuarantinedRatherThanTreatedAsNoPromotion() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    when(fixture.repository().readEnrollmentState(fixture.binding().accountBinding()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(fixture.coordinator()::prepareCurrentPromotionOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.ACCOUNT_STATE_AMBIGUOUS);

    verify(fixture.repository(), never()).readCurrentGenerationRequest(any(), any());
    verify(fixture.prepublication(), never()).prepublishCurrentGeneration();

    Fixture faulted = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    when(faulted.repository().readEnrollmentState(faulted.binding().accountBinding()))
        .thenThrow(
            new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
                "test owner read failure"));
    assertThatThrownBy(faulted.coordinator()::prepareCurrentPromotionOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
    verify(faulted.repository(), never()).readCurrentGenerationRequest(any(), any());
  }

  @Test
  void unavailableReadinessOwnerIsNotReportedAsNoPromotion() {
    Fixture fixture = fixture(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    AccountJwtSignerBootstrapCoordinator withoutReadinessOwner =
        new AccountJwtSignerBootstrapCoordinator(
            fixture.materializerTrust(),
            fixture.client(),
            fixture.repository(),
            fixture.prepublication(),
            new TransactionTemplate(new InertTransactionManager(new AtomicBoolean())));

    assertThatThrownBy(withoutReadinessOwner::prepareCurrentPromotionOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);

    verifyNoInteractions(fixture.client(), fixture.repository(), fixture.prepublication());
  }

  @Test
  void partialReadinessNeverEntersPreparedAndRefreshesExternalEvidenceBeforeSql() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID operationId = fixture.request().operationId();
    stubCurrentGenerationRequest(fixture, insideTransaction);
    stubPublicationAndMountRefresh(fixture, insideTransaction);
    ObservationContext context = initialObservationContext(fixture.request());
    when(fixture
            .readinessRepository()
            .readCurrentInventoryObservationContext(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return context;
            });
    when(fixture.inventorySource().observe(context))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return fixture.inventorySnapshot();
            });
    when(fixture
            .readinessRepository()
            .readCurrentPromotionPrerequisites(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId),
                eq(fixture.inventorySnapshot())))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return Optional.empty();
            });

    assertThat(fixture.coordinator().prepareCurrentPromotionOnce()).isEmpty();

    verify(fixture.prepublication()).prepublishCurrentGeneration();
    verify(fixture.prepublication()).observeCurrentMountedCorrespondence();
    verify(fixture.readinessRepository())
        .readCurrentInventoryObservationContext(
            fixture.binding().accountBinding(), trustFence(fixture.binding()), operationId);
    verify(fixture.inventorySource()).observe(context);
    verify(fixture.repository(), never()).prepareCurrentGeneration(any(), any(), any(), any());
    assertThat(insideTransaction.get()).isFalse();
  }

  @Test
  void concurrentActiveSignerInvalidatesInitialCandidateContextBeforePreparation() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID operationId = fixture.request().operationId();
    stubCurrentGenerationRequest(fixture, insideTransaction);
    stubPublicationAndMountRefresh(fixture, insideTransaction);
    ObservationContext initialContext = initialObservationContext(fixture.request());
    when(fixture
            .readinessRepository()
            .readCurrentInventoryObservationContext(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId)))
        .thenReturn(initialContext);
    when(fixture.inventorySource().observe(initialContext)).thenReturn(fixture.inventorySnapshot());
    when(fixture
            .readinessRepository()
            .readCurrentPromotionPrerequisites(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId),
                eq(fixture.inventorySnapshot())))
        .thenThrow(
            new net.firedevops.firemud.accountservice.service.session
                .AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException());

    assertThatThrownBy(fixture.coordinator()::prepareCurrentPromotionOnce)
        .isInstanceOf(
            net.firedevops.firemud.accountservice.service.session
                .AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);

    verify(fixture.inventorySource()).observe(initialContext);
    verify(fixture.repository(), never()).prepareCurrentGeneration(any(), any(), any(), any());
    assertThat(insideTransaction.get()).isFalse();
  }

  @Test
  void completeOwnerProofPreparesOnlyItsExactCurrentGenerationOnce() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID operationId = fixture.request().operationId();
    stubCurrentGenerationRequest(fixture, insideTransaction);
    stubPublicationAndMountRefresh(fixture, insideTransaction);
    ObservationContext context = initialObservationContext(fixture.request());
    when(fixture
            .readinessRepository()
            .readCurrentInventoryObservationContext(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId)))
        .thenReturn(context);
    when(fixture.inventorySource().observe(context))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return fixture.inventorySnapshot();
            });

    ReadinessPromotionProof proof = mock(ReadinessPromotionProof.class);
    GenerationResult generation = mock(GenerationResult.class);
    when(generation.operationId()).thenReturn(operationId);
    AccountJwtJwksPublicationRepository.PromotionPublicationEvidence publication =
        mock(AccountJwtJwksPublicationRepository.PromotionPublicationEvidence.class);
    var intent = mock(AccountJwtJwksPublicationRepository.PrepublicationIntent.class);
    var receipt = mock(AccountJwtJwksPublicationRepository.PublicationReceipt.class);
    var mounted = mock(AccountJwtJwksPublicationRepository.MountObservation.class);
    when(intent.apiBindingDigest()).thenReturn(API_DIGEST);
    when(intent.apiConfigRevision()).thenReturn(API_REVISION);
    when(intent.configMapUid()).thenReturn(CONFIG_MAP_UID);
    when(intent.intentDigest()).thenReturn("1".repeat(64));
    when(intent.jwksJson()).thenReturn(PUBLIC_JWKS);
    when(receipt.observedResourceVersion()).thenReturn("42");
    when(receipt.receiptDigest()).thenReturn("2".repeat(64));
    when(mounted.observationDigest()).thenReturn("3".repeat(64));
    when(publication.intent()).thenReturn(intent);
    when(publication.receipt()).thenReturn(receipt);
    when(publication.mountedCorrespondence()).thenReturn(mounted);
    var plan = mock(AccountJwtReadinessProbeRepository.ReadinessProbePlan.class);
    when(plan.planDigest()).thenReturn("4".repeat(64));
    when(proof.generationResult()).thenReturn(generation);
    when(proof.publication()).thenReturn(publication);
    when(proof.plan()).thenReturn(plan);
    when(proof.readinessEvidenceDigest()).thenReturn("5".repeat(64));
    when(fixture
            .readinessRepository()
            .readCurrentPromotionPrerequisites(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(operationId),
                eq(fixture.inventorySnapshot())))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return Optional.of(proof);
            });
    EnrollmentIdentity enrollment = enrollment(fixture.binding());
    when(fixture.repository().read(fixture.binding().accountBinding()))
        .thenReturn(
            new DesiredState(
                fixture.binding().accountBinding(),
                2,
                Optional.empty(),
                Optional.empty(),
                Optional.of(operationId),
                Optional.empty(),
                Optional.of(enrollment)));
    UUID promotionId = UUID.randomUUID();
    PreparedPromotion prepared = new PreparedPromotion(promotionId, "6".repeat(64), "PREPARED");
    PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
    when(promotion.operationId()).thenReturn(promotionId);
    when(promotion.requestDigest()).thenReturn(prepared.requestDigest());
    when(promotion.generationOperationId()).thenReturn(operationId);
    PreparedGenerationEvidence readback = mock(PreparedGenerationEvidence.class);
    when(readback.promotion()).thenReturn(promotion);
    when(readback.generationResult()).thenReturn(generation);
    when(fixture
            .repository()
            .readPreparedGenerationForRecovery(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return readback;
            });
    when(fixture
            .repository()
            .prepareCurrentGeneration(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                any(PromotionPreparation.class),
                eq(proof)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return prepared;
            });

    assertThat(fixture.coordinator().prepareCurrentPromotionOnce()).contains(prepared);
    assertThat(prepared.operationId()).isNotEqualTo(operationId);

    ArgumentCaptor<PromotionPreparation> preparation =
        ArgumentCaptor.forClass(PromotionPreparation.class);
    verify(fixture.repository(), times(1))
        .prepareCurrentGeneration(
            eq(fixture.binding().accountBinding()),
            eq(trustFence(fixture.binding())),
            preparation.capture(),
            eq(proof));
    assertThat(preparation.getValue().generationOperationId()).isEqualTo(operationId);
    assertThat(preparation.getValue().generationResult()).isSameAs(generation);
    assertThat(preparation.getValue().enrollmentIdentity()).isEqualTo(enrollment);
    assertThat(preparation.getValue().readinessPlanDigest()).isEqualTo("4".repeat(64));
    assertThat(preparation.getValue().readinessEvidenceDigest()).isEqualTo("5".repeat(64));
    assertThat(insideTransaction.get()).isFalse();

    when(promotion.generationOperationId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(fixture.coordinator()::prepareCurrentPromotionOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(error -> ((BootstrapOperationException) error).failureCode())
        .isEqualTo(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
  }

  @Test
  void missingInventorySourceFailsClosedWithoutPreparation() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    when(fixture.inventorySourceProvider().getIfAvailable()).thenReturn(null);
    stubCurrentGenerationRequest(fixture, insideTransaction);
    stubPublicationAndMountRefresh(fixture, insideTransaction);

    assertThatThrownBy(fixture.coordinator()::prepareCurrentPromotionOnce)
        .isInstanceOf(BootstrapOperationException.class)
        .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
        .isEqualTo(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);

    verify(fixture.repository(), never()).prepareCurrentGeneration(any(), any(), any(), any());
  }

  @Test
  void preparedPromotionDoesNotPublishOrCommitWithoutFreshCompleteReadinessProof() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID promotionId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID generationOperationId = fixture.request().operationId();
    String generationOperationDigest = fixture.request().operationDigest();
    PrivatePromotionObservation observation =
        new PrivatePromotionObservation(
            promotionId,
            "f".repeat(64),
            generationOperationId,
            generationOperationDigest,
            "44444444-4444-4444-8444-444444444444",
            "13",
            "14",
            new AccountJwtSignerDesiredStateRepository.PublicKeyIdentity(
                "1", "test-kid", "9".repeat(64)),
            Optional.empty(),
            List.of("current"));
    PrivatePromotionReceipt privateReceipt =
        new PrivatePromotionReceipt(promotionId, generationOperationId, "14", "8".repeat(64));
    PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
    when(promotion.status()).thenReturn("PREPARED");
    when(promotion.operationId()).thenReturn(promotionId);
    when(promotion.requestDigest()).thenReturn("f".repeat(64));
    when(promotion.generationOperationId()).thenReturn(generationOperationId);
    when(promotion.generationOperationDigest()).thenReturn(generationOperationDigest);
    when(promotion.expectedPreviousActive()).thenReturn(Optional.empty());
    when(promotion.expectedPublishedActive()).thenReturn(Optional.empty());
    when(promotion.privatePromotionDispatched()).thenReturn(true);
    when(promotion.privatePromotionReceiptDigest())
        .thenReturn(Optional.of(privateReceipt.receiptDigest()));
    PreparedGenerationEvidence prepared = mock(PreparedGenerationEvidence.class);
    when(prepared.promotion()).thenReturn(promotion);
    when(fixture
            .repository()
            .recordPrivatePromotionResult(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(observation)))
        .thenReturn(privateReceipt);
    when(fixture
            .repository()
            .readPreparedGenerationForRecovery(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenReturn(prepared);
    ObservationContext context =
        initialObservationContext(generationOperationId, generationOperationDigest);
    when(fixture.inventorySource().observe(context))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isFalse();
              return fixture.inventorySnapshot();
            });
    when(fixture.prepublication().observePreparedCurrentKeyCorrespondence(promotion))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isFalse();
              return fixture.snapshot();
            });
    when(fixture
            .readinessRepository()
            .readPromotionProof(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(generationOperationId),
                same(fixture.inventorySnapshot())))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> fixture.coordinator().reconcilePreparedPromotionOnce(observation, privateReceipt))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException.class)
        .hasMessageContaining("Fresh complete Account readiness proof is unavailable");

    verify(fixture.client(), never()).publishActiveProjection(any(), any(), any());
    verify(fixture.repository(), never()).recordActiveJwksPromotionResult(any(), any(), any());
    verify(fixture.repository(), never()).commitPreparedPromotion(any(), any(), any(), any());
    assertThat(insideTransaction.get()).isFalse();
  }

  @Test
  void exactCommittedPrivateReceiptReplayDoesNotRepeatExternalMutation() {
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED);
    UUID promotionId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    PrivatePromotionObservation observation =
        new PrivatePromotionObservation(
            promotionId,
            "f".repeat(64),
            fixture.request().operationId(),
            fixture.request().operationDigest(),
            "44444444-4444-4444-8444-444444444444",
            "13",
            "14",
            new AccountJwtSignerDesiredStateRepository.PublicKeyIdentity(
                "1", "test-kid", "9".repeat(64)),
            Optional.empty(),
            List.of("current"));
    PrivatePromotionReceipt receipt =
        new PrivatePromotionReceipt(
            promotionId, fixture.request().operationId(), "14", "8".repeat(64));
    PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
    when(promotion.operationId()).thenReturn(promotionId);
    CommittedSignerEvidence committed = mock(CommittedSignerEvidence.class);
    when(committed.privateReceipt()).thenReturn(receipt);
    when(committed.promotion()).thenReturn(promotion);
    when(fixture
            .repository()
            .recordPrivatePromotionResult(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(observation)))
        .thenReturn(receipt);
    when(fixture
            .repository()
            .readPreparedGenerationForRecovery(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenThrow(new AccountJwtSignerDesiredStateRepository.NoPreparedPromotionException());
    when(fixture
            .repository()
            .readCurrentCommittedSigner(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(API_DIGEST),
                eq(API_REVISION)))
        .thenReturn(Optional.of(committed));

    fixture.coordinator().reconcilePreparedPromotionOnce(observation, receipt);

    verify(fixture.repository())
        .readCurrentCommittedSigner(
            fixture.binding().accountBinding(),
            trustFence(fixture.binding()),
            API_DIGEST,
            API_REVISION);
    verify(fixture.inventorySourceProvider(), never()).getIfAvailable();
    verifyNoInteractions(fixture.prepublication());
    verify(fixture.client(), never()).publishActiveProjection(any(), any(), any());
    verify(fixture.repository(), never()).recordActiveJwksPromotionResult(any(), any(), any());
    verify(fixture.repository(), never()).commitPreparedPromotion(any(), any(), any(), any());
  }

  @Test
  void preparedPromotionCasAndCommitAreSeparatedByIndependentProofTransactions() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    Fixture fixture = fixture(GenerationPhase.GENERATION_RECORDED, insideTransaction);
    UUID promotionId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID generationOperationId = fixture.request().operationId();
    String generationOperationDigest = fixture.request().operationDigest();
    PrivatePromotionObservation observation =
        new PrivatePromotionObservation(
            promotionId,
            "f".repeat(64),
            generationOperationId,
            generationOperationDigest,
            "44444444-4444-4444-8444-444444444444",
            "13",
            "14",
            new AccountJwtSignerDesiredStateRepository.PublicKeyIdentity(
                "1", "test-kid", "9".repeat(64)),
            Optional.empty(),
            List.of("current"));
    PrivatePromotionReceipt privateReceipt =
        new PrivatePromotionReceipt(promotionId, generationOperationId, "14", "8".repeat(64));
    PromotionOperationEvidence beforeCas =
        promotionEvidence(fixture, promotionId, privateReceipt, Optional.empty());
    String activeMarker = "{\"phase\":\"ACTIVE\"}";
    ActiveJwksPromotionReceipt activeReceipt =
        new ActiveJwksPromotionReceipt(
            promotionId,
            CONFIG_MAP_UID,
            "42",
            "43",
            AccountJwtJwksPublicationRepository.publicDataDigest(PUBLIC_JWKS, activeMarker),
            "6".repeat(64));
    PromotionOperationEvidence afterCas =
        promotionEvidence(fixture, promotionId, privateReceipt, Optional.of(activeReceipt));
    PreparedGenerationEvidence preparedBefore = mock(PreparedGenerationEvidence.class);
    when(preparedBefore.promotion()).thenReturn(beforeCas);
    PreparedGenerationEvidence preparedAfter = mock(PreparedGenerationEvidence.class);
    when(preparedAfter.promotion()).thenReturn(afterCas);
    when(fixture
            .repository()
            .recordPrivatePromotionResult(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(observation)))
        .thenReturn(privateReceipt);
    when(fixture
            .repository()
            .readPreparedGenerationForRecovery(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenReturn(preparedBefore, preparedBefore, preparedAfter, preparedAfter);
    ObservationContext context =
        new ObservationContext(
            ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES,
            generationOperationId,
            generationOperationDigest);
    InventorySnapshot inventory = fixture.inventorySnapshot();
    AtomicBoolean activeCasReturned = new AtomicBoolean();
    AtomicBoolean activeReceiptRecorded = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger inventoryReads =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger proofReads =
        new java.util.concurrent.atomic.AtomicInteger();
    when(fixture.inventorySource().observe(context))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isFalse();
              inventoryReads.incrementAndGet();
              return inventory;
            });
    String prepublishedMarker = "{\"phase\":\"PREPUBLISHED\"}";
    ConfigMapSnapshot beforeSnapshot =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            "42",
            Map.of(
                "jwks.json", PUBLIC_JWKS,
                "jwt-generation.json", prepublishedMarker,
                "unrelated.txt", "preserved"));
    ConfigMapSnapshot afterSnapshot =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            "43",
            Map.of(
                "jwks.json", PUBLIC_JWKS,
                "jwt-generation.json", activeMarker,
                "unrelated.txt", "preserved"));
    when(fixture.prepublication().observePreparedCurrentKeyCorrespondence(beforeCas))
        .thenReturn(beforeSnapshot);
    when(fixture.prepublication().observePreparedCurrentKeyCorrespondence(afterCas))
        .thenReturn(afterSnapshot);
    when(fixture.client().publishActiveProjection(eq(beforeSnapshot), eq("42"), any()))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isFalse();
              assertThat(inventoryReads.get()).isEqualTo(1);
              assertThat(proofReads.get()).isEqualTo(1);
              activeCasReturned.set(true);
              return new CasObservation(
                  CONFIG_MAP_UID,
                  "42",
                  "43",
                  Map.of("jwks.json", PUBLIC_JWKS, "jwt-generation.json", activeMarker),
                  afterSnapshot.data(),
                  AccountJwtJwksConfigMapClient.Outcome.APPLIED);
            });
    when(fixture.repository().recordActiveJwksPromotionResult(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isTrue();
              assertThat(activeCasReturned.get()).isTrue();
              activeReceiptRecorded.set(true);
              return activeReceipt;
            });
    when(fixture
            .readinessRepository()
            .readPromotionProof(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(generationOperationId),
                same(inventory)))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isTrue();
              proofReads.incrementAndGet();
              return Optional.of(mock(ReadinessPromotionProof.class));
            });
    CommittedSignerEvidence committed = mock(CommittedSignerEvidence.class);
    PromotionOperationEvidence committedOperation = mock(PromotionOperationEvidence.class);
    when(committedOperation.operationId()).thenReturn(promotionId);
    when(committedOperation.status()).thenReturn("COMMITTED");
    when(committed.promotion()).thenReturn(committedOperation);
    when(fixture
            .repository()
            .commitPreparedPromotion(
                eq(fixture.binding().accountBinding()),
                eq(trustFence(fixture.binding())),
                eq(promotionId),
                any(ReadinessPromotionProof.class)))
        .thenAnswer(
            invocation -> {
              assertThat(insideTransaction.get()).isTrue();
              assertThat(activeReceiptRecorded.get()).isTrue();
              assertThat(inventoryReads.get()).isEqualTo(2);
              assertThat(proofReads.get()).isEqualTo(2);
              return committed;
            });

    fixture.coordinator().reconcilePreparedPromotionOnce(observation, privateReceipt);

    assertThat(activeCasReturned.get()).isTrue();
    assertThat(activeReceiptRecorded.get()).isTrue();
    assertThat(inventoryReads.get()).isEqualTo(2);
    assertThat(proofReads.get()).isEqualTo(2);
    verify(fixture.client()).publishActiveProjection(eq(beforeSnapshot), eq("42"), any());
    verify(fixture.repository()).recordActiveJwksPromotionResult(any(), any(), any());
    verify(fixture.repository())
        .commitPreparedPromotion(
            eq(fixture.binding().accountBinding()),
            eq(trustFence(fixture.binding())),
            eq(promotionId),
            any(ReadinessPromotionProof.class));
  }

  private static PromotionOperationEvidence promotionEvidence(
      Fixture fixture,
      UUID promotionId,
      PrivatePromotionReceipt privateReceipt,
      Optional<ActiveJwksPromotionReceipt> activeReceipt) {
    return new PromotionOperationEvidence(
        promotionId,
        "f".repeat(64),
        2,
        fixture.binding().accountBinding(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "1",
        "test-kid",
        "RS256",
        "9".repeat(64),
        trustFence(fixture.binding()),
        fixture.request().operationId(),
        fixture.request().operationDigest(),
        "a".repeat(64),
        "44444444-4444-4444-8444-444444444444",
        "13",
        API_DIGEST,
        API_REVISION,
        CONFIG_MAP_UID,
        "42",
        "1".repeat(64),
        "2".repeat(64),
        "3".repeat(64),
        "4".repeat(64),
        "5".repeat(64),
        PUBLIC_JWKS,
        "{\"phase\":\"ACTIVE\"}",
        "PREPARED",
        true,
        Optional.of(privateReceipt.observedResourceVersion()),
        Optional.of(privateReceipt.receiptDigest()),
        activeReceipt.map(ActiveJwksPromotionReceipt::observedResourceVersion),
        activeReceipt.map(ActiveJwksPromotionReceipt::publicDataDigest),
        activeReceipt.map(ActiveJwksPromotionReceipt::receiptDigest));
  }

  private static Fixture fixture(GenerationPhase phase) {
    return fixture(phase, new AtomicBoolean());
  }

  private static Fixture fixture(GenerationPhase phase, AtomicBoolean insideTransaction) {
    AccountJwtSignerMaterializerTrustBinding materializerTrust =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    AccountJwtJwksConfigMapClient client = mock(AccountJwtJwksConfigMapClient.class);
    AccountJwtSignerDesiredStateRepository repository =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPrepublicationService prepublication =
        mock(AccountJwtJwksPrepublicationService.class);
    AccountJwtReadinessProbeRepository readinessRepository =
        mock(AccountJwtReadinessProbeRepository.class);
    AccountJwtValidatorInventorySource inventorySource =
        mock(AccountJwtValidatorInventorySource.class);
    InventorySnapshot inventorySnapshot = mock(InventorySnapshot.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<AccountJwtValidatorInventorySource> inventorySourceProvider =
        mock(ObjectProvider.class);
    when(inventorySourceProvider.getIfAvailable()).thenReturn(inventorySource);
    Binding binding = materializerBinding();
    BindingIdentity apiIdentity = apiIdentity();
    ConfigMapSnapshot snapshot =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            "41",
            Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, PUBLIC_JWKS));
    GenerationRequest request = request(binding, phase);
    DesiredState enrollmentState =
        new DesiredState(
            binding.accountBinding(),
            2,
            Optional.empty(),
            Optional.empty(),
            Optional.of(request.operationId()),
            Optional.empty(),
            Optional.of(enrollment(binding)));
    when(materializerTrust.current()).thenReturn(Optional.of(binding));
    when(client.identity()).thenReturn(apiIdentity);
    when(client.observe()).thenReturn(snapshot);
    when(repository.readEnrollmentState(binding.accountBinding()))
        .thenReturn(Optional.of(enrollmentState));
    AtomicReference<EnrollmentIdentity> persistedEnrollment = new AtomicReference<>();
    when(repository.initialize(eq(binding.accountBinding()), any(EnrollmentIdentity.class)))
        .thenAnswer(
            invocation -> {
              EnrollmentIdentity requested = invocation.getArgument(1);
              EnrollmentIdentity persisted =
                  persistedEnrollment.updateAndGet(
                      current -> current == null ? requested : current);
              return new DesiredState(
                  binding.accountBinding(),
                  1,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.of(persisted));
            });
    TransactionTemplate transaction =
        new TransactionTemplate(new InertTransactionManager(insideTransaction));
    AccountJwtSignerBootstrapCoordinator coordinator =
        new AccountJwtSignerBootstrapCoordinator(
            materializerTrust,
            client,
            repository,
            prepublication,
            readinessRepository,
            inventorySourceProvider,
            transaction);
    return new Fixture(
        coordinator,
        materializerTrust,
        client,
        repository,
        prepublication,
        readinessRepository,
        inventorySource,
        inventorySnapshot,
        inventorySourceProvider,
        binding,
        request,
        snapshot);
  }

  private static Binding materializerBinding() {
    return materializerBinding("trust-revision-1");
  }

  private static Binding materializerBinding(String revision) {
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            CLUSTER_UID,
            NAMESPACE_UID,
            MATERIALIZER_URI,
            pins);
    return new Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        MATERIALIZER_URI,
        pins,
        revision,
        digest);
  }

  private static BindingIdentity apiIdentity() {
    return new BindingIdentity(
        API_DIGEST,
        API_REVISION,
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        "https://kubernetes.default.svc:6443/",
        "kubernetes.default.svc",
        "b".repeat(64),
        "system:serviceaccount:firemud-prod:account-service");
  }

  private static EnrollmentIdentity enrollment(Binding binding) {
    return new EnrollmentIdentity(
        CLUSTER_UID,
        NAMESPACE_UID,
        binding.bindingDigest(),
        binding.configRevision(),
        API_DIGEST,
        API_REVISION,
        CONFIG_MAP_UID,
        "41",
        AccountJwtJwksPublicationRepository.snapshotDigest(
            Map.of(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, PUBLIC_JWKS)));
  }

  private static void stubCurrentGenerationRequest(
      Fixture fixture, AtomicBoolean insideTransaction) {
    when(fixture
            .repository()
            .readCurrentGenerationRequest(
                eq(fixture.binding().accountBinding()), eq(trustFence(fixture.binding()))))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(insideTransaction.get()).isTrue();
              return fixture.request();
            });
  }

  private static void stubPublicationAndMountRefresh(
      Fixture fixture, AtomicBoolean insideTransaction) {
    var operationId = fixture.request().operationId();
    var publication = mock(AccountJwtJwksPublicationRepository.PublicationReceipt.class);
    var mounted = mock(AccountJwtJwksPublicationRepository.MountObservation.class);
    when(publication.operationId()).thenReturn(operationId);
    when(mounted.operationId()).thenReturn(operationId);
    when(fixture.prepublication().prepublishCurrentGeneration())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return publication;
            });
    when(fixture.prepublication().observeCurrentMountedCorrespondence())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(insideTransaction.get()).isFalse();
              return mounted;
            });
  }

  private static GenerationRequest request(Binding binding, GenerationPhase phase) {
    if (phase == GenerationPhase.GENERATION_RECORDED) {
      GenerationRequest recorded = mock(GenerationRequest.class);
      when(recorded.phase()).thenReturn(phase);
      when(recorded.operationId())
          .thenReturn(UUID.fromString("44444444-4444-4444-8444-444444444444"));
      when(recorded.operationDigest()).thenReturn("c".repeat(64));
      when(recorded.targetGeneration()).thenReturn("1");
      when(recorded.targetKid()).thenReturn("test-kid");
      return recorded;
    }
    return new GenerationRequest(
        phase,
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "c".repeat(64),
        phase == GenerationPhase.GENERATE_PENDING ? "d".repeat(64) : "",
        2,
        binding.accountBinding(),
        trustFence(binding),
        "jwt-signing-keys",
        "1",
        "test-kid",
        "RS256",
        "MATERIALIZE_PENDING",
        List.of("pending"),
        Optional.empty(),
        Optional.empty(),
        phase == GenerationPhase.GENERATE_PENDING ? "55555555-5555-4555-8555-555555555555" : "",
        phase == GenerationPhase.GENERATE_PENDING ? "12" : "",
        "",
        "",
        "",
        "");
  }

  private static ObservationContext initialObservationContext(
      UUID operationId, String operationDigest) {
    return new ObservationContext(
        ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES, operationId, operationDigest);
  }

  private static ObservationContext initialObservationContext(GenerationRequest request) {
    return initialObservationContext(request.operationId(), request.operationDigest());
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private record Fixture(
      AccountJwtSignerBootstrapCoordinator coordinator,
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtJwksConfigMapClient client,
      AccountJwtSignerDesiredStateRepository repository,
      AccountJwtJwksPrepublicationService prepublication,
      AccountJwtReadinessProbeRepository readinessRepository,
      AccountJwtValidatorInventorySource inventorySource,
      InventorySnapshot inventorySnapshot,
      ObjectProvider<AccountJwtValidatorInventorySource> inventorySourceProvider,
      Binding binding,
      GenerationRequest request,
      ConfigMapSnapshot snapshot) {}

  private static final class InertTransactionManager extends AbstractPlatformTransactionManager {
    private final AtomicBoolean active;

    private InertTransactionManager(AtomicBoolean active) {
      this.active = active;
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      active.set(true);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      active.set(false);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      active.set(false);
    }
  }
}
