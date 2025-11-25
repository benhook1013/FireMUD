package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.SessionRateLimiter;
import net.firedevops.firemud.service.TickService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommandServiceImplTest {
  @Test
  void logOnlyAcknowledgesCommands() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    CommandServiceImpl service =
        new CommandServiceImpl(tickService, rateLimiter, new LogOnlyProperties(true), repository);

    CommandEnqueueResult result = service.enqueue("non-numeric", "look", false);

    assertTrue(result.accepted());
    verify(rateLimiter, never()).allow(Mockito.anyLong());
    verify(tickService, never()).enqueueCommand(Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean());
  }

  @Test
  void rateLimitFailurePropagatesError() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(5L)).thenReturn(false);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    CommandServiceImpl service =
        new CommandServiceImpl(tickService, rateLimiter, new LogOnlyProperties(false), repository);

    CommandEnqueueResult result = service.enqueue("5", "look", false);

    assertTrue(result.hasError());
    assertEquals("RATE_LIMIT", result.errorCode());
    verify(tickService, never()).enqueueCommand(Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean());
  }

  @Test
  void enqueuePassesThroughWhenAllowed() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(7L)).thenReturn(true);
    GameInstance instance = new GameInstance();
    instance.setTenantId(9L);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));
    CommandServiceImpl service =
        new CommandServiceImpl(tickService, rateLimiter, new LogOnlyProperties(false), repository);

    CommandEnqueueResult result = service.enqueue("7", "look", true);

    assertTrue(result.accepted());
    verify(rateLimiter).allow(7L);
    verify(tickService, times(1)).enqueueCommand(7L, "look", true);
  }
}
