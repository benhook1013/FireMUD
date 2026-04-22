package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained, not exposed externally.")
public class ScriptOutboxQueueRebuildJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScriptOutboxQueueRebuildJob.class);

  private final AutomationQueueService automationQueueService;
  private final ScriptOutboxProperties outboxProperties;

  public ScriptOutboxQueueRebuildJob(
      AutomationQueueService automationQueueService, ScriptOutboxProperties outboxProperties) {
    this.automationQueueService = automationQueueService;
    this.outboxProperties = outboxProperties;
  }

  @Timed(value = "scriptOutbox.queueRebuild")
  @Scheduled(
      fixedDelayString = "${script.outbox.queue-rebuild-interval-seconds:60}",
      timeUnit = TimeUnit.SECONDS)
  public void rebuildQueueProjection() {
    int rebuilt =
        automationQueueService.rebuildPendingWorkItemIndex(
            outboxProperties.getQueueRebuildBatchSize());
    if (rebuilt > 0) {
      LOGGER.info("Rebuilt automation queue projection pointers count={}", rebuilt);
    }
  }
}
