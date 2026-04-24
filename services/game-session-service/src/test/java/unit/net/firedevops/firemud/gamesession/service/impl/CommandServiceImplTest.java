package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CommandServiceImplTest {
  @Test
  void rateLimitFailurePropagatesError() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(5L)).thenReturn(false);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameplayCommandRepository commandRepository = Mockito.mock(GameplayCommandRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            Mockito.mock(ScriptEventPublisher.class));

    CommandEnqueueResult result = service.enqueue("5", "look", false);

    assertTrue(result.hasError());
    assertEquals("RATE_LIMIT", result.errorCode());
    verify(tickService, never())
        .enqueueCommand(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyBoolean());
    verify(commandRepository, never()).save(Mockito.any());
  }

  @Test
  void enqueuePassesThroughWhenAllowed() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(7L)).thenReturn(true);
    GameInstance instance = new GameInstance();
    instance.setTenantId(9L);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(instance));
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            Mockito.mock(ScriptEventPublisher.class));

    CommandEnqueueResult result = service.enqueue("7", "look", true);

    assertTrue(result.accepted());
    assertTrue(result.commandId().startsWith("cmd-"));
    verify(rateLimiter).allow(7L);
    verify(tickService, times(1)).enqueueCommand(9L, 7L, result.commandId(), "look", true);
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    verify(commandRepository, times(3)).save(commandCaptor.capture());
    GameplayCommand staged = commandCaptor.getAllValues().get(2);
    assertEquals(result.commandId(), staged.getCommandId());
    assertEquals("STAGED", staged.getExecutionOutcome());
    assertEquals("PENDING", staged.getGameplayResult());
    assertEquals("LOOK", staged.getCommandName());
    assertEquals("look", staged.getSanitizedCommandText());
    assertEquals(1L, staged.getEnqueueSeq());
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
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    ScriptEventPublisher scriptEventPublisher = Mockito.mock(ScriptEventPublisher.class);
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            scriptEventPublisher);

    CommandEnqueueResult result = service.enqueue("17", "look", false);

    assertTrue(result.accepted());
    verify(tickService, times(1)).enqueueCommand(9L, 99L, result.commandId(), "look", false);
    verify(scriptEventPublisher, times(1))
        .publishCommandEvent(Mockito.any(SessionContext.class), Mockito.any(GameplayCommand.class));
  }

  @Test
  void enqueueAddsGameplayLoggingContextWhenSessionIsBound() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(17L)).thenReturn(true);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(sessionContextService.findBySessionId(17L))
        .thenReturn(
            Optional.of(new SessionContext(17L, 9L, 3L, "demo", 44L, "char", 99L, "room", "jwt")));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.doAnswer(
            invocation -> {
              assertEquals("9", MDC.get("tenantId"));
              assertEquals("99", MDC.get("gameInstanceId"));
              assertEquals("44", MDC.get("characterId"));
              assertTrue(MDC.get("commandId").startsWith("cmd-"));
              assertNull(MDC.get("regionId"));
              return null;
            })
        .when(tickService)
        .enqueueCommand(
            Mockito.eq(9L),
            Mockito.eq(99L),
            Mockito.anyString(),
            Mockito.eq("look"),
            Mockito.eq(false));

    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            Mockito.mock(ScriptEventPublisher.class));

    CommandEnqueueResult result = service.enqueue("17", "look", false);

    assertTrue(result.accepted());
    assertNull(MDC.get("tenantId"));
    assertNull(MDC.get("gameInstanceId"));
    assertNull(MDC.get("characterId"));
    assertNull(MDC.get("commandId"));
  }

  @Test
  void loginCommandsAreRedactedFromLogs(CapturedOutput output) {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(17L)).thenReturn(true);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(sessionContextService.findBySessionId(17L))
        .thenReturn(
            Optional.of(new SessionContext(17L, 9L, 3L, "demo", 44L, "char", 99L, "room", "jwt")));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            Mockito.mock(ScriptEventPublisher.class));

    service.enqueue("17", "LOGIN demo@example.com swordfish", false);

    String logs = output.getOut() + output.getErr();
    assertTrue(logs.contains("LOGIN [redacted]"));
    assertTrue(!logs.contains("LOGIN demo@example.com swordfish"));
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    verify(commandRepository, times(3)).save(commandCaptor.capture());
    assertEquals("LOGIN [redacted]", commandCaptor.getAllValues().get(0).getSanitizedCommandText());
  }

  @Test
  void queueValidationFailureMarksPersistedCommandFailed() {
    TickService tickService = Mockito.mock(TickService.class);
    SessionRateLimiter rateLimiter = Mockito.mock(SessionRateLimiter.class);
    Mockito.when(rateLimiter.allow(17L)).thenReturn(true);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
    Mockito.when(sessionContextService.findBySessionId(17L))
        .thenReturn(
            Optional.of(new SessionContext(17L, 9L, 3L, "demo", 44L, "char", 99L, "room", "jwt")));
    GameplayCommandRepository commandRepository = commandRepositorySavingArgument();
    Mockito.doThrow(new IllegalArgumentException("bad command"))
        .when(tickService)
        .enqueueCommand(
            Mockito.eq(9L),
            Mockito.eq(99L),
            Mockito.anyString(),
            Mockito.eq("look"),
            Mockito.eq(false));
    CommandServiceImpl service =
        new CommandServiceImpl(
            tickService,
            rateLimiter,
            repository,
            commandRepository,
            sessionContextService,
            Mockito.mock(ScriptEventPublisher.class));

    CommandEnqueueResult result = service.enqueue("17", "look", false);

    assertTrue(!result.accepted());
    assertTrue(result.commandId().startsWith("cmd-"));
    assertEquals("INVALID_ARGUMENT", result.errorCode());
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    verify(commandRepository, times(3)).save(commandCaptor.capture());
    GameplayCommand failed = commandCaptor.getAllValues().get(2);
    assertEquals(result.commandId(), failed.getCommandId());
    assertEquals("FAILED", failed.getExecutionOutcome());
    assertEquals("NOT_APPLIED", failed.getGameplayResult());
    assertEquals("INVALID_ARGUMENT", failed.getFailureCode());
  }

  private GameplayCommandRepository commandRepositorySavingArgument() {
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    AtomicLong idSequence = new AtomicLong();
    Mockito.when(repository.save(Mockito.any(GameplayCommand.class)))
        .thenAnswer(
            invocation -> {
              GameplayCommand command = invocation.getArgument(0);
              if (command.getId() == null) {
                command.setId(idSequence.incrementAndGet());
              }
              return command;
            });
    return repository;
  }
}
