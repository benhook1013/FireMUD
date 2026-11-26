package net.firedevops.firemud.recovery.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackAuthority;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class InitialGameplayReleaseTupleCodecTest {
  private final InitialGameplayReleaseTupleCodec codec =
      new InitialGameplayReleaseTupleCodec(JsonMapper.builder().build());

  @Test
  void validScalarDigestsRetainTheExistingV2Bytes() {
    assertThat(codec.digest(Map.of("text", "ordinary")))
        .isEqualTo("69c0c5d37301a9ab22883df5654ab8d29cc4fb467f00756c685b025b8c47d3eb");
    String supplementary = "sample " + Character.toString(0x1f680);
    assertThat(codec.digest(Map.of("text", supplementary)))
        .isEqualTo("3cf71dcef56d13e4fa962c234be90e80dfe15af212c016083eb3e639e9e81ece");
  }

  @Test
  void incompleteScalarIsRejectedBeforeHashing() {
    String incompleteScalar = Character.toString(Character.MIN_HIGH_SURROGATE);
    assertThatThrownBy(() -> codec.digest(Map.of("text", incompleteScalar)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void typedRetainedClosureRoundTripsWithoutChangingItsDigest() {
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    var codec = new InitialGameplayReleaseTupleCodec(mapper);
    var closure =
        new EffectReadback(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            EffectKind.PREPARE_PLAYABLE_STATE_FENCED,
            "a".repeat(64),
            "b".repeat(64),
            EffectReadbackState.PROVEN_NO_EFFECT,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CLOSED_ATTEMPT,
            "owner-receipt-123",
            Instant.EPOCH);
    String json = codec.toJson(closure);
    assertThat(mapper.readTree(json).get("readbackIdentity").isTextual()).isTrue();
    var retained = codec.readEffectReadback(json);
    assertThat(retained).isEqualTo(closure);
    assertThat(codec.digest(retained)).isEqualTo(codec.digest(closure));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.25", "true"})
  void persistedReadbackIdentityRequiresAString(String replacement) {
    var readback =
        new EffectReadback(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            EffectKind.PREPARE_PLAYABLE_STATE_FENCED,
            "a".repeat(64),
            "b".repeat(64),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "owner-receipt",
            Instant.EPOCH);
    String altered = codec.toJson(readback).replace("\"owner-receipt\"", replacement);
    assertThatThrownBy(() -> codec.readEffectReadback(altered))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.0", "\"0\"", "true"})
  void persistedReadbackEnumsRequireTextualNames(String replacement) {
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    InitialGameplayReleaseTupleCodec codec = new InitialGameplayReleaseTupleCodec(mapper);
    for (EffectKind kind : EffectKind.values()) {
      for (EffectReadbackState state : EffectReadbackState.values()) {
        var original =
            new EffectReadback(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                kind,
                "a".repeat(64),
                "b".repeat(64),
                state,
                state == EffectReadbackState.PROVEN_NO_EFFECT
                    ? EffectReadbackAuthority.GAME_SESSION_DURABLE_CLOSED_ATTEMPT
                    : EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
                "owner-receipt",
                Instant.EPOCH);
        String json = codec.toJson(original);
        assertThat(codec.readEffectReadback(json)).isEqualTo(original);
        for (String field : List.of("effectKind", "state", "authority")) {
          ObjectNode altered = (ObjectNode) mapper.readTree(json);
          altered.set(field, mapper.readTree(replacement));
          String alteredJson = mapper.writeValueAsString(altered);
          assertThatThrownBy(() -> codec.readEffectReadback(alteredJson))
              .as(field + " must retain its textual enum name")
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }
}
