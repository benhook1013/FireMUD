package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.dto.GameTemplateDto;
import net.firedevops.firemud.entity.GameTemplate;
import net.firedevops.firemud.mapper.GameTemplateMapper;
import net.firedevops.firemud.repository.GameTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

class GameTemplateServiceImplTest {
  @Test
  void listTemplatesReturnsPage() {
    GameTemplateRepository repo = Mockito.mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = Mappers.getMapper(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);

    GameTemplate template = new GameTemplate();
    template.setId(1L);
    template.setTenantId(1L);
    Page<GameTemplate> page = new PageImpl<>(List.of(template));
    when(repo.findByTenantId(1L, PageRequest.of(0, 20))).thenReturn(page);
    when(mapper.toDto(template)).thenReturn(new GameTemplateDto(1L, 1L, "name", null, "{}", null));
    Page<GameTemplateDto> result = service.listTemplates(1L, PageRequest.of(0, 20));
    assertEquals(1, result.getTotalElements());
  }
}
