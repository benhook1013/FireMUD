package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.account.v1.RecordSecretObservationRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessPromotionProof;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtSignerMaterializationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtSignerMaterializationServiceTest {
  private static final Binding TRUST = materializerTrustBinding();
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String OPERATION_DIGEST = "a".repeat(64);
  private static final String GENERATION_REQUEST_DIGEST = "b".repeat(64);
  private static final String RECEIPT_DIGEST = "c".repeat(64);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "33333333-3333-4333-8333-333333333333";
  private static final String SECRET_UID = "44444444-4444-4444-8444-444444444444";
  private static final String PEER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final byte[] PEER_SPKI =
      "account materializer test public key".getBytes(StandardCharsets.US_ASCII);

  private final AccountJwtSignerDesiredStateRepository repository = mock();
  private final AccountJwtReadinessProbeRepository readinessRepository = mock();
  private final AccountJwtSignerMaterializerTrustBinding trustBindingProvider = mock();
  private final AccountJwtValidatorInventorySource inventorySource = mock();
  private final PlatformTransactionManager transactionManager = mock();

  @Test
  void rejectsRetainedPreparedRecoveryBeforeDispatchWhenCoordinatorIsAbsent() {
    PreparedGenerationEvidence retainedPrepared = mock();
    when(repository.readPreparedGenerationForRecovery(any(), any())).thenReturn(retainedPrepared);
    when(trustBindingProvider.current()).thenReturn(Optional.of(TRUST));
    StaticListableBeanFactory beans = beanFactoryWithInventory();
    ObjectProvider<AccountJwtSignerBootstrapCoordinator> coordinatorProvider =
        beans.getBeanProvider(AccountJwtSignerBootstrapCoordinator.class);
    ObjectProvider<AccountJwtValidatorInventorySource> inventoryProvider =
        beans.getBeanProvider(AccountJwtValidatorInventorySource.class);
    AccountJwtSignerMaterializationService service =
        new AccountJwtSignerMaterializationService(
            repository,
            readinessRepository,
            trustBindingProvider,
            transactionManager,
            coordinatorProvider,
            inventoryProvider);
    Observer<GetCurrentPromotionRequestResponse> observer = new Observer<>();

    authenticate(TRUST)
        .run(
            () ->
                service.getCurrentPromotionRequest(
                    GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build(),
                    observer));

    assertFailedPrecondition(observer.failure);
    verify(repository, never()).readPreparedGenerationForRecovery(any(), any());
    verify(repository, never()).readAndMarkPreparedPromotionDispatched(any(), any());
    verifyNoInteractions(readinessRepository, inventorySource, transactionManager);
    verify(trustBindingProvider).current();
  }

  @Test
  void rejectsPrivatePromotionResultBeforePersistingReceiptWhenCoordinatorIsAbsent() {
    when(trustBindingProvider.current()).thenReturn(Optional.of(TRUST));
    StaticListableBeanFactory beans = beanFactoryWithInventory();
    ObjectProvider<AccountJwtSignerBootstrapCoordinator> coordinatorProvider =
        beans.getBeanProvider(AccountJwtSignerBootstrapCoordinator.class);
    ObjectProvider<AccountJwtValidatorInventorySource> inventoryProvider =
        beans.getBeanProvider(AccountJwtValidatorInventorySource.class);
    AccountJwtSignerMaterializationService service =
        new AccountJwtSignerMaterializationService(
            repository,
            readinessRepository,
            trustBindingProvider,
            transactionManager,
            coordinatorProvider,
            inventoryProvider);
    Observer<RecordPrivatePromotionResultResponse> observer = new Observer<>();
    RecordPrivatePromotionResultRequest request =
        RecordPrivatePromotionResultRequest.newBuilder()
            .setSchemaVersion(1)
            .setPromotionOperationId(UUID.randomUUID().toString())
            .setGenerationOperationId(UUID.randomUUID().toString())
            .setCurrent(
                net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity.getDefaultInstance())
            .build();

    authenticate(TRUST).run(() -> service.recordPrivatePromotionResult(request, observer));

    assertFailedPrecondition(observer.failure);
    verifyNoInteractions(repository, readinessRepository, inventorySource, transactionManager);
    verify(trustBindingProvider).current();
  }

  @Test
  void recordsPrivatePromotionResultAndReconcilesWhenCoordinatorIsConfigured() {
    when(trustBindingProvider.current()).thenReturn(Optional.of(TRUST));
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    UUID promotionOperationId = UUID.randomUUID();
    UUID generationOperationId = UUID.randomUUID();
    String digest = "b".repeat(64);
    PrivatePromotionReceipt receipt =
        new PrivatePromotionReceipt(promotionOperationId, generationOperationId, "2", digest);
    when(repository.recordPrivatePromotionResult(any(), any(), any())).thenReturn(receipt);
    AccountJwtSignerBootstrapCoordinator coordinator = mock();
    StaticListableBeanFactory beans = beanFactoryWithInventory();
    beans.addBean("jwtSignerBootstrapCoordinator", coordinator);
    ObjectProvider<AccountJwtSignerBootstrapCoordinator> coordinatorProvider =
        beans.getBeanProvider(AccountJwtSignerBootstrapCoordinator.class);
    ObjectProvider<AccountJwtValidatorInventorySource> inventoryProvider =
        beans.getBeanProvider(AccountJwtValidatorInventorySource.class);
    AccountJwtSignerMaterializationService service =
        new AccountJwtSignerMaterializationService(
            repository,
            readinessRepository,
            trustBindingProvider,
            transactionManager,
            coordinatorProvider,
            inventoryProvider);
    Observer<RecordPrivatePromotionResultResponse> observer = new Observer<>();
    RecordPrivatePromotionResultRequest request =
        RecordPrivatePromotionResultRequest.newBuilder()
            .setSchemaVersion(1)
            .setPromotionOperationId(promotionOperationId.toString())
            .setPromotionRequestDigest(digest)
            .setGenerationOperationId(generationOperationId.toString())
            .setGenerationOperationDigest(digest)
            .setSecretUid("33333333-3333-4333-8333-333333333333")
            .setExpectedPriorResourceVersion("1")
            .setObservedResourceVersion("2")
            .setCurrent(
                net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity.newBuilder()
                    .setGeneration("1")
                    .setKid("key-1")
                    .setPublicKeyFingerprint(digest))
            .addResultingSlots("current")
            .build();

    authenticate(TRUST).run(() -> service.recordPrivatePromotionResult(request, observer));

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getPromotionOperationId()).isEqualTo(promotionOperationId.toString());
    assertThat(observer.value.getPublicReceiptDigest()).isEqualTo(receipt.receiptDigest());
    verify(coordinator)
        .reconcilePreparedPromotionOnce(
            org.mockito.ArgumentMatchers.argThat(
                (PrivatePromotionObservation observation) ->
                    observation.promotionOperationId().equals(promotionOperationId)),
            org.mockito.ArgumentMatchers.eq(receipt));
  }

  @Test
  void authenticatedGetReturnsAccountSelectedReadOnlyObservationOperation() throws Exception {
    Fixture fixture = fixture();
    GenerationRequest request =
        generationRequest(fixture.binding, GenerationPhase.OBSERVE_PRIVATE_SECRET, "", "", "");
    when(fixture.repository.ensureCurrentGenerationRequest(
            eq(accountBinding(fixture.binding)), eq(trustFence(fixture.binding))))
        .thenReturn(request);
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.getCurrentGenerationRequest(
                GetCurrentGenerationRequestRequest.newBuilder().setSchemaVersion(1).build(),
                response));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getPhase())
        .isEqualTo(GetCurrentGenerationRequestResponse.Phase.OBSERVE_PRIVATE_SECRET);
    assertThat(response.value.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.value.getTargetGeneration()).isEqualTo("1");
    assertThat(response.value.getTargetKid()).startsWith("jwt-1-");
    assertThat(response.value.getPrivateSecretName()).isEqualTo("jwt-signing-keys");
    assertThat(response.value.getExpectedSecretResourceVersion()).isEmpty();
    assertThat(response.value.getPublicJwkJson()).isEmpty();
    verify(fixture.repository)
        .ensureCurrentGenerationRequest(
            accountBinding(fixture.binding), trustFence(fixture.binding));
    verify(fixture.transactionManager).commit(any(TransactionStatus.class));
  }

  @Test
  void authenticatedObservationReturnsOnlyTheExactGenerationCasFence() throws Exception {
    Fixture fixture = fixture();
    GenerationRequest pending =
        generationRequest(fixture.binding, GenerationPhase.GENERATE_PENDING, SECRET_UID, "12", "");
    when(fixture.repository.recordSecretObservation(
            eq(accountBinding(fixture.binding)),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(OPERATION_DIGEST),
            eq(SECRET_UID),
            eq("12")))
        .thenReturn(pending);
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.recordSecretObservation(
                RecordSecretObservationRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setOperationId(OPERATION_ID.toString())
                    .setOperationDigest(OPERATION_DIGEST)
                    .setSecretUid(SECRET_UID)
                    .setObservedResourceVersion("12")
                    .build(),
                response));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getPhase())
        .isEqualTo(GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING);
    assertThat(response.value.getGenerationRequestDigest()).isEqualTo(GENERATION_REQUEST_DIGEST);
    assertThat(response.value.getSecretUid()).isEqualTo(SECRET_UID);
    assertThat(response.value.getExpectedSecretResourceVersion()).isEqualTo("12");
    assertThat(response.value.getPublicKeyFingerprint()).isEmpty();
    verify(fixture.repository)
        .recordSecretObservation(
            accountBinding(fixture.binding),
            trustFence(fixture.binding),
            OPERATION_ID,
            OPERATION_DIGEST,
            SECRET_UID,
            "12");
  }

  @Test
  void authenticatedGenerationResultReturnsAccountDerivedPublicOnlyReceipt() throws Exception {
    Fixture fixture = fixture();
    PublicJwk jwk = publicJwk(targetKid());
    GenerationResult result = generationResult(fixture.binding, jwk);
    when(fixture.repository.recordGenerationResult(
            eq(accountBinding(fixture.binding)),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(GENERATION_REQUEST_DIGEST),
            eq(SECRET_UID),
            eq("12"),
            eq("13"),
            eq(jwk.json())))
        .thenReturn(result);
    RecordingObserver<RecordGenerationResultResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.recordGenerationResult(
                RecordGenerationResultRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setOperationId(OPERATION_ID.toString())
                    .setGenerationRequestDigest(GENERATION_REQUEST_DIGEST)
                    .setSecretUid(SECRET_UID)
                    .setExpectedPriorResourceVersion("12")
                    .setObservedResourceVersion("13")
                    .setPublicJwkJson(jwk.json())
                    .build(),
                response));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getGenerationReceiptDigest()).isEqualTo(RECEIPT_DIGEST);
    assertThat(response.value.getExpectedPriorResourceVersion()).isEqualTo("12");
    assertThat(response.value.getObservedResourceVersion()).isEqualTo("13");
    assertThat(response.value.getTargetKid()).isEqualTo(targetKid());
    assertThat(response.value.getPublicKeyFingerprint()).isEqualTo(jwk.fingerprint());
    assertThat(response.value.getPublicJwkJson()).isEqualTo(jwk.json());
    assertThat(response.value.getPublicJwkJson()).doesNotContain("\"d\"");
    verify(fixture.transactionManager).commit(any(TransactionStatus.class));
  }

  @Test
  void promotionRequestCommitsStickyDispatchBeforeReturningMutationAuthority() throws Exception {
    Fixture fixture = fixture();
    PublicJwk jwk = publicJwk(targetKid());
    PreparedGenerationEvidence prepared = preparedGenerationEvidence(fixture.binding, jwk);
    when(fixture.repository.readAndMarkPreparedPromotionDispatched(
            eq(accountBinding(fixture.binding)), eq(trustFence(fixture.binding))))
        .thenReturn(prepared);
    when(fixture.repository.readPreparedGenerationForRecovery(
            eq(accountBinding(fixture.binding)), eq(trustFence(fixture.binding))))
        .thenReturn(prepared);
    when(fixture.inventorySource.observe(any(ObservationContext.class)))
        .thenReturn(mock(InventorySnapshot.class));
    when(fixture.readinessRepository.readPromotionProof(
            eq(accountBinding(fixture.binding)),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            any(InventorySnapshot.class)))
        .thenReturn(Optional.of(mock(ReadinessPromotionProof.class)));
    AtomicInteger committedTransactions = new AtomicInteger();
    doAnswer(
            invocation -> {
              committedTransactions.incrementAndGet();
              return null;
            })
        .when(fixture.transactionManager)
        .commit(any(TransactionStatus.class));
    GetCurrentPromotionRequestResponse[] delivered = new GetCurrentPromotionRequestResponse[1];
    StreamObserver<GetCurrentPromotionRequestResponse> response =
        new StreamObserver<>() {
          @Override
          public void onNext(GetCurrentPromotionRequestResponse value) {
            assertThat(committedTransactions.get()).isGreaterThanOrEqualTo(2);
            delivered[0] = value;
          }

          @Override
          public void onError(Throwable error) {
            throw new AssertionError("Promotion request unexpectedly failed", error);
          }

          @Override
          public void onCompleted() {
            assertThat(committedTransactions.get()).isGreaterThanOrEqualTo(2);
          }
        };

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.getCurrentPromotionRequest(
                GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build(),
                response));

    assertThat(delivered[0]).isNotNull();
    assertThat(delivered[0].getPromotionOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(delivered[0].getOperationAction()).isEqualTo("PROMOTE_PENDING");
    assertThat(delivered[0].getAllowedPrivateSlotsList())
        .containsExactly("current.key", "pending.key", "previous.key");
    verify(fixture.repository, times(2))
        .readPreparedGenerationForRecovery(
            accountBinding(fixture.binding), trustFence(fixture.binding));
    verify(fixture.readinessRepository)
        .readPromotionProof(
            eq(accountBinding(fixture.binding)),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            any(InventorySnapshot.class));
    verify(fixture.repository)
        .readAndMarkPreparedPromotionDispatched(
            accountBinding(fixture.binding), trustFence(fixture.binding));
    verify(fixture.inventorySource)
        .observe(
            new ObservationContext(
                ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES,
                OPERATION_ID,
                OPERATION_DIGEST));
    verify(fixture.transactionManager, times(2)).commit(any(TransactionStatus.class));
  }

  @Test
  void authenticatedPromotionPollUsesNotFoundOnlyForNoPreparedWork() throws Exception {
    Fixture fixture = fixture();
    when(fixture.repository.readPreparedGenerationForRecovery(
            eq(accountBinding(fixture.binding)), eq(trustFence(fixture.binding))))
        .thenThrow(new AccountJwtSignerDesiredStateRepository.NoPreparedPromotionException());
    when(fixture.coordinator.prepareCurrentPromotionOnce()).thenReturn(Optional.empty());
    when(fixture.repository.read(eq(accountBinding(fixture.binding))))
        .thenReturn(initialUnselectedDesiredState(fixture.binding));
    RecordingObserver<GetCurrentPromotionRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.getCurrentPromotionRequest(
                GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build(),
                response));

    assertStatus(response.error, Status.Code.NOT_FOUND);
    assertThat(response.completed).isFalse();
    verify(fixture.coordinator).prepareCurrentPromotionOnce();
    verify(fixture.repository).read(eq(accountBinding(fixture.binding)));
    verify(fixture.repository, never()).readAndMarkPreparedPromotionDispatched(any(), any());
    verify(fixture.inventorySource, never()).observe(any(ObservationContext.class));
    verify(fixture.transactionManager).rollback(any(TransactionStatus.class));
  }

  @Test
  void promotionPollDoesNotRecordDispatchWhenOwnerReadinessProofIsMissing() throws Exception {
    Fixture fixture = fixture();
    PublicJwk jwk = publicJwk(targetKid());
    PreparedGenerationEvidence prepared = preparedGenerationEvidence(fixture.binding, jwk);
    when(fixture.repository.readPreparedGenerationForRecovery(
            eq(accountBinding(fixture.binding)), eq(trustFence(fixture.binding))))
        .thenReturn(prepared);
    when(fixture.inventorySource.observe(any(ObservationContext.class)))
        .thenReturn(mock(InventorySnapshot.class));
    when(fixture.readinessRepository.readPromotionProof(
            eq(accountBinding(fixture.binding)),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            any(InventorySnapshot.class)))
        .thenReturn(Optional.empty());
    RecordingObserver<GetCurrentPromotionRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.getCurrentPromotionRequest(
                GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build(),
                response));

    assertStatus(response.error, Status.Code.FAILED_PRECONDITION);
    verify(fixture.repository, never()).readAndMarkPreparedPromotionDispatched(any(), any());
    verify(fixture.inventorySource)
        .observe(
            new ObservationContext(
                ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES,
                OPERATION_ID,
                OPERATION_DIGEST));
    verify(fixture.transactionManager).rollback(any(TransactionStatus.class));
    verify(fixture.transactionManager, times(1)).commit(any(TransactionStatus.class));
  }

  @Test
  void serviceCallsWithoutInterceptorContextAreDeniedEvenWithBearerHeader() {
    Fixture fixture = fixture();
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
        "Bearer caller-controlled-header");

    fixture.service.getCurrentGenerationRequest(
        GetCurrentGenerationRequestRequest.newBuilder().setSchemaVersion(1).build(), response);

    assertStatus(response.error, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(fixture.repository, fixture.transactionManager);
    verify(fixture.trustBindingProvider).current();
  }

  @Test
  void malformedSchemaFailsBeforeTrustLookupOrTransaction() {
    Fixture fixture = fixture();
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();

    fixture.service.getCurrentGenerationRequest(
        GetCurrentGenerationRequestRequest.getDefaultInstance(), response);

    assertStatus(response.error, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(
        fixture.repository, fixture.transactionManager, fixture.trustBindingProvider);
  }

  @Test
  void changedProtectedTrustBeforeCommitRollsBackSecretObservation() throws Exception {
    Fixture fixture = fixture();
    Binding changedBinding = trustBinding("revision-2", sha256("replacement materializer key"));
    when(fixture.trustBindingProvider.current())
        .thenReturn(
            Optional.of(fixture.binding),
            Optional.of(fixture.binding),
            Optional.of(changedBinding));
    when(fixture.repository.recordSecretObservation(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            generationRequest(
                fixture.binding, GenerationPhase.GENERATE_PENDING, SECRET_UID, "12", ""));
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.recordSecretObservation(
                RecordSecretObservationRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setOperationId(OPERATION_ID.toString())
                    .setOperationDigest(OPERATION_DIGEST)
                    .setSecretUid(SECRET_UID)
                    .setObservedResourceVersion("12")
                    .build(),
                response));

    assertStatus(response.error, Status.Code.PERMISSION_DENIED);
    assertThat(response.completed).isFalse();
    verify(fixture.repository).recordSecretObservation(any(), any(), any(), any(), any(), any());
    verify(fixture.transactionManager).rollback(any(TransactionStatus.class));
    verify(fixture.transactionManager, never()).commit(any(TransactionStatus.class));
  }

  @Test
  void staleOperationAndConflictingReadbackFailClosed() throws Exception {
    Fixture staleFixture = fixture();
    when(staleFixture.repository.recordSecretObservation(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException(
                "stale operation"));
    RecordingObserver<GetCurrentGenerationRequestResponse> staleResponse =
        new RecordingObserver<>();
    invokeWithAuthenticatedPeer(
        staleFixture,
        () -> staleFixture.service.recordSecretObservation(observationRequest(), staleResponse));
    assertStatus(staleResponse.error, Status.Code.FAILED_PRECONDITION);
    verify(staleFixture.transactionManager).rollback(any(TransactionStatus.class));

    Fixture conflictFixture = fixture();
    when(conflictFixture.repository.recordSecretObservation(
            any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                "changed observation"));
    RecordingObserver<GetCurrentGenerationRequestResponse> conflictResponse =
        new RecordingObserver<>();
    invokeWithAuthenticatedPeer(
        conflictFixture,
        () ->
            conflictFixture.service.recordSecretObservation(
                observationRequest(), conflictResponse));
    assertStatus(conflictResponse.error, Status.Code.ALREADY_EXISTS);
    verify(conflictFixture.transactionManager).rollback(any(TransactionStatus.class));
  }

  @Test
  void exactOperationIdMustBeCanonicalUuidV4() throws Exception {
    Fixture fixture = fixture();
    RecordingObserver<GetCurrentGenerationRequestResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(
        fixture,
        () ->
            fixture.service.recordSecretObservation(
                RecordSecretObservationRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setOperationId("not-a-uuid")
                    .build(),
                response));

    assertStatus(response.error, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(fixture.repository, fixture.transactionManager);
  }

  private StaticListableBeanFactory beanFactoryWithInventory() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("validatorInventorySource", inventorySource);
    return beans;
  }

  private static Context authenticate(Binding binding) {
    @SuppressWarnings("unchecked")
    Context.Key<Binding> authenticatedBindingKey =
        (Context.Key<Binding>)
            ReflectionTestUtils.getField(
                AccountJwtSignerMaterializerTlsInterceptor.class, "AUTHENTICATED_BINDING");
    return Context.current().withValue(authenticatedBindingKey, binding);
  }

  private static Binding materializerTrustBinding() {
    String environmentId = "prod";
    String clusterId = "prod-cluster-1";
    String namespace = "firemud-prod";
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String peerUri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String revision = "materializer-r1";
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, environmentId, clusterId, namespace, clusterUid, namespaceUid, peerUri, pins);
    return new Binding(
        environmentId,
        clusterId,
        namespace,
        clusterUid,
        namespaceUid,
        peerUri,
        pins,
        revision,
        digest);
  }

  private static void assertFailedPrecondition(Status failure) {
    assertThat(failure).isNotNull();
    assertThat(failure.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  private static final class Observer<T> implements StreamObserver<T> {
    private T value;
    private Status failure;
    private boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static DesiredState initialUnselectedDesiredState(Binding materializerBinding) {
    EnrollmentIdentity enrollment =
        new EnrollmentIdentity(
            materializerBinding.expectedClusterIncarnationUid(),
            materializerBinding.expectedNamespaceUid(),
            materializerBinding.bindingDigest(),
            materializerBinding.configRevision(),
            "e".repeat(64),
            "api-revision-1",
            "55555555-5555-4555-8555-555555555555",
            "44",
            "f".repeat(64));
    return new DesiredState(
        accountBinding(materializerBinding),
        1,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(enrollment));
  }

  private static Fixture fixture() {
    return fixture(true);
  }

  private static Fixture fixture(boolean coordinatorAvailable) {
    try {
      Binding binding = trustBinding("revision-1", sha256(PEER_SPKI));
      AccountJwtSignerMaterializerTrustBinding trustBindingProvider =
          mock(AccountJwtSignerMaterializerTrustBinding.class);
      when(trustBindingProvider.current()).thenReturn(Optional.of(binding));
      AccountJwtSignerDesiredStateRepository repository =
          mock(AccountJwtSignerDesiredStateRepository.class);
      AccountJwtReadinessProbeRepository readinessRepository =
          mock(AccountJwtReadinessProbeRepository.class);
      AccountJwtValidatorInventorySource inventorySource =
          mock(AccountJwtValidatorInventorySource.class);
      AccountJwtSignerBootstrapCoordinator coordinator =
          coordinatorAvailable ? mock(AccountJwtSignerBootstrapCoordinator.class) : null;
      PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
      when(transactionManager.getTransaction(any()))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      StaticListableBeanFactory beans = new StaticListableBeanFactory();
      beans.addBean("validatorInventorySource", inventorySource);
      if (coordinator != null) {
        beans.addBean("jwtSignerBootstrapCoordinator", coordinator);
      }
      ObjectProvider<AccountJwtSignerBootstrapCoordinator> coordinatorProvider =
          beans.getBeanProvider(AccountJwtSignerBootstrapCoordinator.class);
      ObjectProvider<AccountJwtValidatorInventorySource> inventoryProvider =
          beans.getBeanProvider(AccountJwtValidatorInventorySource.class);
      AccountJwtSignerMaterializationService service =
          new AccountJwtSignerMaterializationService(
              repository,
              readinessRepository,
              trustBindingProvider,
              transactionManager,
              coordinatorProvider,
              inventoryProvider);
      AccountJwtSignerMaterializerTlsInterceptor interceptor =
          new AccountJwtSignerMaterializerTlsInterceptor(trustBindingProvider);
      return new Fixture(
          binding,
          trustBindingProvider,
          repository,
          readinessRepository,
          inventorySource,
          coordinator,
          transactionManager,
          service,
          interceptor);
    } catch (Exception ex) {
      throw new IllegalStateException("JWT materializer service test fixture failed", ex);
    }
  }

  private static Binding trustBinding(String revision, String pin) {
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            CLUSTER_UID,
            NAMESPACE_UID,
            PEER_URI,
            List.of(pin));
    return new Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        PEER_URI,
        List.of(pin),
        revision,
        digest);
  }

  private static AccountJwtSignerDesiredStateRepository.Binding accountBinding(Binding binding) {
    return new AccountJwtSignerDesiredStateRepository.Binding(
        binding.environmentId(),
        binding.clusterId(),
        binding.namespace(),
        CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static GenerationRequest generationRequest(
      Binding binding,
      GenerationPhase phase,
      String secretUid,
      String resourceVersion,
      String jwk) {
    String generationRequestDigest =
        phase == GenerationPhase.OBSERVE_PRIVATE_SECRET ? "" : GENERATION_REQUEST_DIGEST;
    boolean recorded = phase == GenerationPhase.GENERATION_RECORDED;
    return new GenerationRequest(
        phase,
        OPERATION_ID,
        OPERATION_DIGEST,
        generationRequestDigest,
        2,
        accountBinding(binding),
        trustFence(binding),
        "jwt-signing-keys",
        "1",
        targetKid(),
        "RS256",
        "MATERIALIZE_PENDING",
        List.of("pending"),
        Optional.empty(),
        Optional.empty(),
        secretUid,
        resourceVersion,
        recorded ? RECEIPT_DIGEST : "",
        recorded ? PUBLIC_JWK_FINGERPRINT : "",
        recorded ? jwk : "",
        recorded ? "13" : "");
  }

  private static final String PUBLIC_JWK_FINGERPRINT = "d".repeat(64);

  private static GenerationResult generationResult(Binding binding, PublicJwk jwk) {
    return new GenerationResult(
        OPERATION_ID,
        accountBinding(binding),
        OPERATION_DIGEST,
        GENERATION_REQUEST_DIGEST,
        2,
        trustFence(binding),
        "jwt-signing-keys",
        SECRET_UID,
        "12",
        "13",
        "1",
        targetKid(),
        "RS256",
        jwk.fingerprint(),
        jwk.json(),
        RECEIPT_DIGEST);
  }

  private static PreparedGenerationEvidence preparedGenerationEvidence(
      Binding binding, PublicJwk jwk) {
    AccountJwtSignerDesiredStateRepository.Binding accountBinding = accountBinding(binding);
    TrustFence trustFence = trustFence(binding);
    EnrollmentIdentity enrollment =
        new EnrollmentIdentity(
            CLUSTER_UID,
            NAMESPACE_UID,
            OPERATION_DIGEST,
            "revision-1",
            "e".repeat(64),
            "api-revision-1",
            "55555555-5555-4555-8555-555555555555",
            "44",
            "f".repeat(64));
    DesiredState desiredState =
        new DesiredState(
            accountBinding,
            3,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(OPERATION_ID),
            Optional.of(enrollment));
    PromotionOperationEvidence promotion =
        new PromotionOperationEvidence(
            OPERATION_ID,
            "a".repeat(64),
            2,
            accountBinding,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "1",
            targetKid(),
            "RS256",
            jwk.fingerprint(),
            trustFence,
            OPERATION_ID,
            OPERATION_DIGEST,
            RECEIPT_DIGEST,
            SECRET_UID,
            "13",
            "e".repeat(64),
            "api-revision-1",
            "55555555-5555-4555-8555-555555555555",
            "44",
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "5".repeat(64),
            jwk.json(),
            "{}",
            "PREPARED",
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    return new PreparedGenerationEvidence(desiredState, promotion, generationResult(binding, jwk));
  }

  private static RecordSecretObservationRequest observationRequest() {
    return RecordSecretObservationRequest.newBuilder()
        .setSchemaVersion(1)
        .setOperationId(OPERATION_ID.toString())
        .setOperationDigest(OPERATION_DIGEST)
        .setSecretUid(SECRET_UID)
        .setObservedResourceVersion("12")
        .build();
  }

  private static PublicJwk publicJwk(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
    String modulus = encodeUnsigned(key.getModulus());
    String exponent = encodeUnsigned(key.getPublicExponent());
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("alg", "RS256");
    jwk.put("e", exponent);
    jwk.put("key_ops", List.of("verify"));
    jwk.put("kid", kid);
    jwk.put("kty", "RSA");
    jwk.put("n", modulus);
    jwk.put("use", "sig");
    String json =
        new String(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JsonMapper.builder().build().writeValueAsString(jwk)),
            StandardCharsets.UTF_8);
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        Rfc8785CanonicalJson.canonicalizeUtf8(
                            JsonMapper.builder()
                                .build()
                                .writeValueAsString(
                                    Map.of("e", exponent, "kty", "RSA", "n", modulus)))));
    return new PublicJwk(kid, json, fingerprint);
  }

  private static String targetKid() {
    return "jwt-1-" + OPERATION_ID.toString().substring(0, 12);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static void invokeWithAuthenticatedPeer(Fixture fixture, Runnable rpcInvocation)
      throws Exception {
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    SSLSession sslSession = mock(SSLSession.class);
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    PublicKey publicKey = mock(PublicKey.class);
    when(publicKey.getEncoded()).thenReturn(PEER_SPKI);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, PEER_URI)));
    when(sslSession.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate});
    when(call.getAttributes())
        .thenReturn(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
    ServerCallHandler<String, String> next =
        (interceptedCall, headers) -> {
          assertThat(AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding())
              .isEqualTo(fixture.binding);
          rpcInvocation.run();
          return new ServerCall.Listener<>() {};
        };

    fixture.interceptor.interceptCall(call, new Metadata(), next);
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String sha256(String value) throws Exception {
    return sha256(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static void assertStatus(ErrorDiagnostic failure, Status.Code expected) {
    assertThat(failure.exceptionType()).isEqualTo(StatusRuntimeException.class.getName());
    assertThat(failure.code()).isEqualTo(expected);
  }

  private record ErrorDiagnostic(String exceptionType, Status.Code code) {}

  private record PublicJwk(String kid, String json, String fingerprint) {}

  private record Fixture(
      Binding binding,
      AccountJwtSignerMaterializerTrustBinding trustBindingProvider,
      AccountJwtSignerDesiredStateRepository repository,
      AccountJwtReadinessProbeRepository readinessRepository,
      AccountJwtValidatorInventorySource inventorySource,
      AccountJwtSignerBootstrapCoordinator coordinator,
      PlatformTransactionManager transactionManager,
      AccountJwtSignerMaterializationService service,
      AccountJwtSignerMaterializerTlsInterceptor interceptor) {}

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T value;
    private ErrorDiagnostic error;
    private boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable error) {
      Status.Code code =
          error instanceof StatusRuntimeException statusFailure
              ? statusFailure.getStatus().getCode()
              : Status.fromThrowable(error).getCode();
      this.error = new ErrorDiagnostic(error.getClass().getName(), code);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
