package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.entity.GenerationRule;
import net.firedevops.firemud.mapper.GenerationRuleMapper;
import net.firedevops.firemud.repository.GenerationRuleRepository;
import net.firedevops.firemud.service.GenerationRuleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GenerationRuleServiceImpl implements GenerationRuleService {
  private final GenerationRuleRepository repository;
  private final GenerationRuleMapper mapper;

  @Override
  @Transactional
  @Timed(value = "generationRule.save")
  public GenerationRuleDto saveRule(GenerationRuleDto dto) {
    GenerationRule entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "generationRule.list")
  public Page<GenerationRuleDto> listRules(Long tenantId, Pageable pageable) {
    return repository.findByTenantId(tenantId, pageable).map(mapper::toDto);
  }
}
