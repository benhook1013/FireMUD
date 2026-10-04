package net.firedevops.firemud.loggingadmin.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.entity.PlayerReport;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singletons for demo seeding.")
public class TestDataSeeder implements ApplicationRunner {
  private static final long DEMO_TENANT_ID = 1L;
  private static final long DEMO_REPORTER_ACCOUNT_ID = 1L;
  private static final long DEMO_TARGET_ACCOUNT_ID = 2L;

  private final LogEventRepository logEventRepository;
  private final PlayerReportRepository playerReportRepository;
  private final ModerationActionRepository moderationActionRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ensureStartupLogEvent();
    ensureSamplePlayerReport();
    ensureSampleModerationAction();
  }

  private void ensureStartupLogEvent() {
    LogEvent event =
        logEventRepository
            .findFirstByTenantIdAndTypeAndMessage(DEMO_TENANT_ID, "INFO", "Service started")
            .orElseGet(LogEvent::new);
    event.setTenantId(DEMO_TENANT_ID);
    event.setType("INFO");
    event.setMessage("Service started");
    if (event.getTimestamp() == null) {
      event.setTimestamp(Instant.now());
    }
    logEventRepository.save(event);
  }

  private void ensureSamplePlayerReport() {
    PlayerReport report =
        playerReportRepository
            .findFirstByTenantIdAndReporterAccountIdAndTargetAccountIdAndType(
                DEMO_TENANT_ID, DEMO_REPORTER_ACCOUNT_ID, DEMO_TARGET_ACCOUNT_ID, "bug")
            .orElseGet(PlayerReport::new);
    report.setTenantId(DEMO_TENANT_ID);
    report.setReporterAccountId(DEMO_REPORTER_ACCOUNT_ID);
    report.setTargetAccountId(DEMO_TARGET_ACCOUNT_ID);
    report.setType("bug");
    report.setDescription("Sample report");
    if (report.getCreatedAt() == null) {
      report.setCreatedAt(Instant.now());
    }
    playerReportRepository.save(report);
  }

  private void ensureSampleModerationAction() {
    ModerationAction action =
        moderationActionRepository
            .findFirstByTenantIdAndAccountIdAndActionAndReason(
                DEMO_TENANT_ID, DEMO_TARGET_ACCOUNT_ID, "warning", "initial seed")
            .orElseGet(ModerationAction::new);
    action.setTenantId(DEMO_TENANT_ID);
    action.setAccountId(DEMO_TARGET_ACCOUNT_ID);
    action.setAction("warning");
    action.setReason("initial seed");
    if (action.getCreatedAt() == null) {
      action.setCreatedAt(Instant.now());
    }
    moderationActionRepository.save(action);
  }
}
