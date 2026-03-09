package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.mapper.GameTemplateMapper;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class GameTemplateServiceImplTest {
  @Test
  void listTemplatesReturnsPage() {
    GameTemplateRepository repo = Mockito.mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = Mockito.mock(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);

    GameTemplate template = new GameTemplate();
    template.setId(1L);
    template.setTenantId("1");
    Page<GameTemplate> page = new PageImpl<>(List.of(template));
    when(repo.findByTenantId("1", PageRequest.of(0, 20))).thenReturn(page);
    Page<GameTemplateDto> result = service.listTemplates("1", PageRequest.of(0, 20));
    assertEquals(1, result.getTotalElements());
  }
}
