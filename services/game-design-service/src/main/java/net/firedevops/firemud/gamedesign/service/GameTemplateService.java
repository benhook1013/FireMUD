package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GameTemplateService {
  GameTemplateDto createTemplate(GameTemplateDto dto);

  Page<GameTemplateDto> listTemplates(String tenantId, Pageable pageable);
}
