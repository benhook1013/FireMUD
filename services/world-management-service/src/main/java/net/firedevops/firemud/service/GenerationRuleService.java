package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.GenerationRuleDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GenerationRuleService {
  GenerationRuleDto saveRule(GenerationRuleDto dto);

  Page<GenerationRuleDto> listRules(Long tenantId, Pageable pageable);
}
