package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import org.junit.jupiter.api.Test;

class GameDesignPublicationTerminalReadEvidenceTest {
  @Test
  void requestAndTerminalRetainDefensiveExactOriginalBytes() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    byte[] bytes = operation.canonicalBytes();
    var request =
        new GameDesignPublicationTerminalReadEvidence.Request(1, "test", UUID.randomUUID(), bytes);
    bytes[0] = 0;
    request.originalOperation()[0] = 0;
    assertThat(request.originalOperation()).containsExactly(operation.canonicalBytes());
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION);
    byte[] terminalBytes = terminal.canonicalBytes();
    var evidence = new GameDesignPublicationTerminalReadEvidence(request, terminalBytes);
    terminalBytes[0] = 0;
    evidence.terminalEvidence().canonicalBytes()[0] = 0;
    assertThat(evidence.terminalEvidence().canonicalBytes())
        .containsExactly(terminal.canonicalBytes());
  }

  @Test
  void requestRejectsUnsupportedVersionInvalidNamespaceNilIdentityAndAbsentOperation()
      throws Exception {
    byte[] bytes = GameDesignPublicationTerminalReadGrpcCodecTest.operation().canonicalBytes();
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    2, "test", UUID.randomUUID(), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "Test", UUID.randomUUID(), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "test", new UUID(0L, 0L), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
