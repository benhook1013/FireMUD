package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameLogicIntakeAuthorizationRecoveryTest {
  private static final String CREDENTIAL = "unchanged.creator.credential";
  private static final String NAMESPACE = "test";

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactFinalizedRetryRecoversBeforeCapturingMutableEnvironmentAuthority() {
    var selected = selected();
    var intakeRequestId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    var scope =
        new GameLogicIntakeSourceReadScope(
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            intakeRequestId,
            actor,
            selected,
            "spiffe://firemud/ns/test/sa/account-service",
            GameLogicIntakeSourceReadScope.PURPOSE);
    var authorization = authorization(scope, actor);
    var actors = mock(AccountControlUiActorService.class);
    var fences =
        mock(
            net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
                .class);
    var repository = mock(AccountGameLogicIntakeAuthorizationRepository.class);
    var sourceClient = mock(GameplayRuleSourceReadClient.class);
    when(repository.findSourceReadScope(
            eq(intakeRequestId), eq(selected), eq(NAMESPACE), anyString()))
        .thenReturn(Optional.of(scope));
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            new AccountGameLogicIntakeSourceReadRecovery(
                scope,
                AccountGameLogicIntakeSourceReadRecovery.State.FINALIZED,
                Optional.of(authorization)));

    var service =
        new AccountGameLogicIntakeAuthorizationService(
            actors, fences, repository, sourceClient, new NoopTransactionManager(), NAMESPACE);
    var captures = new AtomicInteger();
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var prior = context.attach();
    try {
      var recovered =
          service.authorizeWithEnvironmentCapture(
              CREDENTIAL,
              intakeRequestId,
              selected,
              () -> {
                captures.incrementAndGet();
                throw new IllegalStateException("mutable environment must not be read on retry");
              });
      assertThat(recovered.canonicalBytes()).containsExactly(authorization.canonicalBytes());
    } finally {
      context.detach(prior);
    }
    assertThat(captures).hasValue(0);
    verify(repository)
        .findSourceReadScope(eq(intakeRequestId), eq(selected), eq(NAMESPACE), anyString());
    verify(repository).recoverSourceRead(scope);
    verifyNoInteractions(actors, fences, sourceClient);
  }

  @Test
  void requiresFreshAuthorityAfterRemoteSourceReadBeforeFinalization() {
    var selected = selected();
    var intakeRequestId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    var scope =
        new GameLogicIntakeSourceReadScope(
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            intakeRequestId,
            actor,
            selected,
            "spiffe://firemud/ns/test/sa/account-service",
            GameLogicIntakeSourceReadScope.PURPOSE);
    var authorization = authorization(scope, actor);
    var actors = mock(AccountControlUiActorService.class);
    var fences = mock(DraftAuthorizationFenceRepository.class);
    var repository = mock(AccountGameLogicIntakeAuthorizationRepository.class);
    var sourceClient = mock(GameplayRuleSourceReadClient.class);
    var initialEnvironment = mock(CapturedEnvironmentBoundary.class);
    var events = new ArrayList<String>();
    var current = mock(AccountControlUiActorService.Current.class);
    var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
    when(current.source()).thenReturn(snapshot);
    when(snapshot.sources()).thenReturn(List.of());
    when(repository.findSourceReadScope(
            eq(intakeRequestId), eq(selected), eq(NAMESPACE), anyString()))
        .thenReturn(Optional.empty());
    when(repository.reserveSourceRead(
            eq(intakeRequestId), eq(selected), eq(current), eq(NAMESPACE), any(Runnable.class)))
        .thenReturn(scope);
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            new AccountGameLogicIntakeSourceReadRecovery(
                scope, AccountGameLogicIntakeSourceReadRecovery.State.RESERVED, Optional.empty()));
    when(sourceClient.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenAnswer(
            call -> {
              events.add("source-read");
              return new GameplayRuleSourceReadEvidence(
                  call.getArgument(0), authorization.source());
            });
    var actorCalls = new AtomicInteger();
    doAnswer(
            call -> {
              events.add("actor-" + actorCalls.incrementAndGet());
              @SuppressWarnings("unchecked")
              Function<AccountControlUiActorService.Current, Object> action =
                  (Function<AccountControlUiActorService.Current, Object>) call.getArgument(3);
              return action.apply(current);
            })
        .when(actors)
        .withCurrent(anyString(), any(), any(), any());

    var service =
        new AccountGameLogicIntakeAuthorizationService(
            actors, fences, repository, sourceClient, new NoopTransactionManager(), NAMESPACE);
    var environmentCaptures = new AtomicInteger();
    var currentnessUnavailable = new IllegalStateException("fresh authority unavailable");
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var prior = context.attach();
    try {
      assertThatThrownBy(
              () ->
                  service.authorizeWithEnvironmentCapture(
                      CREDENTIAL,
                      intakeRequestId,
                      selected,
                      () -> {
                        if (environmentCaptures.incrementAndGet() == 1) {
                          events.add("capture-reservation");
                          return initialEnvironment;
                        }
                        events.add("capture-finalization");
                        throw currentnessUnavailable;
                      }))
          .isSameAs(currentnessUnavailable);
    } finally {
      context.detach(prior);
    }

    assertThat(environmentCaptures).hasValue(2);
    assertThat(actorCalls).hasValue(1);
    assertThat(events)
        .containsExactly("capture-reservation", "actor-1", "source-read", "capture-finalization");
    verify(repository, never()).finalizeSourceRead(any(), any(), any(), any());
  }

  private static DraftCommitBinding selected() {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-1",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                version.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameLogicIntakeAuthorizationBinding authorization(
      GameLogicIntakeSourceReadScope scope, UUID actor) {
    var selected = scope.selected();
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                java.util.Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selected.canonicalJson(),
                    "bindingDigest",
                    selected.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    UUID.randomUUID().toString(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    return new GameLogicIntakeAuthorizationBinding(
        scope.operationId(),
        scope.fenceId(),
        scope.intakeRequestId(),
        actor,
        source,
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private static final class NoopTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
