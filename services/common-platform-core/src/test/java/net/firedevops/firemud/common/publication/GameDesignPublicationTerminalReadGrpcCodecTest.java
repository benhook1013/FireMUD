package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.Status;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import org.junit.jupiter.api.Test;

/** Preserved donor refusal vectors mapped to the canonical framed read, with controlled inputs. */
class GameDesignPublicationTerminalReadGrpcCodecTest {
  @Test
  void retainsExactReadCorrelationAndTerminalBytesForEveryCanonicalStatus() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request = ReadRequest.create("test", operation.canonicalBytes());
    assertThat(
            GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                GameDesignPublicationTerminalReadGrpcCodec.toRequest(request)))
        .isEqualTo(request);
    for (Status status : Status.values()) {
      var terminal =
          status == Status.UNKNOWN
              ? Optional
                  .<net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence>
                      empty()
              : Optional.of(
                  WorldPublicationTerminalTestFixtures.terminal(
                      operation, Outcome.valueOf(status.name())));
      var result = new ReadResult(request, status, terminal);
      var response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result);
      assertThat(
              GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response)
                  .canonicalBytes())
          .containsExactly(result.canonicalBytes());
      assertThat(
              GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response)
                  .canonicalBytes())
          .containsExactly(result.canonicalBytes());
    }
  }

  @Test
  void rejectsUnknownMalformedNoncanonicalAndIncompleteFramedRequests() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request = ReadRequest.create("test", operation.canonicalBytes());
    var wire = GameDesignPublicationTerminalReadGrpcCodec.toRequest(request);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class);
    for (byte[] invalid :
        List.of(
            new byte[0],
            new byte[] {1},
            replaceFrame(request.canonicalBytes(), 1, text("2")),
            replaceFrame(request.canonicalBytes(), 2, text("Test")),
            replaceFrame(request.canonicalBytes(), 2, text("other")),
            replaceFrame(request.canonicalBytes(), 3, text("00000000-0000-0000-0000-000000000000")),
            replaceFrame(request.canonicalBytes(), 3, text("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")),
            replaceFrame(request.canonicalBytes(), 3, text("1-1-1-1-1")),
            replaceFrame(request.canonicalBytes(), 4, new byte[0]),
            replaceFrame(request.canonicalBytes(), 4, new byte[] {1}),
            java.util.Arrays.copyOf(
                request.canonicalBytes(), request.canonicalBytes().length + 1))) {
      assertThatThrownBy(
              () ->
                  GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                      wire.toBuilder()
                          .setCanonicalRequestBytes(ByteString.copyFrom(invalid))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsChangedEchoUnknownMalformedMissingAndSubstitutedTerminalResponses() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request = ReadRequest.create("test", operation.canonicalBytes());
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
    var result = new ReadResult(request, Status.NO_PUBLICATION, Optional.of(terminal));
    var wire = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result);
    var changedRequest = ReadRequest.create("test", operation.canonicalBytes());
    var changedTerminal =
        WorldPublicationTerminalTestFixtures.terminal(
            WorldPublicationTerminalTestFixtures.operation(), Outcome.NO_PUBLICATION);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    request, wire.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class);
    for (byte[] invalid :
        List.of(
            new byte[0],
            new byte[] {1},
            replaceFrame(result.canonicalBytes(), 1, changedRequest.canonicalBytes()),
            replaceFrame(result.canonicalBytes(), 2, text("UNKNOWN")),
            replaceFrame(result.canonicalBytes(), 2, text("PUBLISHED")),
            replaceFrame(result.canonicalBytes(), 3, new byte[0]),
            replaceFrame(result.canonicalBytes(), 3, new byte[] {1}),
            replaceFrame(result.canonicalBytes(), 3, changedTerminal.canonicalBytes()),
            java.util.Arrays.copyOf(result.canonicalBytes(), result.canonicalBytes().length + 1))) {
      assertThatThrownBy(
              () ->
                  GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                      request,
                      wire.toBuilder()
                          .setCanonicalResponseBytes(ByteString.copyFrom(invalid))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var changedResult =
        new ReadResult(changedRequest, Status.NO_PUBLICATION, Optional.of(terminal));
    assertThatThrownBy(
            () -> GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, changedResult))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.UNKNOWN, Optional.of(terminal)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.PUBLISHED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retainsDefensiveOperationBytesAndRejectsReusedOriginalIdentity() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    byte[] input = operation.canonicalBytes();
    var request = ReadRequest.create("test", input);
    java.util.Arrays.fill(input, (byte) 0);
    assertThat(request.operationBytes()).containsExactly(operation.canonicalBytes());
    byte[] returned = request.operationBytes();
    java.util.Arrays.fill(returned, (byte) 0);
    assertThat(request.operationBytes()).containsExactly(operation.canonicalBytes());
    assertThatThrownBy(
            () ->
                new ReadRequest(
                    1, "test", operation.account().operationId(), operation.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] replaceFrame(byte[] framed, int target, byte[] replacement) {
    var input = ByteBuffer.wrap(framed);
    var output = new ByteArrayOutputStream();
    for (int index = 0; input.hasRemaining(); index++) {
      int length = input.getInt();
      byte[] value = new byte[length];
      input.get(value);
      DraftAuthorizationFenceBinding.frame(output, index == target ? replacement : value);
    }
    return output.toByteArray();
  }
}
