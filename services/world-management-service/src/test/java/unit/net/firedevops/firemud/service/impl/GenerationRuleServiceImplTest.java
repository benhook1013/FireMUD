package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.entity.GenerationRule;
import net.firedevops.firemud.mapper.GenerationRuleMapper;
import net.firedevops.firemud.repository.GenerationRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class GenerationRuleServiceImplTest {
  private GenerationRuleRepository repository;
  private GenerationRuleMapper mapper = Mappers.getMapper(GenerationRuleMapper.class);
  private GenerationRuleServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(GenerationRuleRepository.class);
    service = new GenerationRuleServiceImpl(repository, mapper);
  }

  @Test
  void saveRulePersistsEntity() {
    GenerationRuleDto dto = new GenerationRuleDto(null, 1L, "max_rooms", "10");
    GenerationRule saved = new GenerationRule();
    saved.setId(5L);
    saved.setTenantId(1L);
    saved.setName("max_rooms");
    saved.setValue("10");
    when(repository.save(any())).thenReturn(saved);
    GenerationRuleDto result = service.saveRule(dto);
    assertEquals(5L, result.id());
    verify(repository).save(any(GenerationRule.class));
  }

  @Test
  void listRulesReturnsDtos() {
    GenerationRule rule = new GenerationRule();
    rule.setId(1L);
    rule.setTenantId(1L);
    rule.setName("max_rooms");
    rule.setValue("10");
    when(repository.findByTenantId(1L)).thenReturn(List.of(rule));
    List<GenerationRuleDto> result = service.listRules(1L);
    assertEquals(1, result.size());
    assertEquals("max_rooms", result.get(0).name());
  }
}
