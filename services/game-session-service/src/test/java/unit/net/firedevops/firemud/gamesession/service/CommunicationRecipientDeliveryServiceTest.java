package net.firedevops.firemud.gamesession.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.presentation.CommunicationOutputMapper;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommunicationRecipientDeliveryServiceTest {
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry =
      Mockito.mock(ActiveTransportSessionRegistry.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);
  private final TextPlayerOutputRenderer outputRenderer =
      Mockito.mock(TextPlayerOutputRenderer.class);
  private final CommunicationOutputMapper communicationOutputMapper =
      Mockito.mock(CommunicationOutputMapper.class);
  private final EffectiveSettingsResolver settingsResolver =
      Mockito.mock(EffectiveSettingsResolver.class);
  private final WebSocketOutputProjector outputProjector =
      Mockito.mock(WebSocketOutputProjector.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private CommunicationRecipientDeliveryService service;

  @BeforeEach
  void setUp() {
    service =
        new CommunicationRecipientDeliveryService(
            sessionAuthenticationService,
            activeTransportSessionRegistry,
            screenBufferService,
            outputRenderer,
            communicationOutputMapper,
            settingsResolver,
            outputProjector,
            meterRegistry);
  }

  @Test
  void deliverDropsStaleRecipientResolvedByGameplayIdentity() {
    SessionContext actor =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
    SessionContext clearedRecipient =
        new SessionContext(
            42L, 22L, 456L, "friend@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    CommunicationRecipientView view =
        CommunicationRecipientView.newBuilder()
            .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
            .setRecipientId("456")
            .build();
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setType(CommunicationType.TELL)
            .setMessage("hello")
            .addRecipientViews(view)
            .build();

    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 456L))
        .thenReturn(Optional.of(clearedRecipient));

    service.deliver(actor, response);

    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
    verify(activeTransportSessionRegistry, never()).find(any(Long.class));
  }

  @Test
  void deliverDropsStaleRecipientResolvedByGameplayName() {
    SessionContext actor =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
    SessionContext clearedRecipient =
        new SessionContext(
            42L, 22L, 456L, "friend@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    CommunicationRecipientView view =
        CommunicationRecipientView.newBuilder()
            .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
            .setRecipientName("Sora")
            .build();
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setType(CommunicationType.TELL)
            .setMessage("hello")
            .addRecipientViews(view)
            .build();

    when(sessionAuthenticationService.resolveByGameplayName(22L, 1L, "Sora"))
        .thenReturn(Optional.of(clearedRecipient));

    service.deliver(actor, response);

    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
    verify(activeTransportSessionRegistry, never()).find(any(Long.class));
  }

  @Test
  void deliverDoesNotFallbackToNameWhenRecipientIdIsMalformed() {
    SessionContext actor =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
    CommunicationRecipientView view =
        CommunicationRecipientView.newBuilder()
            .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
            .setRecipientId("bogus")
            .setRecipientName("Sora")
            .build();
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setType(CommunicationType.TELL)
            .setMessage("hello")
            .addRecipientViews(view)
            .build();

    service.deliver(actor, response);

    verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(anyLong(), anyLong(), anyLong());
    verify(sessionAuthenticationService, never()).resolveByGameplayName(22L, 1L, "Sora");
    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void deliverDoesNotFallbackToNameWhenRecipientIdIsNonPositive() {
    SessionContext actor =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
    CommunicationRecipientView view =
        CommunicationRecipientView.newBuilder()
            .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
            .setRecipientId("0")
            .setRecipientName("Sora")
            .build();
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setType(CommunicationType.TELL)
            .setMessage("hello")
            .addRecipientViews(view)
            .build();

    service.deliver(actor, response);

    verify(sessionAuthenticationService, never()).resolveByGameplayName(22L, 1L, "Sora");
    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void deliverDoesNotFallbackToNameWhenStructuredRecipientIdDoesNotResolve() {
    SessionContext actor =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 123L, "Emberline", 1L, "R-1", "jwt");
    CommunicationRecipientView view =
        CommunicationRecipientView.newBuilder()
            .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
            .setRecipientId("456")
            .setRecipientName("Sora")
            .build();
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setType(CommunicationType.TELL)
            .setMessage("hello")
            .addRecipientViews(view)
            .build();

    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 456L))
        .thenReturn(Optional.empty());

    service.deliver(actor, response);

    verify(sessionAuthenticationService).resolveByGameplayIdentity(22L, 1L, 456L);
    verify(sessionAuthenticationService, never()).resolveByGameplayName(22L, 1L, "Sora");
    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }
}
