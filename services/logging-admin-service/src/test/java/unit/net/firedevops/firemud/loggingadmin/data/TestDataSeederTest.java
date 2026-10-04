package net.firedevops.firemud.loggingadmin.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock LogEventRepository logEventRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder = new TestDataSeeder(logEventRepository);
  }

  @Test
  void runSeedsCanonicalRowsWhenMissing() throws Exception {
    when(logEventRepository.findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started"))
        .thenReturn(Optional.empty());
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(logEventRepository).findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started");
    verify(logEventRepository).save(any());
    verifyNoMoreInteractions(logEventRepository);
  }

  @Test
  void runReassertsCanonicalRowsWhenTheyAlreadyExist() throws Exception {
    when(logEventRepository.findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started"))
        .thenReturn(Optional.of(new net.firedevops.firemud.loggingadmin.entity.LogEvent()));
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(logEventRepository).save(any());
    verify(logEventRepository).findFirstByTenantIdAndTypeAndMessage(1L, "INFO", "Service started");
    verifyNoMoreInteractions(logEventRepository);
  }
}
