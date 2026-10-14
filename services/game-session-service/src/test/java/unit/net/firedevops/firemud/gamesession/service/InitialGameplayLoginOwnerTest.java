package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult.NonSecretEvidence;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Source ordering and owned-buffer proof; no Account, Redis, wire, or admission claim. */
class InitialGameplayLoginOwnerTest {
  private static final UUID ACCOUNT = UUID.fromString("2bba6eb7-4874-47e1-af9d-caa9e2d5ef72");
  private static final UUID JTI = UUID.fromString("85c3976c-ef18-4e0d-9f7d-0466854973c4");

  @Test
  void commitsCoordinatesCreatesPendingAuthenticatesRecordsWitnessThenInstallsExactResult() {
    var success = successfulOutcome();
    var fixture = success.fixture();
    var outcome = success.outcome();
    var result = success.result();
    var secret = success.secret();
    var coordinates = outcome.coordinates();
    AuthenticateRequest request = fixture.accountRequest;

    assertThat(outcome.status())
        .isEqualTo(InitialGameplayLoginOwner.Status.INSTALLED_NON_ADMITTING);
    assertThat(outcome.failureCode()).isEmpty();
    assertThat(outcome.toString()).doesNotContain("private-secret");
    assertThat(coordinates.requestId()).isNotEqualTo(coordinates.callerContextId());
    assertThat(coordinates.requestId().version()).isEqualTo(4);
    assertThat(coordinates.callerContextId().version()).isEqualTo(4);
    assertThat(coordinates.expectedAttemptFence()).isEqualTo(1);
    assertThat(request.getRequestId()).isEqualTo(coordinates.requestId().toString());
    assertThat(request.getSourceContext().getCallerContextId())
        .isEqualTo(coordinates.callerContextId().toString());
    assertThat(request.getSourceContext().getTransportClassValue()).isEqualTo(1);
    assertThat(request.getPassword()).isEqualTo("original-private-secret");
    assertThat(secret).containsOnly('\0');
    verify(result, never()).close();
    var transferredResult = outcome.takePrivateResult().orElseThrow();
    assertThat(transferredResult).isSameAs(result);
    outcome.close();
    verify(result, never()).close();
    transferredResult.close();
    verify(result).close();
    InOrder order = inOrder(fixture.cleanup, fixture.contexts, fixture.account);
    order.verify(fixture.cleanup).reserve(coordinates);
    order.verify(fixture.contexts).createPending(coordinates);
    order.verify(fixture.account).authenticate(any(AuthenticateRequest.class), any());
    order.verify(fixture.cleanup).recordVerifiedResult(coordinates, result);
    order.verify(fixture.contexts).install(coordinates, result);
    verify(fixture.cleanup, never()).abandonAndRetire(coordinates);
  }

  @Test
  void closingInstalledOutcomeBeforeTransferWipesAndCleansTheExactOriginal() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var result = success.result();

    outcome.close();

    verify(result).close();
    verify(success.fixture().cleanup).abandonAndRetire(outcome.coordinates());
    assertThat(outcome.custodyLossCleanup().orElseThrow().status())
        .isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    org.assertj.core.api.Assertions.assertThatThrownBy(outcome::takePrivateResult)
        .isInstanceOf(IllegalStateException.class);
    assertThat(success.secret()).containsOnly('\0');
  }

  @Test
  void transferredResultAndOutcomeCloseLeaveCleanupOwnershipToExplicitLossCallback() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var result = success.result();
    var coordinates = outcome.coordinates();
    var transferred = outcome.takePrivateResult().orElseThrow();

    outcome.close();

    assertThat(transferred).isSameAs(result);
    verify(result, never()).close();
    verify(success.fixture().cleanup, never()).abandonAndRetire(any());

    doThrow(new IllegalStateException("qualified cleanup unavailable"))
        .when(success.fixture().cleanup)
        .abandonAndRetire(coordinates);
    var cleanup = outcome.abandonOriginalAfterCustodyLoss();

    assertThat(cleanup.coordinates()).isEqualTo(coordinates);
    assertThat(cleanup.status()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    verify(result).close();
    verify(success.fixture().cleanup).abandonAndRetire(coordinates);
  }

  private static SuccessfulOutcome successfulOutcome() {
    var fixture = new Fixture();
    var secret = "original-private-secret".toCharArray();
    var credentials = presentation(secret);
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenAnswer(
            invocation -> {
              fixture.accountRequest = invocation.getArgument(0);
              return result;
            });
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.contexts.install(any(), any()))
        .thenAnswer(invocation -> Optional.of(installed(invocation.getArgument(0))));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    return new SuccessfulOutcome(fixture, fixture.owner.receive(credentials), result, secret);
  }

  @Test
  void uncertainPendingCreationSelectsOriginalCleanupWithoutAccountDispatch() {
    var fixture = new Fixture();
    var secret = "still-private".toCharArray();
    var credentials = presentation(secret);
    when(fixture.contexts.createPending(any())).thenReturn(Optional.empty());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.cleanupStatus()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verifyNoInteractions(fixture.account);
    verify(fixture.contexts, never()).install(any(), any());
  }

  @Test
  void ambiguousAuthenticateFailureSelectsCleanupAndNeverInstalls() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.failureCode()).contains("UNAVAILABLE");
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
  }

  @Test
  void lostCustodyAfterVerifiedAccountResponseRecordsWitnessThenCleansWithoutInstall() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var credentials = presentation(secret);
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenAnswer(
            invocation -> {
              credentials.close();
              return result;
            });
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.failureCode()).contains("CUSTODY_LOST");
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(any(), any());
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
    verify(result).close();
  }

  @Test
  void unavailableWitnessReadbackStillUsesExactOriginalCleanupFreeze() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any())).thenReturn(result);
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenThrow(new IllegalStateException("journal readback unavailable"));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(outcome.coordinates(), result);
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
    verify(result).close();
  }

  @Test
  void failedExactInstallClosesOriginalAndRetiresFromTheSameInvocationResult() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any())).thenReturn(result);
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.contexts.install(any(), any())).thenReturn(Optional.empty());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(any(), any());
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(result).close();
  }

  private static InitialGameplayLoginCredentialPresentation presentation(char[] secret) {
    return new InitialGameplayLoginCredentialPresentation(
        "player@example.test", "203.0.113.10", secret);
  }

  private static InitialGameplayLoginJournalSnapshot snapshot(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginJournalSnapshot(
        coordinates, false, Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static InitialGameplayLoginJournalSnapshot snapshotWithWitness(
      InitialGameplayLoginAttemptCoordinates coordinates, CanonicalGameplayLoginResult result) {
    var evidence = result.evidence();
    var witness =
        InitialGameplayLoginTokenWitness.newBuilder()
            .setTokenJti(evidence.tokenJti().toString())
            .setTokenSha256(evidence.tokenSha256())
            .setTokenGeneration(Long.parseLong(evidence.tokenGeneration()))
            .setIssuanceFence(Long.parseLong(evidence.issuanceFence()))
            .build();
    return new InitialGameplayLoginJournalSnapshot(
        coordinates, false, Optional.of(witness), Optional.empty(), Optional.empty());
  }

  private static NonSecretEvidence evidence(InitialGameplayLoginAttemptCoordinates coordinates) {
    return new NonSecretEvidence(
        ACCOUNT,
        coordinates.requestId(),
        coordinates.callerContextId(),
        JTI,
        "a".repeat(64),
        "game-session-account-delegation/v1",
        1000,
        1000,
        1200,
        "1",
        "3",
        ByteString.EMPTY,
        ByteString.EMPTY,
        ByteString.EMPTY);
  }

  private static InitialGameplayLoginContextState installed(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    var state = mock(InitialGameplayLoginContextState.class);
    when(state.state()).thenReturn(InitialGameplayLoginContextState.State.INSTALLED);
    when(state.coordinates()).thenReturn(coordinates);
    return state;
  }

  private static InitialGameplayLoginCleanupOwner.CleanupObservation held(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginCleanupOwner.CleanupObservation(
        coordinates,
        InitialGameplayLoginCleanupOwner.Status.HELD,
        Optional.<InitialGameplayLoginDisposalResult>empty(),
        Optional.<InitialGameplayLoginDisposalResult>empty());
  }

  private static final class Fixture {
    private final InitialGameplayLoginContextRedisClient contexts =
        mock(InitialGameplayLoginContextRedisClient.class);
    private final InitialGameplayLoginCleanupOwner cleanup =
        mock(InitialGameplayLoginCleanupOwner.class);
    private final CanonicalGameplayLoginAccountClient account =
        mock(CanonicalGameplayLoginAccountClient.class);
    private final InitialGameplayLoginOwner owner =
        new InitialGameplayLoginOwner(contexts, cleanup, account);
    private InitialGameplayLoginAttemptCoordinates coordinates;
    private AuthenticateRequest accountRequest;

    private Fixture() {
      when(cleanup.reserve(any()))
          .thenAnswer(
              invocation -> {
                coordinates = invocation.getArgument(0);
                return snapshot(coordinates);
              });
      when(account.authenticate(any(AuthenticateRequest.class), any()))
          .thenAnswer(
              invocation -> {
                accountRequest = invocation.getArgument(0);
                return null;
              });
    }
  }

  private record SuccessfulOutcome(
      Fixture fixture,
      InitialGameplayLoginOwner.Outcome outcome,
      CanonicalGameplayLoginResult result,
      char[] secret) {}
}
