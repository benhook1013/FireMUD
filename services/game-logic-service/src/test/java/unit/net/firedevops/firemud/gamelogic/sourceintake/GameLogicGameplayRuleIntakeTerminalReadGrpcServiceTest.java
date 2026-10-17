package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamelogic.v1.ReadGameplayRuleIntakeTerminalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class GameLogicGameplayRuleIntakeTerminalReadGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";

  @Test
  void mapsPermanentOwnerFailuresToFailedPreconditionAndStorageFailuresToUnavailable() {
    String secret = "private storage detail and credential";
    var cause = new IllegalStateException("underlying " + secret);
    var failures =
        List.of(
            new IllegalArgumentException(secret, cause),
            new IllegalStateException(secret, cause),
            new DataAccessResourceFailureException(secret, cause),
            new CannotCreateTransactionException(secret, cause));
    var expectedCodes =
        List.of(
            Status.Code.FAILED_PRECONDITION,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.UNAVAILABLE,
            Status.Code.UNAVAILABLE);

    for (int index = 0; index < failures.size(); index++) {
      var repository = mock(GameLogicGameplayRuleIntakeRepository.class);
      when(repository.findTerminal(any(UUID.class))).thenThrow(failures.get(index));
      var transport =
          new GameLogicGameplayRuleIntakeTerminalReadGrpcService(
              new GameLogicGameplayRuleIntakeTerminalReadService(repository, NAMESPACE), NAMESPACE);
      var evidenceRequest =
          GameLogicIntakeTerminalReadEvidence.Request.create(NAMESPACE, authorization());
      var observer = new RecordingObserver<ReadGameplayRuleIntakeTerminalResponse>();

      asAccount(
          () ->
              transport.readGameplayRuleIntakeTerminal(
                  GameLogicIntakeTerminalReadGrpcCodec.toRequest(evidenceRequest), observer));

      assertThat(observer.error).isEqualTo(expectedCodes.get(index));
      assertThat(observer.failureHasCause).isFalse();
      assertThat(observer.failureText).doesNotContain(secret);
      assertThat(observer.failureDescription).isNull();
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
    }
  }

  private static void asAccount(Runnable action) {
    var peer = GrpcPeerIdentity.parseUri(ACCOUNT_URI).orElseThrow();
    Context previous = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).attach();
    try {
      action.run();
    } finally {
      Context.current().detach(previous);
    }
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    UUID actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var selectedBinding =
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
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selectedBinding.canonicalJson(),
                    "bindingDigest",
                    selectedBinding.digest(),
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

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private String failureText;
    private String failureDescription;
    private boolean failureHasCause;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(failure).isInstanceOf(StatusRuntimeException.class);
      error = Status.fromThrowable(failure).getCode();
      failureText = failure.toString();
      failureDescription = Status.fromThrowable(failure).getDescription();
      failureHasCause = failure.getCause() != null;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
