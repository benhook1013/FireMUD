package net.firedevops.firemud.common.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FiremudWorkflowIdsTest {

  @Test
  void buildsCanonicalWorkflowId() {
    assertEquals(
        "world-lifecycle:tenant-1:instance:world-create-1",
        FiremudWorkflowIds.workflowId("world-lifecycle", "tenant-1", "instance", "world-create-1"));
  }

  @Test
  void buildsCanonicalBusinessStepKey() {
    assertEquals(
        "workflow-1#validate-release#release-7",
        FiremudWorkflowIds.businessStepKey("workflow-1", "validate-release", "release-7"));
  }

  @Test
  void rejectsBlankSegments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FiremudWorkflowIds.workflowId("world-lifecycle", " ", "instance", "req-1"));
  }
}
