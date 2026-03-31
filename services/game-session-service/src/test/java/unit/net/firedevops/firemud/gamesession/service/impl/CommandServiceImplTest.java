package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommandServiceImplTest {
  @Test
  void devIsolatedAcknowledgesCommands() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            new DevIsolatedProperties(true),
            repository,
            sessionContextService);

    CommandEnqueueResult result = service.enqueue("non-numeric", "look", false);

    assertTrue(result.accepted());
    verify(rateLimiter, never()).allow(Mockito.anyLong());
    verify(tickService, never())
        .enqueueCommand(Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean());
  }

  @Test
  void rateLimitFailurePropagatesError() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(5L)).thenReturn(false);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            new DevIsolatedProperties(false),
            repository,
            sessionContextService);

    CommandEnqueueResult result = service.enqueue("5", "look", false);

    assertTrue(result.hasError());
    assertEquals("RATE_LIMIT", result.errorCode());
    verify(tickService, never())
        .enqueueCommand(Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean());
  }

  @Test
  void enqueuePassesThroughWhenAllowed() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(7L)).thenReturn(true);
    GameInstance instance = new GameInstance();
    instance.setTenantId(9L);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            new DevIsolatedProperties(false),
            repository,
            sessionContextService);

    CommandEnqueueResult result = service.enqueue("7", "look", true);

    assertTrue(result.accepted());
    verify(rateLimiter).allow(7L);
    verify(tickService, times(1)).enqueueCommand(7L, "look", true);
  }

  @Test
  void gameplayCommandsQueueAgainstGameInstanceWhenSessionContextHasOne() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(17L)).thenReturn(true);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(sessionContextService.findBySessionId(17L))
        .thenReturn(
            Optional.of(new SessionContext(17L, 9L, 3L, "demo", 44L, "char", 99L, "room", "jwt")));
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            new DevIsolatedProperties(false),
            repository,
            sessionContextService);

    CommandEnqueueResult result = service.enqueue("17", "look", false);

    assertTrue(result.accepted());
    verify(tickService, times(1)).enqueueCommand(99L, "look", false);
  }
}
