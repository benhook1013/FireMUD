package net.firedevops.firemud.entitymanagement.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic receipt/peer tests do not establish Entity producer or database proof. */
class EntitySelectedSourceIntakeTerminalReadServiceTest {
  private static final String NAMESPACE = "test";
  private static Fixture fixture;

  @BeforeAll
  static void createSyntheticReceiptFixture() {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
  }

  @BeforeEach
  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactRetriesReadOnlyTheCommittedReceiptAndUseDistinctReadCorrelations() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var binding = fixture.authorization();
    when(repository.read(NAMESPACE, binding.intakeRequestId()))
        .thenReturn(Optional.of(fixture.receipt()));
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer(NAMESPACE);
    var previous = accountContext.attach();
    try {
      var first = service.read(request(binding));
      var retry = service.read(request(binding));

      assertThat(first.receipt()).isSameAs(fixture.receipt());
      assertThat(retry.receipt()).isSameAs(fixture.receipt());
      assertThat(first.request().readRequestId()).isNotEqualTo(retry.request().readRequestId());
      assertThat(first.receipt().canonicalBytes()).isEqualTo(fixture.receipt().canonicalBytes());
      verify(repository, times(2)).read(NAMESPACE, binding.intakeRequestId());
    } finally {
      accountContext.detach(previous);
    }
  }

  @Test
  void grpcReturnsTheExactFullReceiptWithoutRemintingIt() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var binding = fixture.authorization();
    when(repository.read(NAMESPACE, binding.intakeRequestId()))
        .thenReturn(Optional.of(fixture.receipt()));
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var grpc = new EntitySelectedSourceIntakeTerminalReadGrpcService(service);
    var request = request(binding);
    var observedResponse = new AtomicReference<ReadSelectedSourceIntakeTerminalResponse>();
    var observedError = new AtomicReference<Throwable>();
    var observer = responseObserver(observedResponse, observedError);
    var accountContext = accountPeer(NAMESPACE);
    var previous = accountContext.attach();
    try {
      grpc.readSelectedSourceIntakeTerminal(
          EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request), observer);
    } finally {
      accountContext.detach(previous);
    }

    assertThat(observedError.get()).isNull();
    var response = observedResponse.get();
    assertThat(response).isNotNull();
    assertThat(response.getRequest())
        .isEqualTo(EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request));
    var decoded = EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    assertThat(decoded.receipt().canonicalBytes()).isEqualTo(fixture.receipt().canonicalBytes());
    assertThat(decoded.receipt().authorizationBindingBytes())
        .isEqualTo(fixture.authorization().canonicalBytes());
    verify(repository).read(NAMESPACE, binding.intakeRequestId());
  }

  @Test
  void absentReceiptIsNotConvertedToAnAbortOrAnyTerminalEvidence() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var binding = fixture.authorization();
    when(repository.read(NAMESPACE, binding.intakeRequestId())).thenReturn(Optional.empty());
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer(NAMESPACE);
    var previous = accountContext.attach();
    try {
      assertStatus(() -> service.read(request(binding)), Status.Code.NOT_FOUND);
    } finally {
      accountContext.detach(previous);
    }
    verify(repository).read(NAMESPACE, binding.intakeRequestId());
  }

  @Test
  void changedCompleteOriginalAuthorizationIsAlreadyExistsAndCorruptReadbackIsDataLoss() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var changedAuthorization =
        EntitySelectedSourceIntakeTerminalReadFixtures.create().authorization();
    assertThat(changedAuthorization.intakeRequestId())
        .isEqualTo(fixture.authorization().intakeRequestId());
    assertThat(changedAuthorization.canonicalBytes())
        .isNotEqualTo(fixture.authorization().canonicalBytes());
    when(repository.read(NAMESPACE, fixture.authorization().intakeRequestId()))
        .thenReturn(Optional.of(fixture.receipt()));
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer(NAMESPACE);
    var previous = accountContext.attach();
    try {
      assertStatus(() -> service.read(request(changedAuthorization)), Status.Code.ALREADY_EXISTS);

      when(repository.read(NAMESPACE, fixture.authorization().intakeRequestId()))
          .thenThrow(new IllegalArgumentException("corrupt stored Entity receipt"));
      assertStatus(() -> service.read(request(fixture.authorization())), Status.Code.DATA_LOSS);
    } finally {
      accountContext.detach(previous);
    }
  }

  @Test
  void rejectsWrongPeerNamespaceOwnerEndUserAndAmbientSqlBeforeRepositoryRead() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var request = request(fixture.authorization());
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);

    assertStatus(() -> service.read(request), Status.Code.UNAUTHENTICATED);

    var wrongPeer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/test/sa/game-design-service",
                    NAMESPACE,
                    "game-design-service"));
    var previous = wrongPeer.attach();
    try {
      assertStatus(() -> service.read(request), Status.Code.PERMISSION_DENIED);
    } finally {
      wrongPeer.detach(previous);
    }

    var otherNamespaceService =
        new EntityEmptySelectedSourceIntakeTerminalReadService(repository, "other");
    var otherAccount = accountPeer("other");
    previous = otherAccount.attach();
    try {
      assertStatus(() -> otherNamespaceService.read(request), Status.Code.PERMISSION_DENIED);
    } finally {
      otherAccount.detach(previous);
    }

    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(
                    NAMESPACE, fixture.wrongOwnerAuthorization()))
        .isInstanceOf(IllegalArgumentException.class);

    var accountContext = accountPeer(NAMESPACE);
    previous = accountContext.attach();
    try {
      SessionContext.setContext("101", java.util.List.of(), java.util.Map.of());
      assertStatus(() -> service.read(request), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertStatus(() -> service.read(request), Status.Code.FAILED_PRECONDITION);
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertStatus(() -> service.read(request), Status.Code.FAILED_PRECONDITION);
      TransactionSynchronizationManager.clearSynchronization();
    } finally {
      accountContext.detach(previous);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void grpcAuthenticatesBeforeMalformedRequestDecodeOrRepositoryLookup() {
    var repository = mock(EntityEmptySelectedSourceIntakeRepository.class);
    var service = new EntityEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var grpc = new EntitySelectedSourceIntakeTerminalReadGrpcService(service);

    var unauthenticatedError = invokeMalformed(grpc, null);
    assertThat(Status.fromThrowable(unauthenticatedError).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    var wrongPeer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/test/sa/game-design-service",
                    NAMESPACE,
                    "game-design-service"));
    var previous = wrongPeer.attach();
    try {
      assertThat(Status.fromThrowable(invokeMalformed(grpc, null)).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      wrongPeer.detach(previous);
    }

    var accountContext = accountPeer(NAMESPACE);
    previous = accountContext.attach();
    try {
      assertThat(
              Status.fromThrowable(
                      invokeMalformed(
                          grpc, ReadSelectedSourceIntakeTerminalRequest.getDefaultInstance()))
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    } finally {
      accountContext.detach(previous);
    }
    verifyNoInteractions(repository);
  }

  private static EntitySelectedSourceIntakeTerminalReadEvidence.Request request(
      net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding
          binding) {
    return EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, binding);
  }

  private static void assertStatus(Runnable action, Status.Code expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static Context accountPeer(String namespace) {
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
    return Context.ROOT.withValue(
        GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri, namespace, "account-service"));
  }

  private static Throwable invokeMalformed(
      EntitySelectedSourceIntakeTerminalReadGrpcService grpc,
      ReadSelectedSourceIntakeTerminalRequest request) {
    var observedResponse = new AtomicReference<ReadSelectedSourceIntakeTerminalResponse>();
    var observedError = new AtomicReference<Throwable>();
    grpc.readSelectedSourceIntakeTerminal(
        request == null ? ReadSelectedSourceIntakeTerminalRequest.getDefaultInstance() : request,
        responseObserver(observedResponse, observedError));
    assertThat(observedResponse.get()).isNull();
    assertThat(observedError.get()).isNotNull();
    return observedError.get();
  }

  private static StreamObserver<ReadSelectedSourceIntakeTerminalResponse> responseObserver(
      AtomicReference<ReadSelectedSourceIntakeTerminalResponse> response,
      AtomicReference<Throwable> error) {
    return new StreamObserver<>() {
      @Override
      public void onNext(ReadSelectedSourceIntakeTerminalResponse value) {
        response.set(value);
      }

      @Override
      public void onError(Throwable failure) {
        error.set(failure);
      }

      @Override
      public void onCompleted() {}
    };
  }
}
