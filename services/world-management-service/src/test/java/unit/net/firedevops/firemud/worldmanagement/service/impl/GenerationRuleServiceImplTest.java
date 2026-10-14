package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.mapper.GenerationRuleMapper;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
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
  private WorldDesignPublicationFenceRepository publicationFenceRepository;

  @BeforeEach
  void setUp() {
    repository = mock(GenerationRuleRepository.class);
    // Positive component tests stipulate an OPEN owner; they do not establish write authorization.
    publicationFenceRepository = mock(WorldDesignPublicationFenceRepository.class);
    service = new GenerationRuleServiceImpl(repository, mapper, publicationFenceRepository);
  }

  @Test
  void saveRulePersistsEntity() {
    GenerationRuleDto dto = new GenerationRuleDto(null, 1L, 7L, "max_rooms", null, null, "10");
    GenerationRule saved = new GenerationRule();
    saved.setId(5L);
    saved.setTenantId(1L);
    saved.setVersionId(7L);
    saved.setName("max_rooms");
    saved.setValue("10");
    when(repository.save(any())).thenReturn(saved);
    GenerationRuleDto result = service.saveRule(dto);
    assertEquals(5L, result.id());
    assertEquals(7L, result.versionId());
    var order = inOrder(publicationFenceRepository, repository);
    order.verify(publicationFenceRepository).lockExistingOpenByPrivateKeys(1L, 7L);
    order
        .verify(repository)
        .save(argThat(entity -> Long.valueOf(7L).equals(entity.getVersionId())));
  }

  @Test
  void deniedOwnerPreventsMappingAndSave() {
    GenerationRuleMapper deniedMapper = mock(GenerationRuleMapper.class);
    service = new GenerationRuleServiceImpl(repository, deniedMapper, publicationFenceRepository);
    doThrow(new WorldDesignPublicationFenceRepository.ConflictException("Owner unavailable"))
        .when(publicationFenceRepository)
        .lockExistingOpenByPrivateKeys(1L, 7L);

    assertThrows(
        WorldDesignPublicationFenceRepository.ConflictException.class,
        () -> service.saveRule(new GenerationRuleDto(null, 1L, 7L, "max_rooms", null, null, "10")));
    verifyNoInteractions(deniedMapper, repository);
  }

  @Test
  void existingRuleMustMatchLockedScopeAndPreservesOptimisticVersion() {
    GenerationRule existing = new GenerationRule();
    existing.setVersion(3);
    when(repository.findByTenantIdAndVersionIdAndId(1L, 7L, 5L)).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    GenerationRuleMapper updateMapper = spy(mapper);
    service = new GenerationRuleServiceImpl(repository, updateMapper, publicationFenceRepository);
    var dto = new GenerationRuleDto(5L, 1L, 7L, "max_rooms", null, null, "10");

    service.saveRule(dto);

    var order = inOrder(publicationFenceRepository, repository, updateMapper);
    order.verify(publicationFenceRepository).lockExistingOpenByPrivateKeys(1L, 7L);
    order.verify(repository).findByTenantIdAndVersionIdAndId(1L, 7L, 5L);
    order.verify(updateMapper).toEntity(dto);
    order
        .verify(repository)
        .save(
            argThat(
                entity ->
                    entity.getVersion() == 3 && Long.valueOf(7L).equals(entity.getVersionId())));
  }

  @Test
  void foreignOrMissingExistingRuleCannotMoveIntoTheLockedVersion() {
    GenerationRuleMapper deniedMapper = mock(GenerationRuleMapper.class);
    service = new GenerationRuleServiceImpl(repository, deniedMapper, publicationFenceRepository);
    when(repository.findByTenantIdAndVersionIdAndId(1L, 7L, 5L)).thenReturn(Optional.empty());

    assertThrows(
        WorldDesignPublicationFenceRepository.ConflictException.class,
        () -> service.saveRule(new GenerationRuleDto(5L, 1L, 7L, "max_rooms", null, null, "10")));
    var order = inOrder(publicationFenceRepository, repository);
    order.verify(publicationFenceRepository).lockExistingOpenByPrivateKeys(1L, 7L);
    order.verify(repository).findByTenantIdAndVersionIdAndId(1L, 7L, 5L);
    verify(repository, never()).save(any());
    verifyNoInteractions(deniedMapper);
  }

  @Test
  void mapperPreservesExplicitVersionAndDoesNotDefaultMissingVersion() {
    assertEquals(
        7L,
        mapper
            .toEntity(new GenerationRuleDto(null, 1L, 7L, "max_rooms", null, null, "10"))
            .getVersionId());
    assertNull(new GenerationRule().getVersionId());
    assertNull(
        mapper
            .toEntity(new GenerationRuleDto(null, 1L, null, "max_rooms", null, null, "10"))
            .getVersionId());
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
