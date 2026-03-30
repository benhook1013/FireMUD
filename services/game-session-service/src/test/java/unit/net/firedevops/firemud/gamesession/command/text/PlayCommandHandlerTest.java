package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayCommandHandlerTest {
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameplayWorldCatalog worldCatalog =
      new GameplayWorldCatalog(new GameSessionProperties());
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private PlayCommandHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            meterRegistry);
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 123L)).thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.responseText()).isEqualTo("OK PLAY Entered world: demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                123L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token"));
  }

  @Test
  void playWithoutSessionReturnsLoginRequired() {
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void playWithoutArgumentsReturnsInvalidArgument() {
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of(), "PLAY"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void unknownWorldReturnsSelectionGuidance() {
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("unknown"), "PLAY unknown"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
  }

  @Test
  void sandboxWithoutCharacterReturnsSelectionRequired() {
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("sandbox"), "PLAY sandbox"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Selection required. Use PLAY sandbox <character> or browse CHARS first.");
  }

  @Test
  void playDeniedByMembershipReturnsWorldAccessDenied() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(2L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.resume_denied",
                    "tenantId",
                    "22",
                    "reason",
                    "access_denied")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void playBlockedByEntitlementsReturnsBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(5L)
                .setTenantBillingSequence(5L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("TENANT_BILLING_BLOCKED");
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.resume_denied",
                    "tenantId",
                    "22",
                    "reason",
                    "tenant_unavailable")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void playWhenMembershipAuthorityUnavailableFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("MEMBERSHIP_AUTH_UNAVAILABLE")
                        .setMessage("Membership authority unavailable"))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("MEMBERSHIP_AUTH_UNAVAILABLE");
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.resume_denied",
                    "tenantId",
                    "22",
                    "reason",
                    "authority_unavailable")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void staleRoomContextFallsBackToFreshEntry() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 123L)).thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.responseText()).isEqualTo("OK PLAY Entered world: demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                123L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token"));
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.fresh_entry_fallback",
                    "tenantId",
                    "22",
                    "reason",
                    "stale_or_missing_context")
                .count())
        .isEqualTo(1.0);
  }
}
