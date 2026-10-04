package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.loggingadmin.dto.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.mapper.ReportMapper;
import net.firedevops.firemud.loggingadmin.repository.PlayerReportRepository;
import org.junit.jupiter.api.Test;

class ReportServiceImplTest {
  private static final String REPORTER_UUID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String TARGET_UUID = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";

  @Test
  void createReportFailsClosedWithoutTouchingPlayerReportsRepository() {
    PlayerReportRepository repository = mock(PlayerReportRepository.class);
    ReportMapper mapper = mock(ReportMapper.class);
    ReportServiceImpl service = new ReportServiceImpl(repository, mapper);

    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                service.createReport(
                    new CreateReportRequest(1L, REPORTER_UUID, TARGET_UUID, "BUG", "bad")));

    assertEquals(
        "Report creation is unavailable until the shared mutation gate is implemented",
        exception.getMessage());
    verifyNoInteractions(repository, mapper);
  }
}
