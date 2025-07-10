package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.dto.GameTemplateDto;
import net.firedevops.firemud.entity.GameTemplate;
import net.firedevops.firemud.mapper.GameTemplateMapper;
import net.firedevops.firemud.repository.GameTemplateRepository;
import org.junit.jupiter.api.Test;

class GameTemplateServiceImplTest {
  @Test
  void createTemplateSavesEntity() {
    GameTemplateRepository repo = mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = mock(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);
    GameTemplateDto dto = new GameTemplateDto(null, 1L, "test", null, "{}", null);
    GameTemplate entity = new GameTemplate();
    when(mapper.toEntity(dto)).thenReturn(entity);
    when(repo.save(entity)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(new GameTemplateDto(1L, 1L, "test", null, "{}", null));
    GameTemplateDto result = service.createTemplate(dto);
    assertEquals(1L, result.id());
  }

  @Test
  void listTemplatesFiltersByTenantId() {
    GameTemplateRepository repo = mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = mock(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);
    GameTemplate template = new GameTemplate();
    template.setId(1L);
    template.setTenantId(1L);
    when(repo.findAll()).thenReturn(List.of(template));
    when(mapper.toDto(template)).thenReturn(new GameTemplateDto(1L, 1L, "name", null, "{}", null));
    List<GameTemplateDto> result = service.listTemplates(1L);
    assertEquals(1, result.size());
  }
}
