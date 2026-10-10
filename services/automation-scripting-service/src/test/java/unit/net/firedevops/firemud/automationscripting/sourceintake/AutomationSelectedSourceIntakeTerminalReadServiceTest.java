package net.firedevops.firemud.automationscripting.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked owner-storage and peer tests do not establish physical Automation owner proof. */
class AutomationSelectedSourceIntakeTerminalReadServiceTest {
  private static final String NAMESPACE = "example";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/example/sa/account-service";
  private static final byte[] BINDING_BYTES = {1, 2, 3, 4};

  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void exactRetriesReturnOnlyTheOriginalCommittedEmptyReceipt() {
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var binding = binding();
    var receipt = receipt(BINDING_BYTES);
    when(repository.readCommittedTerminal(binding)).thenReturn(Optional.of(receipt));
    var service = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer();
    var previous = accountContext.attach();
    try {
      var first = service.read(request(binding));
      var retry = service.read(request(binding));

      assertThat(first.receipt()).isSameAs(receipt);
      assertThat(retry.receipt()).isSameAs(receipt);
      assertThat(first.request().readRequestId()).isNotEqualTo(retry.request().readRequestId());
      verify(repository, org.mockito.Mockito.times(2)).readCommittedTerminal(binding);
    } finally {
      accountContext.detach(previous);
    }
  }

  @Test
  void missingReceiptIsNotConvertedToTerminalEvidence() {
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var binding = binding();
    when(repository.readCommittedTerminal(binding)).thenReturn(Optional.empty());
    var service = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer();
    var previous = accountContext.attach();
    try {
      assertThatThrownBy(() -> service.read(request(binding)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.NOT_FOUND));
    } finally {
      accountContext.detach(previous);
    }
  }

  @Test
  void rejectsChangedTupleAndCorruptStoredReceipt() {
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var binding = binding();
    when(repository.readCommittedTerminal(binding))
        .thenThrow(
            new AutomationEmptySelectedSourceIntakeRepository.IntakeConflictException(
                "changed exact authorization"));
    var service = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var accountContext = accountPeer();
    var previous = accountContext.attach();
    try {
      assertThatThrownBy(() -> service.read(request(binding)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.ALREADY_EXISTS));

      doThrow(new IllegalArgumentException("corrupt canonical receipt"))
          .when(repository)
          .readCommittedTerminal(binding);
      assertThatThrownBy(() -> service.read(request(binding)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.DATA_LOSS));
    } finally {
      accountContext.detach(previous);
    }
  }

  @Test
  void rejectsWrongPeerEndUserAndAmbientSqlBeforeRepositoryLookup() {
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var binding = binding();
    var request = request(binding);
    var service = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));

    var wrongPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/game-design-service", NAMESPACE, "game-design-service");
    var wrongContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    var previous = wrongContext.attach();
    try {
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    } finally {
      wrongContext.detach(previous);
    }

    var accountContext = accountPeer();
    previous = accountContext.attach();
    try {
      SessionContext.setContext("1001", List.of(), Map.of());
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      SessionContext.clear();

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      TransactionSynchronizationManager.clearSynchronization();
    } finally {
      accountContext.detach(previous);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void transportAuthenticatesBeforeDecodingOrLookup() {
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var service = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NAMESPACE);
    var grpc = new AutomationSelectedSourceIntakeTerminalReadGrpcService(service);
    var observedError = new AtomicReference<Throwable>();
    StreamObserver<ReadSelectedSourceIntakeTerminalResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(ReadSelectedSourceIntakeTerminalResponse value) {
            throw new AssertionError("Unauthenticated caller received a response");
          }

          @Override
          public void onError(Throwable error) {
            observedError.set(error);
          }

          @Override
          public void onCompleted() {
            throw new AssertionError("Unauthenticated caller completed a response");
          }
        };

    grpc.readSelectedSourceIntakeTerminal(
        ReadSelectedSourceIntakeTerminalRequest.getDefaultInstance(), observer);

    assertThat(observedError.get()).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(observedError.get()).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  private static SelectedOwnerIntakeAuthorizationBinding binding() {
    var binding = mock(SelectedOwnerIntakeAuthorizationBinding.class);
    when(binding.owner())
        .thenReturn(
            net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    when(binding.targetNamespace()).thenReturn(NAMESPACE);
    when(binding.schema()).thenReturn("account-automation-intake-authorization/v1");
    when(binding.purpose()).thenReturn("AUTOMATION_INTAKE_RETENTION");
    when(binding.canonicalBytes()).thenReturn(BINDING_BYTES.clone());
    when(binding.operationId()).thenReturn(UUID.randomUUID());
    when(binding.fenceId()).thenReturn(UUID.randomUUID());
    when(binding.intakeRequestId()).thenReturn(UUID.randomUUID());
    return binding;
  }

  private static AutomationSelectedSourceIntakeTerminalReadEvidence.Request request(
      SelectedOwnerIntakeAuthorizationBinding binding) {
    return AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, binding);
  }

  private static AutomationEmptySelectedSourceIntakeReceipt receipt(byte[] bindingBytes) {
    var receipt = mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(receipt.targetNamespace()).thenReturn(NAMESPACE);
    when(receipt.authorizationBindingBytes()).thenReturn(bindingBytes.clone());
    when(receipt.outcome()).thenReturn("COMMITTED_EMPTY");
    return receipt;
  }

  private static Context accountPeer() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(ACCOUNT_URI, NAMESPACE, "account-service"));
  }
}
