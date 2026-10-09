package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameLogicGameplayRuleIntakeServiceTest {
  private static final String NAMESPACE = "test";

  @Test
  void exactHeldAuthorizationAndSelectedSourceAreRetainedAsOneExactTerminal() {
    var fixture = new Fixture();
    var authorization = authorization();
    var selected = authorization.source();
    fixture.respondFor(authorization, selected);

    var terminal = asGameDesign(() -> fixture.service.retain(authorization));

    assertThat(terminal.outcome()).isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED);
    assertThat(terminal.authorizationBytes()).containsExactly(authorization.canonicalBytes());
    assertThat(terminal.selectedSourceBytes()).containsExactly(selected.canonicalBytes());
    assertThat(terminal.manifestBytes())
        .containsExactly(selected.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    assertThat(
            GameLogicGameplayRuleIntakeTerminal.fromStored(terminal.canonicalBytes())
                .canonicalBytes())
        .containsExactly(terminal.canonicalBytes());
    var calls = inOrder(fixture.accountReader, fixture.gameDesignReader);
    calls
        .verify(fixture.accountReader)
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    calls.verify(fixture.gameDesignReader).read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void exactRetryReturnsOriginalBytesWithoutRecheckingSettledAccountOrLatestSource() {
    var fixture = new Fixture();
    var authorization = authorization();
    var operation = new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization);
    var original =
        GameLogicGameplayRuleIntakeTerminal.retained(
            operation,
            authorization.source().canonicalBytes(),
            authorization.source().manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    fixture.stored.set(original);
    fixture.repositoryFindsStored();

    var retry = asGameDesign(() -> fixture.service.retain(authorization));

    assertThat(retry.canonicalBytes()).containsExactly(original.canonicalBytes());
    verify(fixture.accountReader, never())
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void explicitAbortIsDurableAndBlocksLaterRetentionWithoutInferringFromMissingReads() {
    var fixture = new Fixture();
    var authorization = authorization();
    fixture.repositoryFindsStored();
    fixture.respondAuthorizationOnly(authorization);

    var aborted = asGameDesign(() -> fixture.service.abort(authorization));
    var retry = asGameDesign(() -> fixture.service.retain(authorization));

    assertThat(aborted.outcome()).isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.ABORTED);
    assertThat(retry.canonicalBytes()).containsExactly(aborted.canonicalBytes());
    assertThat(asGameDesign(() -> fixture.service.readTerminal(authorization))).contains(aborted);
    verify(fixture.accountReader).read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void explicitAbortRequiresAnExactHeldAccountReadAndDoesNotWriteOnAbsence() {
    var fixture = new Fixture();
    var authorization = authorization();
    fixture.repositoryFindsStored();
    when(fixture.accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenThrow(Status.FAILED_PRECONDITION.asRuntimeException());

    assertThatThrownBy(() -> asGameDesign(() -> fixture.service.abort(authorization)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(fixture.stored.get()).isNull();
    verify(fixture.repository, never())
        .insertTerminal(any(GameLogicGameplayRuleIntakeTerminal.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void explicitAbortRejectsAChangedAccountAuthorizationReadback() {
    var fixture = new Fixture();
    var authorization = authorization();
    var changed =
        new GameLogicIntakeAuthorizationBinding(
            authorization.operationId(),
            UUID.randomUUID(),
            authorization.intakeRequestId(),
            authorization.actorAccountId(),
            authorization.source(),
            authorization.sources());
    when(fixture.accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                new GameLogicIntakeAuthorizationReadEvidence(
                    new GameLogicIntakeAuthorizationReadEvidence.Request(
                        1,
                        NAMESPACE,
                        invocation
                            .<GameLogicIntakeAuthorizationReadEvidence.Request>getArgument(0)
                            .readRequestId(),
                        changed)));

    assertThatThrownBy(() -> asGameDesign(() -> fixture.service.abort(authorization)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(fixture.stored.get()).isNull();
    verify(fixture.repository, never())
        .insertTerminal(any(GameLogicGameplayRuleIntakeTerminal.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void authenticatedSourceMismatchDeniesWithoutWritingAnAbortOrTerminal() {
    var fixture = new Fixture();
    var authorization = authorization();
    var changed = source(authorization.source().binding(), "2");
    fixture.respondFor(authorization, changed);

    assertThatThrownBy(() -> asGameDesign(() -> fixture.service.retain(authorization)))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(fixture.stored.get()).isNull();
    assertThat(asGameDesign(() -> fixture.service.readTerminal(authorization))).isEmpty();
    verify(fixture.repository, never())
        .insertTerminal(any(GameLogicGameplayRuleIntakeTerminal.class));
  }

  @Test
  void unavailableAccountReadLeavesNoTerminalAndDoesNotInferAbort() {
    var fixture = new Fixture();
    var authorization = authorization();
    when(fixture.accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());

    assertThatThrownBy(() -> asGameDesign(() -> fixture.service.retain(authorization)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
    assertThat(fixture.stored.get()).isNull();
    assertThat(asGameDesign(() -> fixture.service.readTerminal(authorization))).isEmpty();
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
    verify(fixture.repository, never())
        .insertTerminal(any(GameLogicGameplayRuleIntakeTerminal.class));
  }

  @Test
  void changedOrderUnderExistingOperationIdentityIsDeniedBeforeRemoteReads() {
    var fixture = new Fixture();
    var originalAuthorization = authorization();
    var operation = new GameLogicGameplayRuleIntakeOperation(NAMESPACE, originalAuthorization);
    var original = GameLogicGameplayRuleIntakeTerminal.aborted(operation);
    fixture.stored.set(original);
    fixture.repositoryFindsStored();
    var changedAuthorization =
        new GameLogicIntakeAuthorizationBinding(
            originalAuthorization.operationId(),
            UUID.randomUUID(),
            originalAuthorization.intakeRequestId(),
            originalAuthorization.actorAccountId(),
            originalAuthorization.source(),
            originalAuthorization.sources());

    assertThatThrownBy(() -> asGameDesign(() -> fixture.service.retain(changedAuthorization)))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.ALREADY_EXISTS));
    verify(fixture.accountReader, never())
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void onlyTheExactSameNamespaceGameDesignPeerCanInvokeTheOwner() {
    var fixture = new Fixture();
    var authorization = authorization();
    var accountPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/account-service", NAMESPACE, "account-service");
    Context previous =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, accountPeer).attach();
    try {
      assertThatThrownBy(() -> fixture.service.retain(authorization))
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    } finally {
      Context.current().detach(previous);
    }
    verify(fixture.repository, never()).findTerminal(any(UUID.class));
    verify(fixture.accountReader, never())
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  @Test
  void creatorPublicationCredentialCannotBeMixedWithTheServicePeer() {
    var fixture = new Fixture();
    var authorization = authorization();
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-design-service", NAMESPACE, "game-design-service");
    Context request =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .withValue(
                AccountSelectedPublicationOrderCredentials.CONTEXT_KEY,
                AccountSelectedPublicationOrderCredentials.Credential.of(
                    "creator-order-credential"));
    Context previous = request.attach();
    try {
      assertThatThrownBy(() -> fixture.service.retain(authorization))
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    } finally {
      Context.current().detach(previous);
    }
    verify(fixture.repository, never()).findTerminal(any(UUID.class));
    verify(fixture.accountReader, never())
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(fixture.gameDesignReader, never())
        .read(any(GameplayRuleSourceReadEvidence.Request.class));
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-design-service", NAMESPACE, "game-design-service");
    Context previous = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).attach();
    try {
      return action.get();
    } finally {
      Context.current().detach(previous);
    }
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    var actor = UUID.randomUUID();
    var source = source(binding(), "1");
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        source,
        List.of(accountSource));
  }

  private static DraftCommitBinding binding() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding binding, String epoch) {
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema", "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson", binding.canonicalJson(),
                "bindingDigest", binding.digest(),
                "sourceEpoch", epoch,
                "inheritedCommitId", "",
                "genesisReceiptId", UUID.randomUUID().toString(),
                "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                "entries", List.of())));
  }

  private static final class Fixture {
    private final GameLogicGameplayRuleIntakeRepository repository =
        mock(GameLogicGameplayRuleIntakeRepository.class);
    private final GameLogicIntakeAuthorizationReadClient accountReader =
        mock(GameLogicIntakeAuthorizationReadClient.class);
    private final GameplayRuleSourceReadClient gameDesignReader =
        mock(GameplayRuleSourceReadClient.class);
    private final AtomicReference<GameLogicGameplayRuleIntakeTerminal> stored =
        new AtomicReference<>();
    private final GameLogicGameplayRuleIntakeService service =
        new GameLogicGameplayRuleIntakeService(
            repository, new UnitTransactionManager(), accountReader, gameDesignReader, NAMESPACE);

    private Fixture() {
      when(repository.findTerminal(any(UUID.class)))
          .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
      org.mockito.Mockito.doAnswer(
              invocation -> {
                stored.set(invocation.getArgument(0));
                return null;
              })
          .when(repository)
          .insertTerminal(any(GameLogicGameplayRuleIntakeTerminal.class));
    }

    private void repositoryFindsStored() {
      when(repository.findTerminal(any(UUID.class)))
          .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    }

    private void respondFor(
        GameLogicIntakeAuthorizationBinding authorization, GameplayRuleSelectedSource selected) {
      respondAuthorizationOnly(authorization);
      when(gameDesignReader.read(any(GameplayRuleSourceReadEvidence.Request.class)))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var request = invocation.<GameplayRuleSourceReadEvidence.Request>getArgument(0);
                return new GameplayRuleSourceReadEvidence(request, selected);
              });
    }

    private void respondAuthorizationOnly(GameLogicIntakeAuthorizationBinding authorization) {
      when(accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var request =
                    invocation.<GameLogicIntakeAuthorizationReadEvidence.Request>getArgument(0);
                assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
                assertThat(request.binding()).isEqualTo(authorization);
                return new GameLogicIntakeAuthorizationReadEvidence(request);
              });
    }
  }

  private static final class UnitTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      var actualDefinition = definition == null ? TransactionDefinition.withDefaults() : definition;
      assertThat(actualDefinition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
