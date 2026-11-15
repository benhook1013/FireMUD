package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.test.GameplayCommandTestFixtures;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class PlayerOutputDeliveryServiceTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sendFailureRequestsOwnedCloseAndRetainsRegistrationUntilCallback(boolean closeFails)
      throws Exception {
    var registry = mock(ActiveTransportSessionRegistry.class);
    var settings = mock(EffectiveSettingsResolver.class);
    var projector = mock(WebSocketOutputProjector.class);
    var bursts = mock(PromptBurstCoordinator.class);
    var session = mock(WebSocketSession.class);
    var context = GameplayCommandTestFixtures.unnamedOutputActor();
    var output = PlayerOutput.notice("hello");
    var presentation = new PresentationProperties();
    when(registry.find(41L)).thenReturn(Optional.of(session));
    when(session.isOpen()).thenReturn(true);
    when(settings.presentation(context)).thenReturn(presentation);
    when(bursts.applyPromptWindow(
            anyString(), any(), any(), org.mockito.ArgumentMatchers.eq(false)))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(projector.projectPlayerOutput(session, output, null, presentation)).thenReturn("hello");
    doThrow(new IOException("send failed")).when(session).sendMessage(any());
    if (closeFails) {
      doThrow(new IOException("close failed")).when(session).close(any(CloseStatus.class));
    }
    var metrics = new SimpleMeterRegistry();
    try {
      var service =
          new PlayerOutputDeliveryService(
              registry,
              mock(ScreenBufferService.class),
              settings,
              projector,
              mock(PromptComposer.class),
              bursts,
              metrics);
      service.deliver(context, List.of(output), false);
      verify(session).close(CloseStatus.SERVER_ERROR.withReason("internal_error"));
      verify(registry, never()).unregister(anyLong(), any());
      assertThat(metrics.counter("gamesession.output.delivery.failed", "kind", "notice").count())
          .isEqualTo(1.0);
    } finally {
      metrics.close();
    }
  }
}
