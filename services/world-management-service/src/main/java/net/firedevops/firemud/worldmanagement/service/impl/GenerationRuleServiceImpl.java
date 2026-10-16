package net.firedevops.firemud.worldmanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.mapper.GenerationRuleMapper;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.service.GenerationRuleService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GenerationRuleServiceImpl implements GenerationRuleService {
  private final GenerationRuleRepository repository;
  private final GenerationRuleMapper mapper;
  private final WorldDesignPublicationFenceRepository publicationFenceRepository;

  @Override
  @Transactional
  @Timed(value = "generationRule.save")
  public GenerationRuleDto saveRule(GenerationRuleDto dto) {
    publicationFenceRepository.lockExistingOpenByPrivateKeys(dto.tenantId(), dto.versionId());
    GenerationRule existing = null;
    if (dto.id() != null) {
      existing =
          repository
              .findByTenantIdAndVersionIdAndId(dto.tenantId(), dto.versionId(), dto.id())
              .orElseThrow(
                  () ->
                      new WorldDesignPublicationFenceRepository.ConflictException(
                          "Generation rule does not belong to the locked tenant and Version"));
    }
    GenerationRule entity = mapper.toEntity(dto);
    if (existing != null) {
      entity.setVersion(existing.getVersion());
    }
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
