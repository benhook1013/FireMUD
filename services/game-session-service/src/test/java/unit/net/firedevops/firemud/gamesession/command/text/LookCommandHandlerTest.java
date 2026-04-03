package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionSettingsOverridesProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LookCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final EffectiveSettingsResolver settingsResolver = defaultSettingsResolver();
  private final LookCommandHandler handler =
      new LookCommandHandler(
          gameLogicClient,
          lookTextRenderer,
          sessionAuthenticationService,
          gameLogicProperties,
          settingsResolver,
          meterRegistry,
          lookCacheService,
          devIsolatedProperties);
  private final SessionContext sessionContext =
      new SessionContext(1L, 22L, 123L, 911L, 0L, "room-42", "jwt");
  private final LookResult lookResult =
      LookResult.newBuilder()
          .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
          .build();

  @BeforeEach
  void setUp() {
    when(sessionAuthenticationService.resolveSessionContext("123"))
        .thenReturn(Optional.of(sessionContext));
    when(sessionAuthenticationService.resolveSessionContext(
            String.valueOf(sessionContext.sessionId())))
        .thenReturn(Optional.of(sessionContext));
    when(gameLogicClient.resolveLook("22", "1", "911", "room-42", "")).thenReturn(lookResult);
    when(lookTextRenderer.render(
            eq(lookResult),
            eq(true),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .class)))
        .thenReturn("OK LOOK text");
    when(lookTextRenderer.render(
            eq(lookResult),
            eq(true),
            any(net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class),
            any(),
            any()))
        .thenReturn("OK LOOK text");
    when(lookTextRenderer.render(
            eq(lookResult),
            eq(false),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .class)))
        .thenReturn("OK QUICKLOOK text");
    when(lookTextRenderer.render(
            eq(lookResult),
            eq(false),
            any(net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class),
            any(),
            any()))
        .thenReturn("OK QUICKLOOK text");
    when(lookTextRenderer.toPlayerOutput(
            eq(lookResult),
            eq(false),
            any(net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class)))
        .thenReturn(PlayerOutput.view("OK QUICKLOOK text"));
  }

  @Test
  void returnsRendererOutput() {
    assertEquals("OK LOOK text", handler.describe("123"));
    Counter invocations =
        meterRegistry.get("gamesession.command.look.invocations").tag("tenantId", "22").counter();
    assertEquals(1.0, invocations.count());
  }

  @Test
  void mapsGrpcFailureToErrorResponse() {
    StatusRuntimeException worldDown =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("WorldManagement: down"));
    when(gameLogicClient.resolveLook("22", "1", "911", "room-42", "")).thenThrow(worldDown);
    String response = handler.describe("123");
    assertEquals("ERROR WORLD_UNAVAILABLE WorldManagement: down", response);
    Counter failures =
        meterRegistry
            .get("gamesession.command.look.failures")
            .tag("tenantId", "22")
            .tag("error", "WORLD_UNAVAILABLE")
            .counter();
    assertEquals(1.0, failures.count());
  }

  @Test
  void cachesRenderedLook() {
    handler.describe("123");
    verify(lookCacheService)
        .cache(eq(22L), eq(1L), eq("1021"), eq("OK LOOK text"), eq("OK LOOK\nOK LOOK text\n\n"));
  }

  @Test
  void quickLookUsesSharedLookupButShorterRenderVariant() {
    assertThat(handler.describePlayerOutput("123", false))
        .isEqualTo(PlayerOutput.view("OK QUICKLOOK text"));
    verify(lookTextRenderer)
        .toPlayerOutput(
            lookResult,
            false,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.QUICKLOOK,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                .PREFER_BRIEF);
    verify(lookCacheService)
        .cache(
            eq(22L),
            eq(1L),
            eq("1021"),
            eq("OK QUICKLOOK text"),
            eq("OK LOOK\nOK QUICKLOOK text\n\n"));
  }

  @Test
  void cachesRenderedLookByGameplayInstanceWhenAvailable() {
    SessionContext playedContext =
        new SessionContext(17L, 22L, 123L, "demo", 911L, "demo", 77L, "room-42", "jwt");
    when(sessionAuthenticationService.resolveSessionContext("played"))
        .thenReturn(Optional.of(playedContext));
    when(gameLogicClient.resolveLook("22", "17", "911", "room-42", "")).thenReturn(lookResult);

    handler.describe("played");

    verify(lookCacheService)
        .cache(eq(22L), eq(77L), eq("1021"), eq("OK LOOK text"), eq("OK LOOK\nOK LOOK text\n\n"));
  }

  @Test
  void cachedLookProxy() {
    when(lookCacheService.get(22L, 1L))
        .thenReturn(
            Optional.of(new LookCacheService.CachedLook("R-1021", "text", "OK LOOK\ntext\n\n", 0)));
    assertThat(handler.cachedLook("22", "1")).contains("OK LOOK\ntext\n\n");
  }

  @Test
  void cachedLookReplaysThenFallbacksToFreshLookWhenMissing() {
    when(lookCacheService.get(22L, 1L))
        .thenReturn(
            Optional.of(
                new LookCacheService.CachedLook(
                    "R-1021", "cached text", "OK LOOK\ncached text\n\n", 0)))
        .thenReturn(Optional.empty());
    assertThat(handler.cachedLook("22", "1")).contains("OK LOOK\ncached text\n\n");
    Mockito.verifyNoInteractions(gameLogicClient);
    Mockito.clearInvocations(gameLogicClient);
    when(lookTextRenderer.render(
            lookResult,
            true,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .EXPLICIT_LOOK,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput
                .defaultBriefRenderingHint(
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                        .EXPLICIT_LOOK,
                    true),
            sessionContext.localeTag(),
            settingsResolver.presentation(sessionContext)))
        .thenReturn("fresh text");
    assertEquals("fresh text", handler.describe("123"));
    verify(gameLogicClient).resolveLook("22", "1", "911", "room-42", "");
  }

  @Test
  void devIsolatedReturnsLegacyDescription() {
    LookCommandHandler devHandler =
        new LookCommandHandler(
            gameLogicClient,
            lookTextRenderer,
            sessionAuthenticationService,
            gameLogicProperties,
            settingsResolver,
            new SimpleMeterRegistry(),
            lookCacheService,
            new DevIsolatedProperties(true));
    String response = devHandler.describe("123");
    assertEquals(LookCommandConstants.ROOM_DESCRIPTION, response);
    Mockito.verifyNoInteractions(gameLogicClient, lookCacheService);
  }

  private static EffectiveSettingsResolver defaultSettingsResolver() {
    return new EffectiveSettingsResolver(
        new PresentationProperties(),
        new MovementProperties(),
        new WorldTopologyProperties(),
        new GameSessionSettingsOverridesProperties(null, null, null, null, null, null));
  }
}
