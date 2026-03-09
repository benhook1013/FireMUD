package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;

public interface SagaDashboardService {
  List<SagaInstanceDto> listInstances();

  List<SagaStepDto> listSteps(Long instanceId);
}
