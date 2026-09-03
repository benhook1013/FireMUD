package net.firedevops.firemud.loggingadmin.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.mapper.SagaMapper;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnBean({SagaInstanceRepository.class, SagaStepRepository.class})
public class SagaDashboardServiceImpl implements SagaDashboardService {
  private final SagaInstanceRepository instanceRepository;
  private final SagaStepRepository stepRepository;
  private final SagaMapper mapper;

  public SagaDashboardServiceImpl(
      SagaInstanceRepository instanceRepository,
      SagaStepRepository stepRepository,
      SagaMapper mapper) {
    this.instanceRepository = instanceRepository;
    this.stepRepository = stepRepository;
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "saga.listInstances")
  public List<SagaInstanceDto> listInstances() {
    return instanceRepository.findAll().stream().map(mapper::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "saga.listSteps")
  public List<SagaStepDto> listSteps(Long instanceId) {
    instanceRepository
        .findById(instanceId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saga instance not found"));
    return stepRepository.findByInstanceId(instanceId).stream().map(mapper::toDto).toList();
  }
}
