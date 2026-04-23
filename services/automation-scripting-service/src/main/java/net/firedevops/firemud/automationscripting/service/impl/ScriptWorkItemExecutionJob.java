package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained, not exposed externally.")
public class ScriptWorkItemExecutionJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScriptWorkItemExecutionJob.class);

  private final ScriptWorkItemExecutionService executionService;
  private final ScriptOutboxProperties outboxProperties;
  private final ScheduledJobReadinessGuard readinessGuard;

  public ScriptWorkItemExecutionJob(
      ScriptWorkItemExecutionService executionService,
      ScriptOutboxProperties outboxProperties,
      ScheduledJobReadinessGuard readinessGuard) {
    this.executionService = executionService;
    this.outboxProperties = outboxProperties;
    this.readinessGuard = readinessGuard;
  }

  @Timed(value = "scriptOutbox.execution")
  @Scheduled(
      fixedDelayString = "${script.outbox.execution-interval-seconds:5}",
      timeUnit = TimeUnit.SECONDS)
  public void processPendingWorkItems() {
    if (!readinessGuard.canRun()) {
      return;
    }
    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        executionService.processPendingWorkItems(outboxProperties.getExecutionBatchSize());
    if (result.claimedCount() > 0) {
      LOGGER.info(
          "Processed script work items claimed={} completed={} failed={}",
          result.claimedCount(),
          result.completedCount(),
          result.failedCount());
    }
  }
}
