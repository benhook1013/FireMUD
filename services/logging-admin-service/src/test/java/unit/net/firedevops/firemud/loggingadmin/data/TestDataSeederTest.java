package net.firedevops.firemud.loggingadmin.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    when(logEventRepository.count()).thenReturn(0L);
    when(playerReportRepository.count()).thenReturn(0L);
    when(moderationActionRepository.count()).thenReturn(0L);

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(logEventRepository).save(any());
    verify(playerReportRepository).save(any());
    verify(moderationActionRepository).save(any());
  }
}
