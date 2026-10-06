package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
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

  @Test
  void ownerReceiptUsesEmptyByteFrameForNoPublicationAndPreservesExactPublishedTextEvidence()
      throws Exception {
    var operation = fixture();
    byte[] noPublication = invokeReceipt(operation, "NO_PUBLICATION", null);
    var absentReader = new DraftAuthorizationFenceBinding.FrameReader(noPublication);
    absentReader.expect("game-design-publication-owner-readback/v1");
    assertThat(absentReader.bytes()).isEqualTo(operation.canonicalBytes());
    assertThat(absentReader.text()).isEqualTo("NO_PUBLICATION");
    assertThat(absentReader.bytes()).isEmpty();
    absentReader.requireEnd();

    String releaseEvidence = "{\"release\":\"published-雪-🧭\"}";
    byte[] published = invokeReceipt(operation, "PUBLISHED", releaseEvidence);
    var publishedReader = new DraftAuthorizationFenceBinding.FrameReader(published);
    publishedReader.expect("game-design-publication-owner-readback/v1");
    assertThat(publishedReader.bytes()).isEqualTo(operation.canonicalBytes());
    assertThat(publishedReader.text()).isEqualTo("PUBLISHED");
    assertThat(publishedReader.bytes()).isEqualTo(releaseEvidence.getBytes(StandardCharsets.UTF_8));
    publishedReader.requireEnd();

    assertThatThrownBy(() -> invokeReceipt(operation, "PUBLISHED", ""))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
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

  private byte[] invokeReceipt(
      GameDesignPublicationOperation operation, String outcome, String evidence) throws Exception {
    Method method =
        GameDesignPublicationOperationRepository.class.getDeclaredMethod(
            "receipt", GameDesignPublicationOperation.class, String.class, String.class);
    method.setAccessible(true);
    return (byte[]) method.invoke(null, operation, outcome, evidence);
  }
}
