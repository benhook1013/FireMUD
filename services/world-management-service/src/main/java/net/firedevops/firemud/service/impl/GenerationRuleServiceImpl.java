package net.firedevops.firemud.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.entity.GenerationRule;
import net.firedevops.firemud.mapper.GenerationRuleMapper;
import net.firedevops.firemud.repository.GenerationRuleRepository;
import net.firedevops.firemud.service.GenerationRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GenerationRuleServiceImpl implements GenerationRuleService {
  private final GenerationRuleRepository repository;
  private final GenerationRuleMapper mapper;

  @Override
  @Transactional
  public GenerationRuleDto saveRule(GenerationRuleDto dto) {
    GenerationRule entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GenerationRuleDto> listRules(Long tenantId) {
    return repository.findByTenantId(tenantId).stream().map(mapper::toDto).toList();
  }
}
