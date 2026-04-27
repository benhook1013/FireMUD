package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.mapper.GenerationRuleMapper;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    GenerationRuleDto dto = new GenerationRuleDto(null, 1L, "max_rooms", null, null, "10");
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
    Page<GenerationRule> page = new PageImpl<>(List.of(rule));
    when(repository.findByTenantId(1L, PageRequest.of(0, 20))).thenReturn(page);
    Page<GenerationRuleDto> result = service.listRules(1L, PageRequest.of(0, 20));
    assertEquals(1, result.getTotalElements());
    assertEquals("max_rooms", result.getContent().get(0).name());
  }
}
