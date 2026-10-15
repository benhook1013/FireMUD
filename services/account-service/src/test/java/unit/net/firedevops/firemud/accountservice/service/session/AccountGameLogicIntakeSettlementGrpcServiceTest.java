package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeRequest;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementProtoCodec;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceRevision;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Context-level transport proofs; durable settlement belongs to the owner service. */
class AccountGameLogicIntakeSettlementGrpcServiceTest {
  private final AccountGameLogicIntakeSettlementService owner =
      mock(AccountGameLogicIntakeSettlementService.class);
  private final AccountGameLogicIntakeSettlementGrpcService receiver =
      new AccountGameLogicIntakeSettlementGrpcService(owner, "test");

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesBeforeDecodeAndDeniesEveryOtherWorkloadAndNamespace() {
    assertThat(invoke(null).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (var workload :
        List.of(
            "account-service",
            "game-logic-service",
            "world-management-service",
            "game-session-service")) {
      assertThat(peer("test", workload, () -> invoke(null)).code())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(peer("other", "game-design-service", () -> invoke(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(authorized(() -> invoke(SettleGameLogicIntakeRequest.getDefaultInstance())).code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void deniesAmbientSqlAndChangedTargetBeforeOwner() {
    var request =
        AccountGameLogicIntakeSettlementProtoCodec.toRequest(
            evidence("test", 1, Outcome.ABORTED).request());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThat(authorized(() -> invoke(request)).code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThat(authorized(() -> invoke(request)).code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.clearSynchronization();
    assertThat(
            authorized(() -> invoke(request.toBuilder().setTargetNamespace("other").build()))
                .code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void returnsExactRetainedAndAbortedReceiptForNewCorrelationRetryWithoutChangingBytes() {
    for (var outcome : Outcome.values()) {
      var evidence = evidence("test", 1, outcome);
      var receipt = new AccountGameLogicIntakeSettlement(evidence.receipt().terminal());
      when(owner.settle(any())).thenReturn(receipt);
      var first =
          authorized(
              () ->
                  invoke(AccountGameLogicIntakeSettlementProtoCodec.toRequest(evidence.request())));
      assertThat(first.error()).isNull();
      assertThat(
              AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                      evidence.request(), first.response())
                  .receipt()
                  .canonicalBytes())
          .isEqualTo(receipt.canonicalBytes());
      var retry =
          AccountGameLogicIntakeSettlementReadEvidence.Request.create(
              "test", evidence.request().binding());
      var replay =
          authorized(() -> invoke(AccountGameLogicIntakeSettlementProtoCodec.toRequest(retry)));
      assertThat(replay.error()).isNull();
      assertThat(replay.response().getOriginalSettlementReceipt())
          .isEqualTo(first.response().getOriginalSettlementReceipt());
      assertThat(replay.response().getRequest()).isNotEqualTo(first.response().getRequest());
      assertThat(receipt.canonicalBytes()).isEqualTo(evidence.receipt().canonicalBytes());
      assertThat(AccountGameLogicIntakeSettlement.fromStored(receipt.canonicalBytes()).digest())
          .isEqualTo(receipt.digest());
    }
  }

  @Test
  void substitutedOwnerReceiptAndOwnerFailureDenyWithoutLeakingDiagnostics() {
    var evidence = evidence("test", 1, Outcome.RETAINED);
    var request = AccountGameLogicIntakeSettlementProtoCodec.toRequest(evidence.request());
    when(owner.settle(any()))
        .thenReturn(
            new AccountGameLogicIntakeSettlement(
                evidence("test", 1, Outcome.RETAINED).receipt().terminal()));
    assertThat(authorized(() -> invoke(request)).code()).isEqualTo(Status.Code.UNAVAILABLE);
    when(owner.settle(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("private-owner-evidence")
                .asRuntimeException());
    var failed = authorized(() -> invoke(request));
    assertThat(failed.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(failed.error().toString()).doesNotContain("private-owner-evidence");
  }

  private record Result(SettleGameLogicIntakeResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private Result invoke(SettleGameLogicIntakeRequest request) {
    var response = new AtomicReference<SettleGameLogicIntakeResponse>();
    var error = new AtomicReference<Throwable>();
    receiver.settleGameLogicIntake(
        request,
        new io.grpc.stub.StreamObserver<>() {
          @Override
          public void onNext(SettleGameLogicIntakeResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable value) {
            error.set(value);
          }

          @Override
          public void onCompleted() {}
        });
    return new Result(response.get(), error.get());
  }

  private static <T> T authorized(java.util.function.Supplier<T> action) {
    return peer("test", "game-design-service", action);
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private enum Outcome {
    RETAINED,
    ABORTED
  }

  /** These canonical bytes are deliberately synthetic transport evidence, not owner provenance. */
  private static AccountGameLogicIntakeSettlementReadEvidence evidence(
      String namespace, int entryCount, Outcome outcome) {
    var tenant = UUID.randomUUID();
    var version = UUID.randomUUID();
    var requestId = UUID.randomUUID();
    var commitId = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    var definitions = new ArrayList<GameplayRuleManifest.Definition>();
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var revisionIds = new ArrayList<UUID>();
    for (int index = 0; index < entryCount; index++) {
      var definition =
          new GameplayRuleManifest.AdmissionTag(
              String.format(java.util.Locale.ROOT, "tag-%05d", index));
      definitions.add(definition);
      var revisionId = UUID.randomUUID();
      revisionIds.add(revisionId);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              "0",
              revisionId,
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              GameplayRuleSourceRevision.upsertPayload(definition)));
    }
    var selectedBinding =
        DraftCommitBinding.create(
            target,
            requestId,
            commitId,
            "genesis",
            List.of(revisions.getFirst()),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    version.toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var definitionsByFamily =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values())
      definitionsByFamily.put(family, new ArrayList<>());
    definitionsByFamily.get(GameplayRuleManifest.Family.ADMISSION_TAGS).addAll(definitions);
    var manifest = new GameplayRuleManifest(definitionsByFamily);
    var entries = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < entryCount; index++) {
      var definition = definitions.get(index);
      var entryBinding =
          DraftCommitBinding.create(
              target,
              requestId,
              UUID.randomUUID(),
              "genesis",
              List.of(revisions.get(index)),
              List.of(
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                      "GAMEPLAY_RULE_SET",
                      version.toString(),
                      "GAMEPLAY_RULE_SET",
                      "effective",
                      Integer.toString(index))));
      entries.add(
          Map.of(
              "family", definition.family().name(),
              "definitionJson", GameplayRuleManifest.canonical(definition),
              "sourceBindingJson", entryBinding.canonicalJson(),
              "sourceBindingDigest", entryBinding.digest(),
              "revisionOrder", "0",
              "revisionId", revisionIds.get(index).toString()));
    }
    String snapshot =
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
                manifest.canonicalJson(),
                "entries",
                entries));
    var source = new GameplayRuleSelectedSource(snapshot);
    var accountEvidence =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            UUID.randomUUID().toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    var authorization =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.fromString(accountEvidence.scopeId()),
            source,
            List.of(accountEvidence));
    var operation = new GameLogicGameplayRuleIntakeOperation(namespace, authorization);
    var terminal =
        outcome == Outcome.RETAINED
            ? GameLogicGameplayRuleIntakeTerminal.retained(
                operation,
                source.canonicalBytes(),
                manifest.canonicalJson().getBytes(StandardCharsets.UTF_8))
            : GameLogicGameplayRuleIntakeTerminal.aborted(operation);
    return new AccountGameLogicIntakeSettlementReadEvidence(
        AccountGameLogicIntakeSettlementReadEvidence.Request.create(namespace, authorization),
        new AccountGameLogicIntakeSettlementEvidence(terminal));
  }
}
