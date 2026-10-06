package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.Arrays;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalCompletionGrpcCodecTest {
  @Test
  void preservesCompleteOperationAndTerminalAcrossClosedRequestAndResponse() throws Exception {
    var request = request();
    var wireRequest = WorldPublicationTerminalCompletionGrpcCodec.toRequest(request);
    var decodedRequest = WorldPublicationTerminalCompletionGrpcCodec.fromRequest(wireRequest);
    assertThat(decodedRequest.canonicalBytes()).containsExactly(request.canonicalBytes());
    assertThat(decodedRequest.operationBytes()).containsExactly(request.operationBytes());
    assertThat(decodedRequest.terminalEvidenceBytes())
        .containsExactly(request.terminalEvidenceBytes());

    var wireResponse =
        WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, request.terminalEvidence());
    var decodedResponse =
        WorldPublicationTerminalCompletionGrpcCodec.fromResponse(request, wireResponse);
    assertThat(decodedResponse.canonicalBytes())
        .containsExactly(
            new WorldPublicationTerminalCompletionGrpcCodec.Response(
                    request, request.terminalEvidence())
                .canonicalBytes());
    assertThat(decodedResponse.request().canonicalBytes())
        .containsExactly(request.canonicalBytes());
    assertThat(decodedResponse.terminalEvidence().canonicalBytes())
        .containsExactly(request.terminalEvidenceBytes());

    byte[] escaped = request.terminalEvidenceBytes();
    escaped[0] ^= 0x7f;
    assertThat(request.terminalEvidenceBytes()).isNotEqualTo(escaped);
  }

  @Test
  void rejectsOperationTerminalNamespaceSchemaAndWireSubstitution() throws Exception {
    var request = request();
    var readRequest = WorldPublicationTerminalReadGrpcCodecTest.request(false);
    var changedOperationTerminal =
        WorldPublicationTerminalReadGrpcCodecTest.changedTerminal(readRequest);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionGrpcCodec.Request(
                    1,
                    request.targetNamespace(),
                    request.operationBytes(),
                    changedOperationTerminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionGrpcCodec.Request(
                    2,
                    request.targetNamespace(),
                    request.operationBytes(),
                    request.terminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionGrpcCodec.Request(
                    1, "other", request.operationBytes(), request.terminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalCompletionGrpcCodec.fromRequest(
                    WorldPublicationTerminalCompletionGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalCompletionGrpcCodec.Request.fromStored(
                    Arrays.copyOf(request.canonicalBytes(), request.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldPublicationTerminalCompletionGrpcCodec.Request request() throws Exception {
    var readRequest = WorldPublicationTerminalReadGrpcCodecTest.request(false);
    GameDesignPublicationTerminalEvidence terminal = readRequest.terminalEvidence();
    return new WorldPublicationTerminalCompletionGrpcCodec.Request(
        1, readRequest.targetNamespace(), terminal.operationBytes(), terminal.canonicalBytes());
  }

  static WorldPublicationTerminalCompletionGrpcCodec.Request changedRequest(
      WorldPublicationTerminalCompletionGrpcCodec.Request original) throws Exception {
    var readRequest = WorldPublicationTerminalReadGrpcCodecTest.request(false);
    var changed = WorldPublicationTerminalReadGrpcCodecTest.changedTerminal(readRequest);
    return new WorldPublicationTerminalCompletionGrpcCodec.Request(
        1, original.targetNamespace(), changed.operationBytes(), changed.canonicalBytes());
  }
}
