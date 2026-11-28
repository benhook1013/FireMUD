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
                    new CreateReportRequest(
                        1L,
                        "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1",
                        "e830a76a-a786-43de-9416-5840fa6e0578",
                        "BUG",
                        "bad")));

    assertEquals(
        "Report creation is unavailable until the shared mutation gate is implemented",
        exception.getMessage());
    verifyNoInteractions(repository, mapper);
  }
}
