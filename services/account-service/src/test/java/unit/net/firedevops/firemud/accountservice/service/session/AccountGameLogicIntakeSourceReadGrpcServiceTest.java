package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadProtoCodec;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class AccountGameLogicIntakeSourceReadGrpcServiceTest {
  @Test
  void authenticatesExactGdBeforeDecoding() {
    var owner = mock(AccountGameLogicIntakeSourceReadService.class);
    var service = new AccountGameLogicIntakeSourceReadGrpcService(owner, "test");
    var malformed = GameLogicIntakeSourcePermissionRequest.getDefaultInstance();
    var missing = new Collector();
    service.readSourceScope(malformed, missing);
    assertThat(missing.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String caller :
        List.of("account-service", "game-logic-service", "world-management-service")) {
      var result = new Collector();
      peer("test", caller).run(() -> service.readSourceScope(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    var otherNamespace = new Collector();
    peer("other", "game-design-service")
        .run(() -> service.readSourceScope(malformed, otherNamespace));
    assertThat(otherNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    var gd = new Collector();
    peer("test", "game-design-service").run(() -> service.readSourceScope(malformed, gd));
    assertThat(gd.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void dispatchesOnlyMatchingProofMethodAndEchoesExactConfirmedRequest() {
    var owner = mock(AccountGameLogicIntakeSourceReadService.class);
    var service = new AccountGameLogicIntakeSourceReadGrpcService(owner, "test");
    var requests = requests();
    var prelim = GameLogicIntakeSourceReadProtoCodec.toRequest(requests.getFirst());
    var finalized = GameLogicIntakeSourceReadProtoCodec.toRequest(requests.getLast());
    var wrongFirst = new Collector();
    var wrongLast = new Collector();
    peer("test", "game-design-service")
        .run(
            () -> {
              service.readSourceScope(finalized, wrongFirst);
              service.readFinalizedIntake(prelim, wrongLast);
            });
    assertThat(wrongFirst.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(wrongLast.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
    when(owner.readSourceScope(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(owner.readFinalizedIntake(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var first = new Collector();
    var last = new Collector();
    peer("test", "game-design-service")
        .run(
            () -> {
              service.readSourceScope(prelim, first);
              service.readFinalizedIntake(finalized, last);
            });
    assertThat(first.response.getRequest()).isEqualTo(prelim);
    assertThat(last.response.getRequest()).isEqualTo(finalized);
    assertThat(first.response.getPermitted()).isTrue();
    assertThat(last.response.getPermitted()).isTrue();
    when(owner.readSourceScope(any(), any(), any()))
        .thenThrow(Status.PERMISSION_DENIED.asRuntimeException());
    var denied = new Collector();
    peer("test", "game-design-service").run(() -> service.readSourceScope(prelim, denied));
    assertThat(denied.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.response).isNull();
  }

  private static Context peer(String namespace, String service) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
                .orElseThrow());
  }

  private static List<GameLogicIntakeSourceReadEvidence.Request> requests() {
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
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var actor = UUID.randomUUID();
    var scope =
        new GameLogicIntakeSourceReadScope(
            "test",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            selected,
            "spiffe://firemud/ns/test/sa/account-service",
            GameLogicIntakeSourceReadScope.PURPOSE);
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
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
    var authorization =
        new GameLogicIntakeAuthorizationBinding(
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
    return List.of(
        GameLogicIntakeSourceReadEvidence.Request.create(
            "test",
            scope.intendedReader(),
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Preliminary(scope)),
        GameLogicIntakeSourceReadEvidence.Request.create(
            "test",
            "spiffe://firemud/ns/test/sa/game-logic-service",
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Finalized(authorization)));
  }

  private static final class Collector
      implements StreamObserver<GameLogicIntakeSourcePermissionResponse> {
    Status.Code error;
    GameLogicIntakeSourcePermissionResponse response;

    public void onNext(GameLogicIntakeSourcePermissionResponse value) {
      response = value;
    }

    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    public void onCompleted() {}
  }
}
