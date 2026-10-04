package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.mapper.ModerationActionMapper;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ModerationServiceImplTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

  @Mock ModerationActionRepository repository;
  @Mock ModerationActionMapper mapper;

  @InjectMocks ModerationServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void applyActionSavesEntity() throws Exception {
    ApplyModerationActionRequest req =
        new ApplyModerationActionRequest(1L, ACCOUNT_ID.toString(), 9L, "ban", "test");
    ModerationAction saved = new ModerationAction();
    saved.setId(1L);
    saved.setCreatedAt(Instant.now());
    when(repository.save(any())).thenReturn(saved);
    ModerationActionDto dto =
        new ModerationActionDto(1L, 1L, ACCOUNT_ID, "ban", "test", saved.getCreatedAt(), null);
    when(mapper.toDto(saved)).thenReturn(dto);

    ModerationActionDto result = service.applyAction(req);

    assertEquals(dto, result);
    ArgumentCaptor<ModerationAction> actionCaptor = ArgumentCaptor.forClass(ModerationAction.class);
    verify(repository).save(actionCaptor.capture());
    assertEquals(ACCOUNT_ID, actionCaptor.getValue().getAccountId());
  }

  @Test
  void evaluatePolicyBlocksGameplayAdmissionForActiveBan() {
    ModerationAction action = new ModerationAction();
    action.setAction("gameplay_ban");
    action.setReason("abuse");
    when(repository.findActivePolicyActions(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(ACCOUNT_ID),
            org.mockito.Mockito.anyList(),
            org.mockito.Mockito.any()))
        .thenReturn(List.of(action));

    ModerationPolicyDecisionDto decision =
        service.evaluatePolicy(1L, ACCOUNT_ID, "GAMEPLAY_ADMISSION");

    assertEquals(false, decision.allowed());
    assertEquals("gameplay_ban", decision.action());
  }
}
