package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadClient;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked ordering and denial checks only; no producer, currentness, or physical-source proof. */
class AccountStartSessionActivationProtectionAcquisitionServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID MUTATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldPeerBeforeTupleOrActivationDecoding() {
    Harness harness = new Harness();

    assertCode(
        Status.Code.UNAUTHENTICATED, () -> harness.service.acquire(harness.malformedRequest));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other-runtime", () -> harness.service.acquire(harness.malformedRequest)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            peer(
                "world-runtime",
                "game-session-service",
                () -> harness.service.acquire(harness.malformedRequest)));

    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptRepository);
    verifyNoInteractions(harness.protectionRepository);
    verifyNoInteractions(harness.currentAttemptClient);
    verifyNoInteractions(harness.launchReadClient);
  }

  @Test
  void rejectsAmbientAccountSqlBeforeDecodingOrEitherRemoteOwnerRead() {
    Harness harness = new Harness();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            peer(
                NAMESPACE,
                "world-management-service",
                () -> harness.service.acquire(harness.malformedRequest)));

    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptRepository);
    verifyNoInteractions(harness.protectionRepository);
    verifyNoInteractions(harness.currentAttemptClient);
    verifyNoInteractions(harness.launchReadClient);
  }

  private static <T> T peer(String namespace, java.util.function.Supplier<T> action) {
    return peer(namespace, "world-management-service", action);
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
            .orElseThrow();
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static final class Harness {
    final AccountStartSessionOperatorAuthorizationService issuer =
        mock(AccountStartSessionOperatorAuthorizationService.class);
    final AccountStartSessionWorldParticipationRepository participationRepository =
        mock(AccountStartSessionWorldParticipationRepository.class);
    final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository =
        mock(AccountStartSessionWorldOriginalAttemptEvidenceRepository.class);
    final AccountStartSessionActivationProtectionRepository protectionRepository =
        mock(AccountStartSessionActivationProtectionRepository.class);
    final OriginalStartSessionCurrentAttemptClient currentAttemptClient =
        mock(OriginalStartSessionCurrentAttemptClient.class);
    final OriginalStartSessionLaunchReadClient launchReadClient =
        mock(OriginalStartSessionLaunchReadClient.class);
    final AccountStartSessionActivationProtectionAcquisitionService service =
        new AccountStartSessionActivationProtectionAcquisitionService(
            issuer,
            participationRepository,
            attemptRepository,
            protectionRepository,
            currentAttemptClient,
            launchReadClient,
            mock(PlatformTransactionManager.class),
            NAMESPACE);
    final AccountStartSessionActivationProtectionAcquisitionService.AcquisitionRequest
        malformedRequest =
            new AccountStartSessionActivationProtectionAcquisitionService.AcquisitionRequest(
                new byte[] {1, 2, 3}, MUTATION_ID, ATTEMPT_ID, 7L, null);
  }
}
