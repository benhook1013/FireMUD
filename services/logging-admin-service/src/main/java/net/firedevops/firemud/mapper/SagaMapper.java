package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.SagaInstanceDto;
import net.firedevops.firemud.dto.SagaStepDto;
import net.firedevops.firemud.entity.SagaInstance;
import net.firedevops.firemud.entity.SagaStep;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SagaMapper {
  SagaInstanceDto toDto(SagaInstance entity);

  SagaStepDto toDto(SagaStep entity);
}
