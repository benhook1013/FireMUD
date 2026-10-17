package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class GameDesignGameplayRuleSourceReadServiceTest {
  @ParameterizedTest
  @MethodSource("permissionFailures")
  void sanitizesForeignPermissionStatuses(Status.Code foreignCode, Status.Code expectedCode) {
    var repository = mock(GameplayRuleSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var permissions =
        mock(net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadClient.class);
    org.mockito.Mockito.doThrow(foreignFailure(foreignCode)).when(permissions).read(any());
    var owner =
        new GameDesignGameplayRuleSourceReadService(repository, transactions, permissions, "test");

    assertThatThrownBy(() -> asAccount(() -> owner.read(accountScopeRequest())))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error -> {
              var status = Status.fromThrowable(error);
              assertThat(status.getCode()).isEqualTo(expectedCode);
              assertThat(status.getDescription()).isNull();
              assertThat(error.getCause()).isNull();
              assertThat(((StatusRuntimeException) error).getTrailers()).isNull();
            });
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
  }

  @Test
  void exportsOnlyTheCompleteOwnerReadForTheExactSynchronizedBinding() {
    var repository = mock(GameplayRuleSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    var permissions =
        mock(net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadClient.class);
    org.mockito.Mockito.doAnswer(
            invocation ->
                new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence(
                    invocation.getArgument(0)))
        .when(permissions)
        .read(any());
    var owner =
        new GameDesignGameplayRuleSourceReadService(repository, transactions, permissions, "test");
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var selected =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.upsertPayload(
                        new GameplayRuleManifest.AdmissionTag("gameplay")))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    var snapshot =
        new GameplayRuleSnapshot(
            selected, "1", null, UUID.randomUUID(), GameplayRuleSource.replay(List.of(), selected));
    when(repository.requireCompleteForGameLogic(target, selected.commitId())).thenReturn(snapshot);
    var request =
        GameplayRuleSourceReadEvidence.Request.forAccountSourceScope(
            "test",
            new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope(
                "test",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                selected,
                "spiffe://firemud/ns/test/sa/account-service",
                "GAME_LOGIC_INTAKE_SOURCE"));
    var result = new AtomicReference<GameplayRuleSourceReadEvidence>();
    asAccount(() -> result.set(owner.read(request)));
    assertThat(result.get().source().canonicalBytes()).containsExactly(snapshot.canonicalBytes());
    assertThat(result.get().source().manifest()).isEqualTo(snapshot.manifest());
    verify(repository).requireCompleteForGameLogic(target, selected.commitId());
    var ordered = org.mockito.Mockito.inOrder(permissions, transactions, repository);
    ordered.verify(permissions).read(any());
    ordered.verify(transactions).getTransaction(any());
    ordered.verify(repository).requireCompleteForGameLogic(target, selected.commitId());

    org.mockito.Mockito.clearInvocations(permissions, transactions, repository);
    org.mockito.Mockito.doThrow(io.grpc.Status.PERMISSION_DENIED.asRuntimeException())
        .when(permissions)
        .read(any());
    assertThatThrownBy(() -> asAccount(() -> owner.read(request)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
    org.mockito.Mockito.doAnswer(
            invocation ->
                new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence(
                    invocation.getArgument(0)))
        .when(permissions)
        .read(any());

    var actor = UUID.randomUUID();
    var finalAuthorization =
        new net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            new net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource(
                snapshot.canonicalJson()),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var finalizedRequest =
        GameplayRuleSourceReadEvidence.Request.forFinalizedIntake("test", finalAuthorization);
    asGameLogic(
        () ->
            assertThat(owner.read(finalizedRequest).source().canonicalBytes())
                .containsExactly(snapshot.canonicalBytes()));
    org.mockito.Mockito.clearInvocations(permissions, transactions, repository);
    assertThatThrownBy(() -> asAccount(() -> owner.read(finalizedRequest)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThatThrownBy(() -> asGameLogic(() -> owner.read(request)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(permissions, transactions, repository);
    org.mockito.Mockito.doReturn(null).when(permissions).read(any());
    assertThatThrownBy(() -> asAccount(() -> owner.read(request)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
    org.mockito.Mockito.doThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        .when(permissions)
        .read(any());
    assertThatThrownBy(() -> asAccount(() -> owner.read(request)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var original =
                  invocation
                      .<net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence
                              .Request>
                          getArgument(0);
              return new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence(
                  net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence.Request
                      .create(
                          original.targetNamespace(),
                          original.intendedReader(),
                          original.purpose(),
                          original.proof()));
            })
        .when(permissions)
        .read(any());
    assertThatThrownBy(() -> asAccount(() -> owner.read(request)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
    org.mockito.Mockito.doAnswer(
            invocation ->
                new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence(
                    invocation.getArgument(0)))
        .when(permissions)
        .read(any());

    when(repository.requireCompleteForGameLogic(target, selected.commitId()))
        .thenThrow(new IllegalStateException("GAMEPLAY_RULE_LEGACY_COMMAND_SOURCE_NOT_CONVERGED"));
    assertThatThrownBy(() -> asAccount(() -> owner.read(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_CONVERGED");
  }

  private static void asAccount(Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/account-service").orElseThrow())
        .run(action);
  }

  private static GameplayRuleSourceReadEvidence.Request accountScopeRequest() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var selected =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.upsertPayload(
                        new GameplayRuleManifest.AdmissionTag("gameplay")))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    return GameplayRuleSourceReadEvidence.Request.forAccountSourceScope(
        "test",
        new net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope(
            "test",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            selected,
            "spiffe://firemud/ns/test/sa/account-service",
            "GAME_LOGIC_INTAKE_SOURCE"));
  }

  private static Stream<Arguments> permissionFailures() {
    return Stream.of(
        Arguments.of(Status.Code.UNAUTHENTICATED, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.PERMISSION_DENIED, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.FAILED_PRECONDITION, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.ABORTED, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.NOT_FOUND, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.ALREADY_EXISTS, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.INVALID_ARGUMENT, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.OUT_OF_RANGE, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.UNAVAILABLE, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.DEADLINE_EXCEEDED, Status.Code.DEADLINE_EXCEEDED),
        Arguments.of(Status.Code.CANCELLED, Status.Code.CANCELLED),
        Arguments.of(Status.Code.RESOURCE_EXHAUSTED, Status.Code.RESOURCE_EXHAUSTED),
        Arguments.of(Status.Code.INTERNAL, Status.Code.INTERNAL),
        Arguments.of(Status.Code.DATA_LOSS, Status.Code.UNAVAILABLE),
        Arguments.of(Status.Code.UNKNOWN, Status.Code.UNKNOWN));
  }

  private static StatusRuntimeException foreignFailure(Status.Code code) {
    var trailers = new Metadata();
    trailers.put(Metadata.Key.of("foreign-detail", Metadata.ASCII_STRING_MARSHALLER), "secret");
    return Status.fromCode(code)
        .withDescription("foreign description")
        .withCause(new IllegalStateException("foreign cause"))
        .asRuntimeException(trailers);
  }

  private static void asGameLogic(Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-logic-service")
                .orElseThrow())
        .run(action);
  }
}
