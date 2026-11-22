package net.firedevops.firemud.recovery.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackAuthority;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class InitialGameplayReleaseTupleCodecTest {
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
