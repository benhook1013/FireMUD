package net.firedevops.firemud.loggingadmin.data;

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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private final LogEventRepository logEventRepository;
  private final PlayerReportRepository playerReportRepository;
  private final ModerationActionRepository moderationActionRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (logEventRepository.count() == 0) {
      LogEvent event = new LogEvent();
      event.setTenantId(1L);
      event.setType("INFO");
      event.setMessage("Service started");
      event.setTimestamp(Instant.now());
      logEventRepository.save(event);
    }

    if (playerReportRepository.count() == 0) {
      PlayerReport report = new PlayerReport();
      report.setTenantId(1L);
      report.setReporterAccountId(1L);
      report.setTargetAccountId(2L);
      report.setType("bug");
      report.setDescription("Sample report");
      report.setCreatedAt(Instant.now());
      playerReportRepository.save(report);
    }

    if (moderationActionRepository.count() == 0) {
      ModerationAction action = new ModerationAction();
      action.setTenantId(1L);
      action.setAccountId(2L);
      action.setAction("warning");
      action.setReason("initial seed");
      action.setCreatedAt(Instant.now());
      moderationActionRepository.save(action);
    }
  }
}
