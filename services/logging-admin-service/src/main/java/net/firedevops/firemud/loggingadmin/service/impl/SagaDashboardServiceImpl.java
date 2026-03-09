package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.mapper.SagaMapper;
import net.firedevops.firemud.metrics.SagaMetrics;
import net.firedevops.firemud.loggingadmin.repository.SagaInstanceRepository;
import net.firedevops.firemud.loggingadmin.repository.SagaStepRepository;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SagaDashboardServiceImpl implements SagaDashboardService {
  private final SagaInstanceRepository instanceRepository;
  private final SagaStepRepository stepRepository;
  private final SagaMapper mapper;
  private final SagaMetrics metrics;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages dependencies and metrics bean")
  public SagaDashboardServiceImpl(
      SagaInstanceRepository instanceRepository,
      SagaStepRepository stepRepository,
      SagaMapper mapper,
      SagaMetrics metrics) {
    this.instanceRepository = instanceRepository;
    this.stepRepository = stepRepository;
    this.mapper = mapper;
    this.metrics = metrics;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "saga.listInstances")
  public List<SagaInstanceDto> listInstances() {
    List<SagaInstanceDto> instances =
        instanceRepository.findAll().stream().map(mapper::toDto).toList();
    metrics.setActive(instances.size());
    return instances;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "saga.listSteps")
  public List<SagaStepDto> listSteps(Long instanceId) {
    return stepRepository.findByInstanceId(instanceId).stream().map(mapper::toDto).toList();
  }
}
