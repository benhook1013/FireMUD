package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.mapper.GameTemplateMapper;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class GameTemplateServiceImplTest {
  @Test
  void createWithoutIdUsesThePersistedIdentityAndPreservesItsTenant() {
    GameTemplateRepository repo = Mockito.mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = Mockito.mock(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);
    GameTemplateDto request =
        new GameTemplateDto(null, "tenant-a", "Fresh", null, "{}", null, null, null, null, null);
    GameTemplate mapped = new GameTemplate();
    mapped.setTenantId(request.tenantId());
    mapped.setName(request.name());
    GameTemplate saved = new GameTemplate();
    saved.setId(73L);
    saved.setTenantId(request.tenantId());
    GameTemplateDto result =
        new GameTemplateDto(
            73L,
            "tenant-a",
            "Fresh",
            null,
            "{}",
            null,
            null,
            "{}",
            TemplateReferencePhase.ENFORCED,
            null);
    when(mapper.toEntity(request)).thenReturn(mapped);
    when(repo.save(mapped)).thenReturn(saved);
    when(mapper.toDto(saved)).thenReturn(result);

    assertEquals(result, service.createTemplate(request));
    Mockito.verify(repo).save(mapped);
  }

  @Test
  void createRejectsCallerSuppliedIdBeforeMappingOrRepositoryAccess() {
    GameTemplateRepository repo = Mockito.mock(GameTemplateRepository.class);
    GameTemplateMapper mapper = Mockito.mock(GameTemplateMapper.class);
    GameTemplateServiceImpl service = new GameTemplateServiceImpl(repo, mapper);
    GameTemplateDto request =
        new GameTemplateDto(
            19L, "tenant-b", "Replacement", null, "{}", null, null, null, null, null);
    GameTemplate mapped = new GameTemplate();
    mapped.setId(request.id());
    mapped.setTenantId(request.tenantId());
    when(mapper.toEntity(request)).thenReturn(mapped);
    when(repo.save(mapped)).thenReturn(mapped);
    when(mapper.toDto(mapped)).thenReturn(request);

    assertThatThrownBy(() -> service.createTemplate(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ARGUMENT: template id must not be supplied on create");
    verifyNoInteractions(repo, mapper);
  }

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
