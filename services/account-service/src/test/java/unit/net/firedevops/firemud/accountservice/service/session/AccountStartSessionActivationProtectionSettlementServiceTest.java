package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadClient;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked composition/order proofs only; no authenticated producer or physical-source proof. */
class AccountStartSessionActivationProtectionSettlementServiceTest {
  private static final String NAMESPACE = "world-runtime";

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldPeerBeforeValidatingLookupSelectors() {
    Harness harness = new Harness();
    assertCode(Status.Code.UNAUTHENTICATED, () -> harness.service.settle(null, 0L));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other-runtime", () -> harness.service.settle(null, 0L)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            peer("world-runtime", "game-session-service", () -> harness.service.settle(null, 0L)));

    verifyNoInteractions(harness.repository, harness.worldClient);
  }

  @Test
  void rejectsEndUserAndAmbientAccountTransactionBeforeOwnerOrWorldRead() {
    Harness harness = new Harness();
    SessionContext.setContext("authenticated-user", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer(NAMESPACE, () -> harness.service.settle(null, 0L)));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> peer(NAMESPACE, () -> harness.service.settle(null, 0L)));
    verifyNoInteractions(harness.repository, harness.worldClient);
  }

  @Test
  void resolvesExpiredHistoricalProtectionOnlyFromWorldThenReturnsIndependentReadback() {
    Harness harness = new Harness();
    harness.prepareNoPriorSettlement();
    StoredSettlement first =
        new StoredSettlement(harness.terminal, Instant.parse("2026-10-11T01:02:03Z"));
    when(harness.repository.settleExact(harness.terminal)).thenReturn(first);
    when(harness.repository.findSettlementExact(harness.protectionId, Harness.PROTECTION_FENCE))
        .thenReturn(Optional.empty(), Optional.of(first));
    when(harness.worldClient.read(any(WorldOriginalStartSessionActivationTerminalReadInput.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              WorldOriginalStartSessionActivationTerminalReadInput input =
                  invocation.getArgument(0);
              assertThat(input.protectionEvidence().canonicalBytes())
                  .containsExactly(harness.evidenceBytes);
              return harness.terminal;
            });

    StoredSettlement actual =
        peer(
            NAMESPACE,
            () -> harness.service.settle(harness.protectionId, Harness.PROTECTION_FENCE));

    assertThat(actual.terminal().canonicalBytes()).containsExactly(harness.terminalBytes);
    verify(harness.repository).settleExact(harness.terminal);
    verify(harness.worldClient)
        .read(any(WorldOriginalStartSessionActivationTerminalReadInput.class));
  }

  @Test
  void missingWorldTerminalNeverSettlesOrReleasesAccountProtection() {
    Harness harness = new Harness();
    harness.prepareNoPriorSettlement();
    when(harness.worldClient.read(any(WorldOriginalStartSessionActivationTerminalReadInput.class)))
        .thenReturn(null);

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer(
                NAMESPACE,
                () -> harness.service.settle(harness.protectionId, Harness.PROTECTION_FENCE)));

    verify(harness.repository, never()).settleExact(any());
  }

  @Test
  void worldReadTimeoutNeverSettlesOrReleasesAccountProtection() {
    Harness harness = new Harness();
    harness.prepareNoPriorSettlement();
    when(harness.worldClient.read(any(WorldOriginalStartSessionActivationTerminalReadInput.class)))
        .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());

    assertCode(
        Status.Code.DEADLINE_EXCEEDED,
        () ->
            peer(
                NAMESPACE,
                () -> harness.service.settle(harness.protectionId, Harness.PROTECTION_FENCE)));

    verify(harness.repository, never()).settleExact(any());
  }

  @Test
  void priorExactReceiptRecoversResponseLossWithoutAnotherWorldRead() {
    Harness harness = new Harness();
    StoredSettlement prior =
        new StoredSettlement(harness.terminal, Instant.parse("2026-10-11T01:02:03Z"));
    when(harness.repository.findHistoricalExact(harness.protectionId, Harness.PROTECTION_FENCE))
        .thenReturn(Optional.of(harness.evidence));
    when(harness.repository.findSettlementExact(harness.protectionId, Harness.PROTECTION_FENCE))
        .thenReturn(Optional.of(prior));

    StoredSettlement recovered =
        peer(
            NAMESPACE,
            () -> harness.service.settle(harness.protectionId, Harness.PROTECTION_FENCE));

    assertThat(recovered.terminal().canonicalBytes()).containsExactly(harness.terminalBytes);
    verifyNoInteractions(harness.worldClient);
    verify(harness.repository, never()).settleExact(any());
  }

  @Test
  void mismatchedWorldReceiptAndUnmatchedReadbackNeverReturnAsSettlement() {
    Harness mismatch = new Harness();
    mismatch.prepareNoPriorSettlement();
    AccountStartSessionActivationProtectionEvidence otherEvidence =
        mock(AccountStartSessionActivationProtectionEvidence.class);
    when(otherEvidence.canonicalBytes()).thenReturn("different Account receipt".getBytes());
    when(mismatch.terminal.accountProtectionEvidence()).thenReturn(otherEvidence);
    when(mismatch.worldClient.read(any(WorldOriginalStartSessionActivationTerminalReadInput.class)))
        .thenReturn(mismatch.terminal);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer(
                NAMESPACE,
                () -> mismatch.service.settle(mismatch.protectionId, Harness.PROTECTION_FENCE)));
    verify(mismatch.repository, never()).settleExact(any());

    Harness readback = new Harness();
    readback.prepareNoPriorSettlement();
    StoredSettlement committed =
        new StoredSettlement(readback.terminal, Instant.parse("2026-10-11T01:02:03Z"));
    when(readback.repository.settleExact(readback.terminal)).thenReturn(committed);
    when(readback.repository.findSettlementExact(readback.protectionId, Harness.PROTECTION_FENCE))
        .thenReturn(Optional.empty(), Optional.empty());
    when(readback.worldClient.read(any(WorldOriginalStartSessionActivationTerminalReadInput.class)))
        .thenReturn(readback.terminal);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer(
                NAMESPACE,
                () -> readback.service.settle(readback.protectionId, Harness.PROTECTION_FENCE)));
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static <T> T peer(String namespace, java.util.function.Supplier<T> action) {
    return peer(namespace, "world-management-service", action);
  }

  private static <T> T peer(
      String namespace, String service, java.util.function.Supplier<T> action) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
            .orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static final class Harness {
    final UUID protectionId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final long PROTECTION_FENCE = 17L;
    final UUID activationId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    final byte[] evidenceBytes = "exact historical Account evidence".getBytes();
    final byte[] terminalBytes = "exact World terminal receipt".getBytes();
    final AccountStartSessionActivationProtectionRepository repository =
        mock(AccountStartSessionActivationProtectionRepository.class);
    final WorldOriginalStartSessionActivationTerminalReadClient worldClient =
        mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
    final AccountStartSessionActivationProtectionEvidence evidence =
        mock(
            AccountStartSessionActivationProtectionEvidence.class,
            org.mockito.Mockito.withSettings()
                .defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
    final WorldOriginalStartSessionActivationTerminalEvidence terminal =
        mock(WorldOriginalStartSessionActivationTerminalEvidence.class);
    final AccountStartSessionActivationProtectionSettlementService service =
        new AccountStartSessionActivationProtectionSettlementService(
            repository, worldClient, new TestTransactions(), NAMESPACE);

    Harness() {
      when(evidence.accountProtectionId()).thenReturn(protectionId);
      when(evidence.accountProtectionFence()).thenReturn(PROTECTION_FENCE);
      when(evidence.canonicalBytes()).thenReturn(evidenceBytes);
      when(evidence
              .request()
              .originalTuple()
              .preAuthorizationTuple()
              .action()
              .scope()
              .targetNamespace())
          .thenReturn(NAMESPACE);
      when(evidence.request().worldActivationRequest().activationRequestId())
          .thenReturn(activationId);
      when(terminal.accountProtectionEvidence()).thenReturn(evidence);
      when(terminal.activationRequestId()).thenReturn(activationId);
      when(terminal.worldActivationFence()).thenReturn(29L);
      when(terminal.outcome()).thenReturn(WorldCanonicalInstanceActivation.Outcome.COMMITTED);
      when(terminal.canonicalBytes()).thenReturn(terminalBytes);
      when(terminal.terminalDigest()).thenReturn("sha256:" + "a".repeat(64));
    }

    void prepareNoPriorSettlement() {
      when(repository.findHistoricalExact(protectionId, PROTECTION_FENCE))
          .thenReturn(Optional.of(evidence));
      when(repository.findSettlementExact(protectionId, PROTECTION_FENCE))
          .thenReturn(Optional.empty(), Optional.empty());
    }
  }

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
