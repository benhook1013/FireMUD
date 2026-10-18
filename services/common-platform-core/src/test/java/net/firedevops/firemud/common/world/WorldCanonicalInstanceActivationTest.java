package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class WorldCanonicalInstanceActivationTest {
  private static final UUID ACTIVATION_ID = UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3");

  @Test
  void fromStoredNormalizesMalformedDuplicateAndTrailingJsonFailures() throws Exception {
    var preparing = AuthoringFixtures.lifecycleEvidence("PREPARING", 3L);
    var request = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, preparing);
    var result =
        new WorldCanonicalInstanceActivation.Result(
            request,
            WorldCanonicalInstanceActivation.Outcome.ABORTED,
            "POSITIVE_FENCED_ABORT",
            preparing);
    String canonical = new String(result.canonicalBytes(), StandardCharsets.UTF_8);
    String schemaField = "\"schema\":\"world-canonical-instance-activation-result/v1\"";
    assertThat(canonical).contains(schemaField);

    List<InvalidStoredCase> cases =
        List.of(
            new InvalidStoredCase("malformed JSON", "{".getBytes(StandardCharsets.UTF_8)),
            new InvalidStoredCase(
                "duplicate JSON field",
                canonical
                    .replace(schemaField, schemaField + "," + schemaField)
                    .getBytes(StandardCharsets.UTF_8)),
            new InvalidStoredCase(
                "trailing JSON value", (canonical + " {} ").getBytes(StandardCharsets.UTF_8)));

    for (InvalidStoredCase testCase : cases) {
      assertThatThrownBy(() -> WorldCanonicalInstanceActivation.Result.fromStored(testCase.bytes()))
          .as(testCase.description())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Stored canonical activation result is invalid")
          .cause()
          .isInstanceOf(JacksonException.class);
    }
  }

  private record InvalidStoredCase(String description, byte[] bytes) {}
}
