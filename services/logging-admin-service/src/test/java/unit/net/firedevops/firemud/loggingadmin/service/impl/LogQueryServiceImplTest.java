package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import net.firedevops.firemud.loggingadmin.repository.LogEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class LogQueryServiceImplTest {
  @Mock LogEventRepository repository;

  @InjectMocks LogQueryServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void queryLogsReturnsMessages() {
    LogEvent event = new LogEvent();
    event.setMessage("hello");
    when(repository.findByTenantIdAndMessageContainingIgnoreCase(1L, "test"))
        .thenReturn(List.of(event));

    List<String> result = service.queryLogs(new QueryLogsRequest(1L, "test"));

    assertEquals(List.of("hello"), result);
  }
}
