package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScriptOutboxCleanupJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScriptOutboxCleanupJob.class);

  private final ScriptWorkItemService workItemService;
  private final ScheduledJobReadinessGuard readinessGuard;

  public ScriptOutboxCleanupJob(
      ScriptWorkItemService workItemService, ScheduledJobReadinessGuard readinessGuard) {
    this.workItemService = workItemService;
    this.readinessGuard = readinessGuard;
  }

  @Timed(value = "scriptOutbox.cleanup")
  @Scheduled(
      fixedDelayString = "${script.outbox.terminal-cleanup-interval-seconds:300}",
      timeUnit = TimeUnit.SECONDS)
  public void cleanupTerminalWorkItems() {
    if (!readinessGuard.canRun()) {
      return;
    }
    ScriptWorkItemService.TerminalCleanupResult result = workItemService.cleanupTerminalWorkItems();
    if (result.totalDeleted() > 0) {
      LOGGER.info(
          "Cleaned terminal script work items handedOffDeleted={} canceledDeleted={} deadLetteredDeleted={}",
          result.handedOffDeleted(),
          result.canceledDeleted(),
          result.deadLetteredDeleted());
    }
  }
}
