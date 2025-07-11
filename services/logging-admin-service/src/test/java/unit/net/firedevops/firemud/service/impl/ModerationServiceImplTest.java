package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.dto.ModerationActionDto;
import net.firedevops.firemud.entity.ModerationAction;
import net.firedevops.firemud.mapper.ModerationActionMapper;
import net.firedevops.firemud.repository.ModerationActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ModerationServiceImplTest {
  @Mock ModerationActionRepository repository;
  @Mock ModerationActionMapper mapper;

  @InjectMocks ModerationServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void applyActionSavesEntity() {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, "ban", "test");
    ModerationAction entity = new ModerationAction();
    ModerationAction saved = new ModerationAction();
    saved.setId(1L);
    saved.setCreatedAt(Instant.now());
    when(repository.save(any())).thenReturn(saved);
    ModerationActionDto dto =
        new ModerationActionDto(1L, 1L, 2L, "ban", "test", saved.getCreatedAt(), null);
    when(mapper.toDto(saved)).thenReturn(dto);

    ModerationActionDto result = service.applyAction(req);

    assertEquals(dto, result);
    verify(repository).save(any(ModerationAction.class));
  }
}
