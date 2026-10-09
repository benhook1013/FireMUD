package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class GameDesignGameplayRuleSourceReadServiceTest {
  @Test
  void exportsOnlyTheCompleteOwnerReadForTheExactSynchronizedBinding() {
    var repository = mock(GameplayRuleSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    var owner = new GameDesignGameplayRuleSourceReadService(repository, transactions, "test");
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
    var request = GameplayRuleSourceReadEvidence.Request.create("test", selected);
    var result = new AtomicReference<GameplayRuleSourceReadEvidence>();
    asAccount(() -> result.set(owner.read(request)));
    assertThat(result.get().source().canonicalBytes()).containsExactly(snapshot.canonicalBytes());
    assertThat(result.get().source().manifest()).isEqualTo(snapshot.manifest());
    verify(repository).requireCompleteForGameLogic(target, selected.commitId());

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
}
