package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainProtoCodec;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeRequest;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class GameLogicGameplayRuleIntakeGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";

  @Test
  void exactRetryReturnsAndEchoesTheCompleteOriginalTerminal() {
    var fixture = new Fixture();
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, fixture.authorization);
    var observer = new RecordingObserver<RetainGameplayRuleIntakeResponse>();

    asGameDesign(
        () ->
            fixture.transport.retainGameplayRuleIntake(
                GameLogicIntakeRetainProtoCodec.toRequest(request), observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    var result = GameLogicIntakeRetainProtoCodec.fromResponse(request, observer.value);
    assertThat(result.terminal().canonicalBytes())
        .containsExactly(fixture.terminal.canonicalBytes());
    assertThat(result.terminal().outcome())
        .isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED);
    verify(fixture.repository).findTerminal(fixture.authorization.operationId());
    verify(fixture.accountReader, never()).read(any());
    verify(fixture.gameDesignReader, never()).read(any());
  }

  @Test
  void rejectsUnauthenticatedAndWrongPeersBeforeDecodingOrOwnerAccess() {
    var fixture = new Fixture();
    var malformed = RetainGameplayRuleIntakeRequest.getDefaultInstance();

    var unauthenticated = new RecordingObserver<RetainGameplayRuleIntakeResponse>();
    fixture.transport.retainGameplayRuleIntake(malformed, unauthenticated);
    assertCode(unauthenticated.error, Status.Code.UNAUTHENTICATED);

    var account = new RecordingObserver<RetainGameplayRuleIntakeResponse>();
    withPeer(
        "spiffe://firemud/ns/test/sa/account-service",
        () -> fixture.transport.retainGameplayRuleIntake(malformed, account));
    assertCode(account.error, Status.Code.PERMISSION_DENIED);

    verify(fixture.repository, never()).findTerminal(any(UUID.class));
    verify(fixture.accountReader, never()).read(any());
    verify(fixture.gameDesignReader, never()).read(any());
  }

  @Test
  void rejectsChangedNamespaceAndChangedAuthorizationForExistingOperation() {
    var fixture = new Fixture();

    var wrongNamespace = new RecordingObserver<RetainGameplayRuleIntakeResponse>();
    var requestForOtherNamespace =
        GameLogicIntakeRetainEvidence.Request.create("other", fixture.authorization);
    asGameDesign(
        () ->
            fixture.transport.retainGameplayRuleIntake(
                GameLogicIntakeRetainProtoCodec.toRequest(requestForOtherNamespace),
                wrongNamespace));
    assertCode(wrongNamespace.error, Status.Code.PERMISSION_DENIED);

    var changed =
        new GameLogicIntakeAuthorizationBinding(
            fixture.authorization.operationId(),
            UUID.randomUUID(),
            fixture.authorization.intakeRequestId(),
            fixture.authorization.actorAccountId(),
            fixture.authorization.source(),
            fixture.authorization.sources());
    var changedRequest = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, changed);
    var conflict = new RecordingObserver<RetainGameplayRuleIntakeResponse>();
    asGameDesign(
        () ->
            fixture.transport.retainGameplayRuleIntake(
                GameLogicIntakeRetainProtoCodec.toRequest(changedRequest), conflict));
    assertCode(conflict.error, Status.Code.ALREADY_EXISTS);
    verify(fixture.repository).findTerminal(fixture.authorization.operationId());
    verify(fixture.accountReader, never()).read(any());
    verify(fixture.gameDesignReader, never()).read(any());
  }

  @Test
  void authenticatedMalformedRequestIsRejectedBeforeAnyOwnerRead() {
    var fixture = new Fixture();
    var request =
        RetainGameplayRuleIntakeRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setCorrelationId(UUID.randomUUID().toString())
            .setOriginalIntakeAuthorization(
                com.google.protobuf.ByteString.copyFromUtf8("not-an-order"))
            .setIntakeAuthorizationDigest("sha256:" + "0".repeat(64))
            .build();
    var observer = new RecordingObserver<RetainGameplayRuleIntakeResponse>();

    asGameDesign(() -> fixture.transport.retainGameplayRuleIntake(request, observer));

    assertCode(observer.error, Status.Code.INVALID_ARGUMENT);
    verify(fixture.repository, never()).findTerminal(any(UUID.class));
    verify(fixture.accountReader, never()).read(any());
    verify(fixture.gameDesignReader, never()).read(any());
  }

  private static void assertCode(Status.Code error, Status.Code expected) {
    assertThat(error).isEqualTo(expected);
  }

  private static void asGameDesign(Runnable action) {
    withPeer(GAME_DESIGN_URI, action);
  }

  private static void withPeer(String uri, Runnable action) {
    var peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context previous = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).attach();
    try {
      action.run();
    } finally {
      Context.current().detach(previous);
    }
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(failure).isInstanceOf(StatusRuntimeException.class);
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class Fixture {
    private final GameLogicGameplayRuleIntakeRepository repository =
        mock(GameLogicGameplayRuleIntakeRepository.class);
    private final GameLogicIntakeAuthorizationReadClient accountReader =
        mock(GameLogicIntakeAuthorizationReadClient.class);
    private final GameplayRuleSourceReadClient gameDesignReader =
        mock(GameplayRuleSourceReadClient.class);
    private final GameLogicIntakeAuthorizationBinding authorization = authorization();
    private final GameLogicGameplayRuleIntakeTerminal terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization),
            authorization.source().canonicalBytes(),
            authorization.source().manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    private final GameLogicGameplayRuleIntakeService owner =
        new GameLogicGameplayRuleIntakeService(
            repository,
            mock(PlatformTransactionManager.class),
            accountReader,
            gameDesignReader,
            NAMESPACE);
    private final GameLogicGameplayRuleIntakeGrpcService transport =
        new GameLogicGameplayRuleIntakeGrpcService(owner, NAMESPACE);

    private Fixture() {
      when(repository.findTerminal(any(UUID.class))).thenReturn(Optional.of(terminal));
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
}
