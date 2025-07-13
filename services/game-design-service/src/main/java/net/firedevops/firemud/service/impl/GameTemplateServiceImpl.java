package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameTemplateDto;
import net.firedevops.firemud.entity.GameTemplate;
import net.firedevops.firemud.mapper.GameTemplateMapper;
import net.firedevops.firemud.repository.GameTemplateRepository;
import net.firedevops.firemud.service.GameTemplateService;
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
  public GameTemplateDto createTemplate(GameTemplateDto dto) {
    logger.info("Creating game template {}", dto.name());
    GameTemplate entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<GameTemplateDto> listTemplates(Long tenantId, Pageable pageable) {
    return repository.findByTenantId(tenantId, pageable).map(mapper::toDto);
  }
}
