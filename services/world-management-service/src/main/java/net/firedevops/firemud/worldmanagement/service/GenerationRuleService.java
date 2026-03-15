package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GenerationRuleService {
  GenerationRuleDto saveRule(GenerationRuleDto dto);

  Page<GenerationRuleDto> listRules(Long tenantId, Pageable pageable);
}
