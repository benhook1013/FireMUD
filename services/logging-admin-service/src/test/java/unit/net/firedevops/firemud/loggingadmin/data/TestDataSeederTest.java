package net.firedevops.firemud.loggingadmin.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock LogEventRepository logEventRepository;
  @Mock PlayerReportRepository playerReportRepository;
  @Mock ModerationActionRepository moderationActionRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(logEventRepository, playerReportRepository, moderationActionRepository);
  }

  @Test
  void runSeedsCanonicalRowsWhenMissing() throws Exception {
    when(logEventRepository.findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started"))
        .thenReturn(Optional.empty());
    when(playerReportRepository.findFirstByTenantIdAndReporterAccountIdAndTargetAccountIdAndType(
            1L, 1L, 2L, "bug"))
        .thenReturn(Optional.empty());
    when(moderationActionRepository.findFirstByTenantIdAndAccountIdAndActionAndReason(
            1L, 2L, "warning", "initial seed"))
        .thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(logEventRepository).save(any());
    verify(playerReportRepository).save(any());
    verify(moderationActionRepository).save(any());
  }

  @Test
  void runReassertsCanonicalRowsWhenTheyAlreadyExist() throws Exception {
    when(logEventRepository.findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started"))
        .thenReturn(Optional.of(new net.firedevops.firemud.loggingadmin.entity.LogEvent()));
    when(playerReportRepository.findFirstByTenantIdAndReporterAccountIdAndTargetAccountIdAndType(
            1L, 1L, 2L, "bug"))
        .thenReturn(Optional.of(new net.firedevops.firemud.loggingadmin.entity.PlayerReport()));
    when(moderationActionRepository.findFirstByTenantIdAndAccountIdAndActionAndReason(
            1L, 2L, "warning", "initial seed"))
        .thenReturn(Optional.of(new net.firedevops.firemud.loggingadmin.entity.ModerationAction()));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(logEventRepository).save(any());
    verify(playerReportRepository).save(any());
    verify(moderationActionRepository).save(any());
    verify(logEventRepository, never()).count();
    verify(playerReportRepository, never()).count();
    verify(moderationActionRepository, never()).count();
  }
}
