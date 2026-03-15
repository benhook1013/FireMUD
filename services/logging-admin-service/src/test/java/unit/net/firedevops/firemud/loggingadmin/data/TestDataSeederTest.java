package net.firedevops.firemud.loggingadmin.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.loggingadmin.repository.FeatureFlagRepository;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock FeatureFlagRepository featureFlagRepository;
  @Mock LogEventRepository logEventRepository;
  @Mock PlayerReportRepository playerReportRepository;
  @Mock ModerationActionRepository moderationActionRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(
            featureFlagRepository,
            logEventRepository,
            playerReportRepository,
            moderationActionRepository);
  }

  @Test
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    when(featureFlagRepository.count()).thenReturn(0L);
    when(logEventRepository.count()).thenReturn(0L);
    when(playerReportRepository.count()).thenReturn(0L);
    when(moderationActionRepository.count()).thenReturn(0L);

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(featureFlagRepository).save(any());
    verify(logEventRepository).save(any());
    verify(playerReportRepository).save(any());
    verify(moderationActionRepository).save(any());
  }
}
