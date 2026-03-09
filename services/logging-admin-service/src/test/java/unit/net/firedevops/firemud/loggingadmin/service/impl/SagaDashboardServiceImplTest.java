package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.mapper.SagaMapper;
import net.firedevops.firemud.metrics.SagaMetrics;
import net.firedevops.firemud.loggingadmin.repository.SagaInstanceRepository;
import net.firedevops.firemud.loggingadmin.repository.SagaStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SagaDashboardServiceImplTest {
  @Mock SagaInstanceRepository instanceRepository;
  @Mock SagaStepRepository stepRepository;
  @Mock SagaMapper mapper;
  @Mock SagaMetrics metrics;

  @InjectMocks SagaDashboardServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void listInstancesReturnsDtos() {
    SagaInstance entity = new SagaInstance();
    SagaInstanceDto dto = new SagaInstanceDto(1L, "demo", "COMPLETED", null, null);
    when(instanceRepository.findAll()).thenReturn(List.of(entity));
    when(mapper.toDto(entity)).thenReturn(dto);

    List<SagaInstanceDto> result = service.listInstances();

    assertEquals(1, result.size());
    assertEquals(dto, result.get(0));
  }
}
