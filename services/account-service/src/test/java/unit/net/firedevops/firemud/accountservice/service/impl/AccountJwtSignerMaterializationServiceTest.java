package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtSignerMaterializationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountJwtSignerMaterializationServiceTest {
  private static final Binding TRUST = materializerTrustBinding();

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
}
