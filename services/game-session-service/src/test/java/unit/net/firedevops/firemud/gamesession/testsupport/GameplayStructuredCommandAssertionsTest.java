package net.firedevops.firemud.gamesession.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class GameplayStructuredCommandAssertionsTest {

  @Test
  void matchesStructuredCommandWithoutCommandIdFilter() {
    assertThat(
            GameplayStructuredCommandAssertions.isStructuredCommand(
                """
                {"eventType":"command_result","commandType":"AUTHORED","commandId":"wave-salute"}
                """,
                "AUTHORED"))
        .isTrue();
  }

  @Test
  void matchesStructuredCommandWithCommandIdFilter() {
    assertThat(
            GameplayStructuredCommandAssertions.isStructuredCommand(
                """
                {"eventType":"command_result","commandType":"AUTHORED","commandId":"wave-salute"}
                """,
                "AUTHORED",
                "wave-salute"))
        .isTrue();
  }

  @Test
  void rejectsStructuredCommandWhenCommandIdDoesNotMatch() {
    assertThat(
            GameplayStructuredCommandAssertions.isStructuredCommand(
                """
                {"eventType":"command_result","commandType":"AUTHORED","commandId":"wave-salute"}
                """,
                "AUTHORED",
                "bow"))
        .isFalse();
  }

  @Test
  void parseStructuredResponseReadsCommandIdField() {
    JsonNode json =
        GameplayStructuredCommandAssertions.parseStructuredResponse(
            """
            {"eventType":"command_result","commandType":"LOOK","commandId":"look"}
            """);

    assertThat(json.path("commandType").asText()).isEqualTo("LOOK");
    assertThat(json.path("commandId").asText()).isEqualTo("look");
  }
}
