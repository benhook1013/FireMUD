package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.mapper.ModerationActionMapper;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
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
  void applyActionSavesEntity() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, 9L, "ban", "test");
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

  @Test
  void evaluatePolicyBlocksGameplayAdmissionForActiveBan() {
    ModerationAction action = new ModerationAction();
    action.setAction("gameplay_ban");
    action.setReason("abuse");
    when(repository.findActivePolicyActions(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(2L),
            org.mockito.Mockito.anyList(),
            org.mockito.Mockito.any()))
        .thenReturn(List.of(action));

    ModerationPolicyDecisionDto decision = service.evaluatePolicy(1L, 2L, "GAMEPLAY_ADMISSION");

    assertEquals(false, decision.allowed());
    assertEquals("gameplay_ban", decision.action());
  }
}
