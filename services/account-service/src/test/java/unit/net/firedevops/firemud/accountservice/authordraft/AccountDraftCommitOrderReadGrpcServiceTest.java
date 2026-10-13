package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import net.firedevops.firemud.account.v1.HeldOriginalCommitOrderStatus;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderRequest;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.FenceSnapshot;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class AccountDraftCommitOrderReadGrpcServiceTest {
  @Test
  void authenticatesExactOwnerPeerBeforeDecodingOrOwnerAccess() {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    var owner = new AccountDraftCommitOrderReadService(repository, manager, "test");
    var service = new AccountDraftCommitOrderReadGrpcService(owner, "test");
    var malformed = ReadHeldOriginalCommitOrderRequest.newBuilder().setSchemaVersion(-1).build();

    Collector missing = new Collector();
    service.readHeldOriginalCommitOrder(malformed, missing);
    assertThat(missing.errorStatus.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

    for (String identity :
        new String[] {
          "spiffe://firemud/ns/test/sa/account-service",
          "spiffe://firemud/ns/other/sa/world-management-service"
        }) {
      Collector wrong = new Collector();
      asPeer(identity, () -> service.readHeldOriginalCommitOrder(malformed, wrong));
      assertThat(wrong.errorStatus.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    Collector malformedGameLogic = new Collector();
    asPeer(
        "spiffe://firemud/ns/test/sa/game-logic-service",
        () -> service.readHeldOriginalCommitOrder(malformed, malformedGameLogic));
    assertThat(malformedGameLogic.errorStatus.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository, manager);
  }

  @Test
  void authenticatedWorldPeerGetsHeldOnlyForExactPendingCommitOrder() {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    var transactionStatus = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    var binding = AccountDraftCommitOrderReadServiceTest.binding(1);
    when(repository.readOriginalBinding(binding.operationId())).thenReturn(Optional.of(binding));
    when(repository.read(binding))
        .thenReturn(new FenceSnapshot(Ordering.COMMIT_ORDER, binding.canonicalBytes(), null, null));
    when(repository.readSettlement(binding)).thenReturn(Settlement.PENDING);
    var owner = new AccountDraftCommitOrderReadService(repository, manager, "test");
    var service = new AccountDraftCommitOrderReadGrpcService(owner, "test");
    var request = AccountDraftCommitOrderReadServiceTest.request(binding);
    Collector result = new Collector();

    asPeer(
        "spiffe://firemud/ns/test/sa/world-management-service",
        () ->
            service.readHeldOriginalCommitOrder(
                DraftCommitOrderReadGrpcCodec.toRequest(request), result));

    assertThat(result.errorStatus).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getStatus())
        .isEqualTo(HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_HELD);
    assertThat(result.value.getOriginalAccountBinding().toByteArray())
        .containsExactly(binding.canonicalBytes());
  }

  @Test
  void authenticatedGameLogicPeerGetsHeldOnlyForExactRequiredOwnerBinding() {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    var transactionStatus = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    var binding = AccountDraftCommitOrderReadServiceTest.bindingWithGameLogic(1);
    when(repository.readOriginalBinding(binding.operationId())).thenReturn(Optional.of(binding));
    when(repository.read(binding))
        .thenReturn(new FenceSnapshot(Ordering.COMMIT_ORDER, binding.canonicalBytes(), null, null));
    when(repository.readSettlement(binding)).thenReturn(Settlement.PENDING);
    var service =
        new AccountDraftCommitOrderReadGrpcService(
            new AccountDraftCommitOrderReadService(repository, manager, "test"), "test");
    var request = AccountDraftCommitOrderReadServiceTest.request(binding);
    Collector result = new Collector();

    asPeer(
        "spiffe://firemud/ns/test/sa/game-logic-service",
        () ->
            service.readHeldOriginalCommitOrder(
                DraftCommitOrderReadGrpcCodec.toRequest(request), result));

    assertThat(result.errorStatus).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getStatus())
        .isEqualTo(HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_HELD);
    assertThat(result.value.getOriginalAccountBinding().toByteArray())
        .containsExactly(binding.canonicalBytes());
  }

  @Test
  void gameLogicPeerCannotReadWorldOnlyBindingOrOpenOwnerTransaction() {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    var service =
        new AccountDraftCommitOrderReadGrpcService(
            new AccountDraftCommitOrderReadService(repository, manager, "test"), "test");
    var binding = AccountDraftCommitOrderReadServiceTest.binding(1);
    Collector result = new Collector();

    asPeer(
        "spiffe://firemud/ns/test/sa/game-logic-service",
        () ->
            service.readHeldOriginalCommitOrder(
                DraftCommitOrderReadGrpcCodec.toRequest(
                    AccountDraftCommitOrderReadServiceTest.request(binding)),
                result));

    assertThat(result.errorStatus.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(result.value).isNull();
    assertThat(result.completed).isFalse();
    verifyNoInteractions(repository, manager);
  }

  @Test
  void invalidCanonicalRequestAndUnheldOperationNeverProducePositiveResponse() {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    var service =
        new AccountDraftCommitOrderReadGrpcService(
            new AccountDraftCommitOrderReadService(repository, manager, "test"), "test");
    Collector malformed = new Collector();
    asPeer(
        "spiffe://firemud/ns/test/sa/world-management-service",
        () ->
            service.readHeldOriginalCommitOrder(
                ReadHeldOriginalCommitOrderRequest.getDefaultInstance(), malformed));
    assertThat(malformed.errorStatus.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);

    var binding = AccountDraftCommitOrderReadServiceTest.binding(1);
    when(repository.readOriginalBinding(binding.operationId())).thenReturn(Optional.empty());
    Collector absent = new Collector();
    asPeer(
        "spiffe://firemud/ns/test/sa/world-management-service",
        () ->
            service.readHeldOriginalCommitOrder(
                DraftCommitOrderReadGrpcCodec.toRequest(
                    AccountDraftCommitOrderReadServiceTest.request(binding)),
                absent));
    assertThat(absent.errorStatus.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(absent.value).isNull();
  }

  private static void asPeer(String uri, Runnable call) {
    var peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    try {
      call.run();
    } finally {
      context.detach(previous);
    }
  }

  private static final class Collector
      implements StreamObserver<ReadHeldOriginalCommitOrderResponse> {
    private ReadHeldOriginalCommitOrderResponse value;
    private Status errorStatus;
    private boolean completed;

    @Override
    public void onNext(ReadHeldOriginalCommitOrderResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorStatus = Status.fromThrowable(failure);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
