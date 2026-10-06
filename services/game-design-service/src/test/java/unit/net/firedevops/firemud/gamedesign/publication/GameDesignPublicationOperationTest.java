package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import org.junit.jupiter.api.Test;

class GameDesignPublicationOperationTest {
  @Test
  void exactCodecPreservesDistinctAccountOperationCompleteSelectionAndOriginalWorldBytes()
      throws Exception {
    var operation = fixture();
    var restored = GameDesignPublicationOperation.fromStored(operation.canonicalBytes());
    assertThat(restored.canonicalBytes()).isEqualTo(operation.canonicalBytes());
    assertThat(restored.account().canonicalBytes()).isEqualTo(operation.account().canonicalBytes());
    assertThat(restored.world().canonicalBytes()).isEqualTo(operation.world().canonicalBytes());
    assertThat(restored.selectionDigest())
        .isEqualTo("sha256:" + restored.world().request().requestDigest());
    GameDesignPublicationOperationRepository.exact(operation, restored);
  }

  @Test
  void changedAccountAllocationAndWorldFenceConflictInsteadOfReplacingOriginalOperation()
      throws Exception {
    var operation = fixture();
    var account = operation.account();
    var changedAccount =
        new GameDesignPublicationOperation(
            new AccountPublicationAuthorizationBinding(
                UUID.randomUUID(), account.fenceId(), account.input(), account.sources()),
            operation.world());
    assertThatThrownBy(
            () -> GameDesignPublicationOperationRepository.exact(operation, changedAccount))
        .isInstanceOf(IllegalArgumentException.class);
    var original = operation.world();
    var request = original.request();
    var changedWorld =
        new GameDesignPublicationOperation(
            account,
            new WorldPublishedStartLocationEvidence(
                new WorldPublishedStartLocationEvidence.Request(
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.canonicalVersionId(),
                    request.intakeRequestId(),
                    UUID.randomUUID(),
                    request.publicationRequestId(),
                    request.requestDigest(),
                    request.versionStateEpoch(),
                    request.publishWorkflowId(),
                    request.appliedCommitId(),
                    request.contentDigest(),
                    request.digestSchemaVersion(),
                    request.worldAffectedTuples()),
                original.selectorReceiptBytes(),
                original.originalAccountBindingBytes(),
                original.appliedResultBytes()));
    assertThatThrownBy(
            () -> GameDesignPublicationOperationRepository.exact(operation, changedWorld))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedWorkflowOrSelectionDigestCannotConstructOwnerOperation() throws Exception {
    var operation = fixture();
    var original = operation.world();
    var request = original.request();
    assertThatThrownBy(
            () ->
                new GameDesignPublicationOperation(
                    operation.account(),
                    new WorldPublishedStartLocationEvidence(
                        new WorldPublishedStartLocationEvidence.Request(
                            request.targetNamespace(),
                            request.canonicalTenantId(),
                            request.canonicalVersionId(),
                            request.intakeRequestId(),
                            request.publicationFence(),
                            request.publicationRequestId(),
                            "f".repeat(64),
                            request.versionStateEpoch(),
                            request.publishWorkflowId(),
                            request.appliedCommitId(),
                            request.contentDigest(),
                            request.digestSchemaVersion(),
                            request.worldAffectedTuples()),
                        original.selectorReceiptBytes(),
                        original.originalAccountBindingBytes(),
                        original.appliedResultBytes())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationOperation(
                    operation.account(),
                    new WorldPublishedStartLocationEvidence(
                        new WorldPublishedStartLocationEvidence.Request(
                            request.targetNamespace(),
                            request.canonicalTenantId(),
                            request.canonicalVersionId(),
                            request.intakeRequestId(),
                            request.publicationFence(),
                            request.publicationRequestId(),
                            request.requestDigest(),
                            request.versionStateEpoch(),
                            "changed-workflow",
                            request.appliedCommitId(),
                            request.contentDigest(),
                            request.digestSchemaVersion(),
                            request.worldAffectedTuples()),
                        original.selectorReceiptBytes(),
                        original.originalAccountBindingBytes(),
                        original.appliedResultBytes())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private GameDesignPublicationOperation fixture() throws Exception {
    return IsolatedPublicationOperationFixtures.fresh(
        new TargetProof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            7L,
            "ISOLATED-tenant",
            3L,
            "ISOLATED-tenant",
            "NEW_GAME_ROW"));
  }
}
