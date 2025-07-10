package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.SagaInstanceDto;
import net.firedevops.firemud.dto.SagaStepDto;

public interface SagaDashboardService {
  List<SagaInstanceDto> listInstances();

  List<SagaStepDto> listSteps(Long instanceId);
}
