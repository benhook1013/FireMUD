package net.firedevops.firemud.common.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import org.junit.jupiter.api.Test;

class TemporalTaskQueueResolverTest {

  @Test
  void buildsCanonicalTaskQueue() {
    TemporalProperties properties = new TemporalProperties();
    properties.setTaskQueuePrefix("firemud");
    TemporalTaskQueueResolver resolver =
        new TemporalTaskQueueResolver("world-management-service", properties);

    assertEquals(
        "firemud:world-management-service:world-lifecycle",
        resolver.forWorkflowFamily("world-lifecycle"));
  }

  @Test
  void rejectsBlankWorkflowFamily() {
    TemporalProperties properties = new TemporalProperties();
    TemporalTaskQueueResolver resolver =
        new TemporalTaskQueueResolver("world-management-service", properties);

    assertThrows(IllegalArgumentException.class, () -> resolver.forWorkflowFamily(" "));
  }
}
