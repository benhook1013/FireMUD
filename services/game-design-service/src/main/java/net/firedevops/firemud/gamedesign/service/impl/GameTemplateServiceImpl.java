package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.mapper.GameTemplateMapper;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.service.GameTemplateService;
import org.slf4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GameTemplateServiceImpl implements GameTemplateService {
  private static final Logger logger = LoggingUtil.getLogger(GameTemplateServiceImpl.class);

  private final GameTemplateRepository repository;
  private final GameTemplateMapper mapper;

  @Override
  @Transactional
  @Timed(value = "gamedesign.template.create")
  public GameTemplateDto createTemplate(GameTemplateDto dto) {
    logger.info("Creating game template {}", dto.name());
    GameTemplate entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.template.list")
  public Page<GameTemplateDto> listTemplates(String tenantId, Pageable pageable) {
    return repository.findByTenantId(tenantId, pageable).map(mapper::toDto);
  }
}
