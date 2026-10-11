package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

class AuthoredInitialActorCoreTest {
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  @Test
  void parsesOnlyExplicitDisplayNameAndDispositionKey() throws Exception {
    var core =
        AuthoredInitialActorCore.parse(
            JSON.readTree("{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}"));

    assertThat(core).isEqualTo(new AuthoredInitialActorCore("Wayfarer", "newcomer"));
    assertThat(core.canonicalJson())
        .isEqualTo("{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}");
  }

  @Test
  void rejectsPresenceAndEveryUnknownMissingOrInvalidCoreField() {
    for (String invalid :
        new String[] {
          "{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\",\"presence\":\"OFFLINE\"}",
          "{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\",\"unknown\":true}",
          "{\"displayName\":\"Wayfarer\"}",
          "{\"displayName\":\" Wayfarer\",\"dispositionKey\":\"newcomer\"}",
          "{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"not valid\"}",
          "{\"displayName\":\"Other\",\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}"
        }) {
      assertThatThrownBy(() -> AuthoredInitialActorCore.parse(JSON.readTree(invalid)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThatThrownBy(() -> new AuthoredInitialActorCore("Wayfarer", "not valid"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
