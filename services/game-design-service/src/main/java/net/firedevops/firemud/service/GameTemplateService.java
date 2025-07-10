package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.GameTemplateDto;

public interface GameTemplateService {
  GameTemplateDto createTemplate(GameTemplateDto dto);

  List<GameTemplateDto> listTemplates(Long tenantId);
}
