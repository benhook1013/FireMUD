package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.mapper.SagaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class SagaDashboardServiceImplTest {
  @Mock SagaInstanceRepository instanceRepository;
  @Mock SagaStepRepository stepRepository;
  @Mock SagaMapper mapper;

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

  @Test
  void listStepsRejectsUnknownInstanceButAllowsKnownEmptyInstance() {
    when(instanceRepository.findById(404L)).thenReturn(Optional.empty());

    ResponseStatusException exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> service.listSteps(404L));
    assertEquals(404, exception.getStatusCode().value());

    SagaInstance entity = new SagaInstance();
    when(instanceRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(stepRepository.findByInstanceId(1L)).thenReturn(List.of());
    assertEquals(List.of(), service.listSteps(1L));
  }
}
