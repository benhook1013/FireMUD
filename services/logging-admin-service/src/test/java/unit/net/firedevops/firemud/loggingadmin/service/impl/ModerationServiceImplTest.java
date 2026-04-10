package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.loggingadmin.client.AccountClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
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
  @Mock AccountClient accountClient;
  @Mock GameSessionClient gameSessionClient;
  @Mock SagaRunner sagaRunner;

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
    doAnswer(
            inv -> {
              ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              return null;
            })
        .when(sagaRunner)
        .run(any());

    ModerationActionDto result = service.applyAction(req);

    assertEquals(dto, result);
    verify(repository).save(any(ModerationAction.class));
    verify(gameSessionClient).stopSession(9L);
  }

  @Test
  void applyActionDeletesRecordedActionWhenSessionStopFails() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, 9L, "ban", "test");
    ModerationAction saved = new ModerationAction();
    saved.setId(1L);
    saved.setCreatedAt(Instant.now());
    when(repository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("session service unavailable"))
        .when(gameSessionClient)
        .stopSession(9L);
    doAnswer(
            inv -> {
              ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              return null;
            })
        .when(sagaRunner)
        .run(any());

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> service.applyAction(req));

    assertEquals("Moderation action failed", ex.getMessage());
    verify(repository).save(any(ModerationAction.class));
    verify(repository).delete(saved);
    verify(accountClient).deleteAccount(1L, 2L);
    verify(gameSessionClient).stopSession(9L);
  }
}
