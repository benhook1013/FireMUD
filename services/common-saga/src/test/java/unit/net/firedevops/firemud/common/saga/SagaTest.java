package net.firedevops.firemud.common.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SagaTest {
  @Test
  void compensationRunsOnFailure() {
    StringBuilder log = new StringBuilder();
    SagaBuilder builder =
        new SagaBuilder()
            .step("ok", () -> log.append("1"))
            .step(
                "fail",
                () -> {
                  throw new RuntimeException("boom");
                },
                () -> log.append("c"));

    assertThrows(SagaException.class, builder::run);
    // compensation should have appended 'c'
    assertEquals("1c", log.toString());
  }
}
