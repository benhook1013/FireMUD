package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.GenerationRuleDto;

public interface GenerationRuleService {
  GenerationRuleDto saveRule(GenerationRuleDto dto);

  List<GenerationRuleDto> listRules(Long tenantId);
}
