package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import org.junit.jupiter.api.Test;

/** Structural retention proof; upstream authority is explicitly stipulated by the fixture. */
class GameDesignPublicationOperationBindingTest {
  @Test
  void fiveFramesRetainExactInventoryAndReconstructOriginalFreeze() throws Exception {
    var original = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var bytes = original.canonicalBytes();
    var frames = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    frames.expect("game-design-publication-operation/v2");
    assertThat(frames.bytes()).containsExactly(original.account().canonicalBytes());
    assertThat(frames.bytes()).containsExactly(original.world().canonicalBytes());
    assertThat(frames.bytes()).containsExactly(original.inventory().canonicalBytes());
    assertThat(frames.text()).isEqualTo(original.inventory().digest());
    frames.requireEnd();
    var retained = GameDesignPublicationOperationBinding.fromStored(bytes);
    assertThat(retained.canonicalBytes()).containsExactly(bytes);
    assertThat(retained.inventory().canonicalBytes())
        .containsExactly(original.inventory().canonicalBytes());
    assertThat(retained.inventory().freezeEvidence().request())
        .isEqualTo(original.inventory().freezeEvidence().request());
    assertThat(retained.inventory().freezeEvidence().acknowledgement())
        .isEqualTo(original.inventory().freezeEvidence().acknowledgement());
    bytes[0] ^= 1;
    assertThat(retained.canonicalBytes()).containsExactly(original.canonicalBytes());
  }

  @Test
  void absentInventoryV1WrongDigestTrailingAndOversizeInputsDeny() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var v1 = prefix(operation, "game-design-publication-operation/v1");
    assertThatThrownBy(() -> GameDesignPublicationOperationBinding.fromStored(v1.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
    var absent = prefix(operation, GameDesignPublicationOperationBinding.SCHEMA);
    assertThatThrownBy(() -> GameDesignPublicationOperationBinding.fromStored(absent.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
    var wrong = prefix(operation, GameDesignPublicationOperationBinding.SCHEMA);
    DraftAuthorizationFenceBinding.frame(wrong, operation.inventory().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(wrong, "sha256:" + "0".repeat(64));
    assertThatThrownBy(() -> GameDesignPublicationOperationBinding.fromStored(wrong.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
    var trailing = new ByteArrayOutputStream();
    trailing.writeBytes(operation.canonicalBytes());
    trailing.write(0);
    assertThatThrownBy(
            () -> GameDesignPublicationOperationBinding.fromStored(trailing.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameDesignPublicationOperationBinding.fromStored(
                    new byte[GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void digestCorrectChangedAppliedOperationAndGraphStillDeny() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var inventory = operation.inventory().publicEvidence();
    for (String changed :
        new String[] {
          new String(inventory.canonicalBytes(), StandardCharsets.UTF_8)
              .replace(
                  inventory.selectedApplication().appliedResultDigest(),
                  "sha256:" + "0".repeat(64)),
          new String(inventory.canonicalBytes(), StandardCharsets.UTF_8)
              .replace(inventory.sourceModel().graphDigest(), "sha256:" + "1".repeat(64)),
          new String(inventory.canonicalBytes(), StandardCharsets.UTF_8)
              .replace(
                  inventory.selectedApplication().applicationOperationId().toString(),
                  java.util.UUID.randomUUID().toString())
        }) {
      var bytes = changed.getBytes(StandardCharsets.UTF_8);
      var digest =
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      assertThatThrownBy(
              () ->
                  WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
                      operation.account(), operation.world(), bytes, digest))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static ByteArrayOutputStream prefix(
      GameDesignPublicationOperationBinding operation, String schema) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, schema);
    DraftAuthorizationFenceBinding.frame(out, operation.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, operation.world().canonicalBytes());
    return out;
  }
}
