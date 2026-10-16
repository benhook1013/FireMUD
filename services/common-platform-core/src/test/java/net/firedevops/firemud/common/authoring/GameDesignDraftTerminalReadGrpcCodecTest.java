package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import org.junit.jupiter.api.Test;

class GameDesignDraftTerminalReadGrpcCodecTest {
  @Test
  void terminalReadbackAllowsMoreThanBindingLimitAndEnforcesProducerAndConsumerBounds()
      throws Exception {
    var worldRequest = AuthoringFixtures.freshGraphRequest();
    var request =
        new GameDesignDraftTerminalReadEvidence.Request(
            1,
            "test",
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            worldRequest.originalAccountBinding());
    var binding = request.accountBinding();
    byte[] result = new byte[DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES + 1];
    var largeReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.DEFINITIVELY_ABORTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            request.originalAccountBinding(),
            result);

    var response =
        GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(largeReadback));
    assertThat(response.getOwnerReadbackBytes().size())
        .isGreaterThan(DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES);
    assertThat(
            GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, response)
                .ownerReadback()
                .orElseThrow()
                .result())
        .containsExactly(result);

    var oversizedReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.DEFINITIVELY_ABORTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            request.originalAccountBinding(),
            new byte[DraftTerminalReadGrpcLimits.MAX_OWNER_READBACK_BYTES]);
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.toResponse(
                    request, Optional.of(oversizedReadback)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner readback");

    var oversizedWire =
        response.toBuilder()
            .setOwnerReadbackBytes(
                com.google.protobuf.ByteString.copyFrom(
                    new byte[DraftTerminalReadGrpcLimits.MAX_OWNER_READBACK_BYTES + 1]))
            .build();
    assertThatThrownBy(
            () -> GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, oversizedWire))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner readback");
  }
}
