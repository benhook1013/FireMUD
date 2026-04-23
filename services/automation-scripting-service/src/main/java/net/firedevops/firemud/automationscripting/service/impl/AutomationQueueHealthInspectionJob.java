package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained internally.")
public class AutomationQueueHealthInspectionJob {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AutomationQueueHealthInspectionJob.class);

  private final AutomationQueueService automationQueueService;
  private final ScheduledJobReadinessGuard readinessGuard;

  @org.springframework.beans.factory.annotation.Value("${script.outbox.queue-health-max-queues:50}")
  private int maxQueues;

  @org.springframework.beans.factory.annotation.Value(
      "${script.outbox.queue-health-stale-after-seconds:300}")
  private long staleAfterSeconds;

  public AutomationQueueHealthInspectionJob(
      AutomationQueueService automationQueueService, ScheduledJobReadinessGuard readinessGuard) {
    this.automationQueueService = automationQueueService;
    this.readinessGuard = readinessGuard;
  }

  @Timed(value = "scriptOutbox.queueHealthInspection")
  @Scheduled(
      fixedDelayString = "${script.outbox.queue-health-interval-seconds:60}",
      timeUnit = TimeUnit.SECONDS)
  public void inspectQueueProjectionHealth() {
    if (!readinessGuard.canRun()) {
      return;
    }
    AutomationQueueService.QueueHealthSnapshot snapshot =
        automationQueueService.inspectProjectionHealth(maxQueues, staleAfterSeconds);
    if (snapshot.orphanedEntries() > 0) {
      LOGGER.warn(
          "Detected orphaned automation queue projection entries inspectedQueues={} orphanedEntries={} oldestEntryAgeSeconds={}",
          snapshot.inspectedQueues(),
          snapshot.orphanedEntries(),
          snapshot.oldestEntryAgeSeconds());
    }
  }
}
