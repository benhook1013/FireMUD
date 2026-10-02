package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.RetainedRuntimeTenantUuidResolver;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.serializer.SerializationException;

class PlayCommandHandlerTest {
  private static final String PLAY_COMMAND_NAME = "PLAY";
  private static final String PLAYER_ACCOUNT_ID = "f2ed193b-12c1-4c96-bcad-c162229af440";
  private static final String CANONICAL_TENANT_UUID = "7c958a3d-401e-47ee-8df8-351988b6ce26";
  private static final String FIXTURE_LEGACY_TENANT_UUID = "00000000-0000-0000-0000-000000000022";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService =
      Mockito.mock(SessionRoutingNormalizationService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final RetainedRuntimeTenantUuidResolver retainedRuntimeTenantUuidResolver =
      Mockito.mock(RetainedRuntimeTenantUuidResolver.class);
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final ModerationPolicyClient moderationPolicyClient =
      Mockito.mock(ModerationPolicyClient.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameplayWorldCatalog worldCatalog =
      TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private PlayCommandHandler handler;

  @BeforeEach
  void setUp() {
    gameplayCatalogProperties.setWorlds(
        List.of(
            world(
                "demo",
                "Demo World",
                List.of(realm("production", "Live Realm", 22L, 1L, true, false))),
            world(
                "sandbox",
                "Builder Sandbox",
                List.of(
                    realm("production", "Live Realm", 22L, 2L, true, true),
                    realm("preview", "Preview Realm", 22L, 41L, true, true)))));
    handler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            retainedRuntimeTenantUuidResolver,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    when(retainedRuntimeTenantUuidResolver.resolveCanonicalTenantId(22L))
        .thenReturn(Optional.of(UUID.fromString(CANONICAL_TENANT_UUID)));
    when(moderationPolicyClient.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyString()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
                .build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .active(PLAYER_ACCOUNT_ID, 22L, "1")),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grantResponse(true));
    when(sessionRoutingNormalizationService.normalizeProjectedContext(
            Mockito.any(SessionContext.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(firstPartyConnectContextRegistry.find(Mockito.anyLong())).thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class)))
        .thenAnswer(
            invocation -> {
              String instanceId = invocation.getArgument(2);
              PlayableStateScope scope = invocation.getArgument(3);
              String name = "41".equals(instanceId) ? "Emberline" : "demo";
              return roster(actor("123", name, scope));
            });
  }

  @Test
  void playForwardsResolvedUuidAndKeepsCatalogKeyForLocalRosterLookup() {
    SessionContext context =
        new SessionContext(
            1L, 93L, PLAYER_ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("123", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    var membershipCaptor =
        org.mockito.ArgumentCaptor.forClass(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.class);
    Mockito.verify(accountClient).getTenantMembershipForRuntime(membershipCaptor.capture());
    assertThat(membershipCaptor.getValue().getTenantId()).isEqualTo(CANONICAL_TENANT_UUID);
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq(CANONICAL_TENANT_UUID), Mockito.anyString());
    Mockito.verify(retainedRuntimeTenantUuidResolver).resolveCanonicalTenantId(22L);
    Mockito.verify(retainedRuntimeTenantUuidResolver, never()).resolveCanonicalTenantId(93L);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount(
            "22", PLAYER_ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(sessionContextService).save(Mockito.argThat(saved -> saved.tenantId() == 22L));
  }

  @Test
  void missingRetainedUuidAssociationDeniesBeforeAccountOrGameplayAccess() {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(retainedRuntimeTenantUuidResolver.resolveCanonicalTenantId(22L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, sessionContextService);
    Mockito.verifyNoInteractions(
        gameplayPresenceLifecycleService, moderationPolicyClient, scriptEventPublisher);
  }

  @Test
  void malformedRetainedUuidAssociationDeniesBeforeAccountOrGameplayAccess() {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(retainedRuntimeTenantUuidResolver.resolveCanonicalTenantId(22L))
        .thenReturn(Optional.of(new UUID(0L, 0L)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, sessionContextService);
    Mockito.verifyNoInteractions(
        gameplayPresenceLifecycleService, moderationPolicyClient, scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(strings = {"22", "6c118f15-ceda-4f7e-bf9f-1c342cb83962"})
  void numericOrWrongTenantEchoDeniesBeforeRosterOrBinding(String responseTenantId) {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(PLAYER_ACCOUNT_ID, 22L, "1"));
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder().setTenantId(responseTenantId).build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
    Mockito.verifyNoInteractions(gameplayPresenceLifecycleService, scriptEventPublisher);
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "play demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService)
        .registerConnected(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"),
            command("play-command:1:1:7001:1", PLAY_COMMAND_NAME, "play demo"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:1:7001:1");
  }

  @Test
  void playRejectsModerationPolicyDeniedAdmission() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(moderationPolicyClient.evaluateGameplayAdmission(22L, PLAYER_ACCOUNT_ID))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(false)
                .setAction("gameplay_ban")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("MODERATION_POLICY_DENIED");
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .registerConnected(Mockito.any());
  }

  @Test
  void playUsesResolvedEntityManagementCharacterIdWhenNameExists() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "2",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("9007", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 2L, 9007L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"),
            command(
                "play-command:1:2:9007:1", PLAY_COMMAND_NAME, "PLAY sandbox production Emberline"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:2:9007:1");
  }

  @Test
  void playRejectsMalformedResolvedCharacterId() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "2",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("abc", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_MESSAGE);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void playRejectsMissingOwnedActorWithoutSynthesizingIdentity() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster("1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
    Mockito.verify(entityManagementClient, never())
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
  }

  @Test
  void playRejectsAmbiguousOwnedRosterWhenActorIsNotSelected() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playRejectsAnotherAccountsActorEvenWhenNameMatches() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Character foreign =
        actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED).toBuilder()
            .setAccountId("6ba7b810-9dad-41d1-80b4-00c04fd430c8")
            .build();
    stubOwnedRoster("2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, foreign);

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "scope"})
  void playRejectsRosterRowsOutsideSelectedTenantOrScope(String mismatch) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Character valid = actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Character mismatched =
        "tenant".equals(mismatch)
            ? valid.toBuilder().setTenantId("23").build()
            : valid.toBuilder()
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                .build();
    stubOwnedRoster("1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, mismatched);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playRejectsStaleRetainedActorWhenSelectedRosterNoLongerContainsIt() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            "R-7",
            "jwt-token",
            null,
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void playRejectsEntityRosterErrorWithoutBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Mockito.doReturn(
            ListCharactersByAccountResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build())
        .when(entityManagementClient)
        .listCharactersByAccount(
            "22", PLAYER_ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playReadsEntitlementMembershipAndGrantBeforeActorRoster() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "41",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    org.mockito.InOrder order = Mockito.inOrder(accountClient, entityManagementClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq(CANONICAL_TENANT_UUID), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    order
        .verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(PLAYER_ACCOUNT_ID),
            Mockito.eq(CANONICAL_TENANT_UUID),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
    order
        .verify(entityManagementClient)
        .listCharactersByAccount(
            "22", PLAYER_ACCOUNT_ID, "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void playResumeDoesNotPublishSpawnEvent() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            7001L,
            "demo",
            1L,
            "R-7",
            "jwt-token",
            null,
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isTrue();
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            context, command("play-command:1:1:7001:1", PLAY_COMMAND_NAME, "PLAY demo"));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @Test
  void playIgnoresStaleExistingBindingAfterNormalization() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    SessionContext clearedExisting =
        new SessionContext(
            9L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            null,
            "old-jwt",
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.of(clearedExisting));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .recordDisconnected(Mockito.eq(9L), Mockito.any());
    Mockito.verify(sessionContextService, never()).deleteBySessionId(22L, 9L);
    Mockito.verify(sessionAuthenticationService).resolveByGameplayIdentity(22L, 1L, 7001L);
  }

  @Test
  void playIgnoresExistingBindingWithoutRoomRegion() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    SessionContext existingWithoutRoom =
        new SessionContext(
            9L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            7001L,
            "demo",
            1L,
            null,
            "old-jwt",
            null,
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.of(existingWithoutRoom));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .recordDisconnected(Mockito.eq(9L), Mockito.any());
    Mockito.verify(sessionContextService, never()).deleteBySessionId(22L, 9L);
  }

  @Test
  void firstPartyPlayRejectsMismatchedConnectScope() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            41L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "demo",
                    "production",
                    41L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Sora"),
                "PLAY sandbox preview Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsMismatchedWorldSlug() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "sandbox",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsStalePointerVersion() {
    gameplayCatalogProperties.getWorlds().get(0).getRealms().get(0).setPointerVersion(9L);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "demo",
                    "production",
                    1L,
                    8L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingRealmSlug() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "demo",
                    null,
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    assertThat(joinedOutputText(result.outputs()))
        .isEqualTo("ERROR CONNECT_CONTEXT_INVALID Connect context invalid");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void firstPartyPlayRejectsUnreadableRegistryContextWithoutPersistedFallback(
      boolean serializationFailure) {
    SessionContext persisted =
        new SessionContext(
            1L,
            22L,
            PLAYER_ACCOUNT_ID,
            null,
            0L,
            null,
            0L,
            null,
            null,
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null,
            "persisted-scope",
            "persisted-request");
    when(sessionAuthenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(persisted));
    RuntimeException decodeFailure =
        serializationFailure
            ? new SerializationException("legacy numeric Account carrier")
            : new ClassCastException("legacy numeric Account carrier");
    when(firstPartyConnectContextRegistry.find(1L)).thenThrow(decodeFailure);

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    assertThat(joinedOutputText(result.outputs()))
        .isEqualTo("ERROR CONNECT_CONTEXT_INVALID Connect context invalid");
    Mockito.verify(accountClient, Mockito.never()).getTenantMembershipForRuntime(Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(entityManagementClient, Mockito.never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, Mockito.never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingConnectScope() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "demo",
                    "production",
                    1L,
                    1L,
                    "",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingConnectRequest() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "demo",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
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
    SessionContext context =
        new SessionContext(1L, 22L, "f2ed193b-12c1-4c96-bcad-c162229af440", 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of(), PLAY_COMMAND_NAME));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void unknownWorldReturnsSelectionGuidance() {
    SessionContext context =
        new SessionContext(1L, 22L, "f2ed193b-12c1-4c96-bcad-c162229af440", 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("unknown"), "PLAY unknown"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
  }

  @Test
  void sandboxWithoutCharacterReturnsSelectionRequired() {
    SessionContext context =
        new SessionContext(1L, 22L, "f2ed193b-12c1-4c96-bcad-c162229af440", 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("sandbox"), "PLAY sandbox"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(
            "Selection required. Use PLAY sandbox <realm> [character] or browse REALMS first.");
    assertThat(
            new TextPlayerOutputRenderer(new PresentationProperties())
                .render(result.outputs().get(0), "fr"))
        .isEqualTo(
            "ERROR PLAY_SELECTION_REQUIRED Sélection requise. Utilisez PLAY sandbox <realm> [character] ou consultez REALMS d’abord.");
  }

  @Test
  void explicitRealmWithoutCharacterReturnsCharacterSelectionGuidance() {
    SessionContext context =
        new SessionContext(1L, 22L, "f2ed193b-12c1-4c96-bcad-c162229af440", 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("sandbox", "preview"), "PLAY sandbox preview"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(
            "Selection required. Use PLAY sandbox preview <character> or browse CHARS sandbox preview first.");
  }

  @Test
  void playRejectsIsolatedStateRealm() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.COPIED_ONLY);
    SessionContext context =
        new SessionContext(1L, 22L, "f2ed193b-12c1-4c96-bcad-c162229af440", 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void firstPartyPlayAcceptsNonProductionRealmWhenScopeMatches() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            41L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    PLAYER_ACCOUNT_ID,
                    22L,
                    "sandbox",
                    "preview",
                    41L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));
    stubOwnedRoster(
        "41",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 41L, 7002L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Sora"),
                "PLAY sandbox preview Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void firstPartyPlayFallsBackToPersistedSelectorWhenRegistryEntryIsMissing() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            "scope-persisted",
            "req-persisted");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "41",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 41L, 7002L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(TextCommandType.PLAY, List.of("sandbox", "Sora"), "PLAY sandbox Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void firstPartyPlayRejectsIncompletePersistedSelectorWhenRegistryEntryIsMissing() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            "scope-persisted",
            null);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(TextCommandType.PLAY, List.of("sandbox", "Sora"), "PLAY sandbox Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
  }

  @Test
  void activePublicMembershipDeniedByPolicyReturnsWorldAccessDeniedWithoutBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                                net.firedevops.firemud.gamesession.support
                                    .RuntimeMembershipTestFixtures.active(
                                    "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "2"))
                            .toBuilder()
                            .setGameplayAdmissionAllowed(false)
                            .build(),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playNonPublicRealmRequiresExistingAdmissibleMembership() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.NON_PUBLIC_ENROLLMENT_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.non-public-enrollment-required");
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playNonPublicLeftMembershipRequiresEnrollment() {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, List.of("designer", "player")));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.NON_PUBLIC_ENROLLMENT_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.non-public-enrollment-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        moderationPolicyClient,
        scriptEventPublisher);
  }

  @Test
  void playBoundNonPublicLeftMembershipRequiresEnrollmentAndClearsBinding() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, List.of("designer", "player")));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.NON_PUBLIC_ENROLLMENT_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.non-public-enrollment-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "non_public_enrollment_required");
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.characterId() == 0L
                        && saved.characterName() == null
                        && saved.gameInstanceId() == 0L
                        && saved.roomInstanceId() == null));
    Mockito.verifyNoInteractions(
        entityManagementClient, moderationPolicyClient, scriptEventPublisher);
  }

  @Test
  void playInvisibleRealmWithGrantedAccessContinuesAdmission() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grantResponse(true));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(PLAYER_ACCOUNT_ID),
            Mockito.eq(CANONICAL_TENANT_UUID),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playVisibleNonPublicRealmRequiresGrantedAccess() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grantResponse(false));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.realm-access-denied");
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.characterId() == 0L
                        && saved.gameInstanceId() == 0L
                        && saved.roomInstanceId() == null));
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(PLAYER_ACCOUNT_ID),
            Mockito.eq(CANONICAL_TENANT_UUID),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playNonPublicRealmDoesNotConsumePublicJoinPolicy() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(PLAYER_ACCOUNT_ID),
            Mockito.eq(CANONICAL_TENANT_UUID),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playInvisibleRealmWithDeniedGrantClearsBinding() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grantResponse(false));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "realm_access_denied");
  }

  @Test
  void playInvisibleRealmWithNonAuthorityGrantClearsBinding() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            GetRealmAccessGrantForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("PERMISSION_DENIED")
                        .setMessage("grant denied")
                        .build())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "realm_access_denied");
  }

  @ParameterizedTest
  @ValueSource(strings = {"AUTH_UNAVAILABLE", "FAILED_PRECONDITION"})
  void playInvisibleRealmWithUnavailableGrantFailsClosed(String errorCode) {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            GetRealmAccessGrantForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(errorCode)
                        .setMessage(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE)
                        .build())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "PERMISSION_DENIED"})
  void playNonAuthorityMembershipErrorReturnsAccessDeniedAndClearsBinding(String errorCode) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(errorCode)
                        .setMessage("membership failure")
                        .build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "access_denied");
  }

  @Test
  void playRequiresExplicitJoinWhenPublicProductionMembershipIsMissing() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));
    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ACTIVE", "UNKNOWN"})
  void malformedMembershipLifecycleCannotBecomeJoinOpportunity(String lifecycleState) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L));
              var malformedLifecycle =
                  response.toBuilder()
                      .setMembershipLifecycleState(lifecycleState)
                      .setMembershipBaseline(
                          response.getMembershipBaseline().toBuilder()
                              .setMembershipLifecycleState(lifecycleState))
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(malformedLifecycle, invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE"})
  void nonAdmittingMembershipLifecycleWithAdmissionFlagIsDenied(String lifecycleState) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  "INACTIVE".equals(lifecycleState)
                      ? freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .inactive("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "3"))
                      : freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L));
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder().setGameplayAdmissionAllowed(true).build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playLeftMembershipRequiresJoinForPublicProduction() {
    SessionContext context = unboundContext();
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, List.of("designer", "player")));
    var leftEvent =
        MembershipAuthorityEventV1Codec.verify(
            leftMembership.getOutboxSourceEvidence(0).getCanonicalEventJson());
    assertThat(leftMembership.getMembershipExists()).isTrue();
    assertThat(leftMembership.getGameplayAdmissionAllowed()).isFalse();
    assertThat(leftMembership.getMembershipLifecycleState()).isEqualTo("INACTIVE");
    assertThat(leftMembership.getMembershipVersionMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "3"));
    assertThat(leftMembership.getMembershipAuthorityGeneration()).isEqualTo("2");
    assertThat(leftMembership.getIssuanceFence()).isEqualTo("2");
    assertThat(leftMembership.getRolesList()).containsExactly("designer", "player");
    assertThat(leftMembership.getAuthorityTuple().getMembershipAuthorityGenerationMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "2"));
    assertThat(leftMembership.getOutboxCheckpointsList())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.getOutboxStreamKey()).isEqualTo(leftEvent.outboxStreamKey());
              assertThat(checkpoint.getOutboxSequence()).isEqualTo("2");
            });
    assertThat(leftMembership.getOutboxSourceEvidence(0).getEventId())
        .isEqualTo(leftEvent.eventId());
    assertThat(leftMembership.getOutboxSourceEvidence(0).getEventDigest())
        .isEqualTo(leftEvent.eventDigest());
    assertThat(leftMembership.getOutboxSourceEvidence(0).getCanonicalEventJson())
        .isEqualTo(leftEvent.canonicalJson());
    assertThat(leftEvent.outboxSequence()).isEqualTo("2");
    assertThat(leftEvent.membershipVersion())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "3"));
    assertThat(leftEvent.membershipAuthorityGeneration()).isEqualTo("2");
    assertThat(leftEvent.issuanceFence()).isEqualTo("2");
    assertThat(leftEvent.callerBoundAuthorityInvalidated()).isTrue();
    assertThat(leftEvent.roles()).containsExactly("designer", "player");
    assertThat(leftEvent.gameplayAdmissionAllowed()).isFalse();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        moderationPolicyClient,
        scriptEventPublisher);
  }

  @Test
  void playBoundLeftMembershipRequiresJoinAndClearsBindingForPublicProduction() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, List.of("designer", "player")));
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.characterId() == 0L
                        && saved.characterName() == null
                        && saved.gameInstanceId() == 0L
                        && saved.roomInstanceId() == null));
    Mockito.verifyNoInteractions(
        entityManagementClient, moderationPolicyClient, scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "membership-generation",
        "issuance-fence",
        "zero-sequence",
        "removed-event",
        "altered-roles",
        "admission-allowed"
      })
  void contradictoryLeftEventEvidenceCannotAdmitGameplay(String defect) {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(leftMembershipWithDefect(defect)),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).payload()).isInstanceOf(ErrorOutput.class);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        moderationPolicyClient,
        scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ENTITLEMENT_UNAVAILABLE", "AUTH_UNAVAILABLE"})
  void playEntitlementFailureMasksMissingPublicMembership(String errorCode) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode(errorCode).build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(
            "ENTITLEMENT_UNAVAILABLE".equals(errorCode)
                ? GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE
                : GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq(CANONICAL_TENANT_UUID), Mockito.anyString());
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playBillingDenialMasksMissingPublicMembership() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(false)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE);
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playPublicPolicyDenialMasksMissingMembershipWithoutBindingOrEntityCalls() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.public-production-admission-denied");
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playExistingPublicMemberCanEnterWhenNewPublicJoinsAreDisabled() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(sessionContextService).save(Mockito.any(SessionContext.class));
  }

  @Test
  void playReturnsJoinRequiredAfterFreshPublicPolicyAllowsJoinWithoutBindingOrEntityCalls() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq(CANONICAL_TENANT_UUID), Mockito.anyString());
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "future", "stale"})
  void playWhenMembershipAuthorityTimestampIsUnsafeFailsClosed(String defect) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1"))
                      .toBuilder()
                      .setEvaluatedAt(
                          switch (defect) {
                            case "future" -> Instant.now().plusSeconds(1L).toString();
                            case "stale" -> Instant.now().minus(16L, ChronoUnit.SECONDS).toString();
                            default -> "not-a-timestamp";
                          })
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(response, invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void playWhenMembershipRequestIdEchoDoesNotMatchFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          freshMembership(
                              net.firedevops.firemud.gamesession.support
                                  .RuntimeMembershipTestFixtures.active(
                                  "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1")),
                          invocation.getArgument(0));
              return response.toBuilder().setRequestId("wrong-request-id").build();
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playWhenMembershipAuthorityGenerationIsMissingFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          freshMembership(
                              net.firedevops.firemud.gamesession.support
                                  .RuntimeMembershipTestFixtures.active(
                                  "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1")),
                          invocation.getArgument(0));
              return response.toBuilder().clearMembershipAuthorityGeneration().build();
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playWhenMembershipCanonicalEventChangesWithValidDigestFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1"));
              String changedCanonicalJson =
                  resealWithMembershipVersion(
                      response.getOutboxSourceEvidence(0).getCanonicalEventJson(), "2");
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      replaceCanonicalEvent(response, changedCanonicalJson, true),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService)
        .save(Mockito.argThat(saved -> saved.characterId() == 0L && saved.gameInstanceId() == 0L));
  }

  @Test
  void playWhenMembershipCanonicalEventDigestDoesNotMatchFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1"));
              String changedCanonicalJson =
                  changeCanonicalEventWithoutResealing(
                      response.getOutboxSourceEvidence(0).getCanonicalEventJson());
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      replaceCanonicalEvent(response, changedCanonicalJson, false),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService)
        .save(Mockito.argThat(saved -> saved.characterId() == 0L && saved.gameInstanceId() == 0L));
  }

  @Test
  void playWhenMembershipCanonicalEventContentIsAbsentFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1"));
              var source =
                  response.getOutboxSourceEvidence(0).toBuilder().clearCanonicalEventJson();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder()
                          .clearOutboxSourceEvidence()
                          .addOutboxSourceEvidence(source)
                          .build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService)
        .save(Mockito.argThat(saved -> saved.characterId() == 0L && saved.gameInstanceId() == 0L));
  }

  @Test
  void playWhenMembershipResponseDiffersFromCanonicalEventFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1"));
              var mismatchedResponse =
                  response.toBuilder()
                      .putMembershipVersion("00000000-0000-0000-0000-000000000022", "2")
                      .setMembershipBaseline(
                          response.getMembershipBaseline().toBuilder()
                              .putMembershipVersion("00000000-0000-0000-0000-000000000022", "2"))
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(mismatchedResponse, invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService)
        .save(Mockito.argThat(saved -> saved.characterId() == 0L && saved.gameInstanceId() == 0L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"target", "version", "stale", "future", "malformed"})
  void unsafeEntitlementSnapshotFailsClosed(String defect) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            0L,
            null,
            0L,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    String tenantId = "target".equals(defect) ? "23" : "22";
    long entitlementVersion = "version".equals(defect) ? 0L : 1L;
    String evaluatedAt =
        switch (defect) {
          case "stale" -> Instant.now().minus(16L, ChronoUnit.SECONDS).toString();
          case "future" -> Instant.now().plus(1L, ChronoUnit.SECONDS).toString();
          case "malformed" -> "not-a-timestamp";
          default -> Instant.now().toString();
        };
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(tenantId)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(entitlementVersion)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(evaluatedAt)
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playBlockedByEntitlementsReturnsBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(false)
                .setEntitlementVersion(5L)
                .setTenantBillingSequence(5L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("TENANT_BILLING_BLOCKED");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "tenant_unavailable")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "tenant_unavailable");
  }

  @Test
  void playMapsNonAuthorityEntitlementFailureToBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("TENANT_BILLING_BLOCKED")
                        .setMessage("Tenant billing blocks gameplay"))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.billing-blocked");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "tenant_unavailable")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "tenant_unavailable");
  }

  @ParameterizedTest
  @ValueSource(strings = {"AUTH_UNAVAILABLE", "FAILED_PRECONDITION"})
  void playWhenMembershipAuthorityUnavailableFailsClosed(String errorCode) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(errorCode)
                        .setMessage(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    assertThat(meterRegistry.find("gamesession.session.resume_denied").counters()).isEmpty();
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
  }

  @ParameterizedTest
  @CsvSource({
    "ACTIVE,ABSENT",
    "ACTIVE,UNKNOWN",
    "ACTIVE,UNAVAILABLE",
    "MISSING,ABSENT",
    "MISSING,UNKNOWN",
    "MISSING,UNAVAILABLE",
    "INACTIVE,ABSENT",
    "INACTIVE,UNKNOWN",
    "INACTIVE,UNAVAILABLE"
  })
  void playRequiresExplicitAvailableMembershipAuthorityBeforePublicOutcomes(
      String lifecycle, String availability) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var membership =
                  switch (lifecycle) {
                    case "ACTIVE" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .active("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "1");
                    case "MISSING" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L);
                    case "INACTIVE" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .inactive("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "3");
                    default ->
                        throw new IllegalArgumentException("Unexpected lifecycle: " + lifecycle);
                  };
              var builder = freshMembership(membership).toBuilder();
              if (!"ABSENT".equals(availability)) {
                builder.setAuthorityAvailability(availability);
              } else {
                builder.clearAuthorityAvailability();
              }
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(builder.build(), invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,ABSENT",
    "MISSING,UNKNOWN",
    "MISSING,UNAVAILABLE",
    "INACTIVE,ABSENT",
    "INACTIVE,UNKNOWN",
    "INACTIVE,UNAVAILABLE"
  })
  void playRequiresExplicitAvailableMembershipAuthorityBeforeNonPublicOutcome(
      String lifecycle, String availability) {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var membership =
                  "MISSING".equals(lifecycle)
                      ? net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .missing("f2ed193b-12c1-4c96-bcad-c162229af440", 22L)
                      : net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .inactive("f2ed193b-12c1-4c96-bcad-c162229af440", 22L, "3");
              var builder = freshMembership(membership).toBuilder();
              if (!"ABSENT".equals(availability)) {
                builder.setAuthorityAvailability(availability);
              } else {
                builder.clearAuthorityAvailability();
              }
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(builder.build(), invocation.getArgument(0));
            });

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void playWhenEntitlementAuthorityUnavailablePreservesExistingBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE)
                        .setMessage(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.entitlement-unavailable");
    assertThat(meterRegistry.find("gamesession.session.resume_denied").counters()).isEmpty();
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
  }

  @Test
  void playWhenEntitlementRuntimeReadIsDisabledPreservesExistingBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            "R-1",
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
  }

  @Test
  void staleRoomContextFallsBackToFreshEntry() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            "f2ed193b-12c1-4c96-bcad-c162229af440",
            "demo@example.com",
            123L,
            "demo",
            1L,
            null,
            "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 123L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                123L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"));
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.fresh_entry_fallback",
                    "reason",
                    "stale_or_missing_context")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                123L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"),
            command("play-command:1:1:123:1", PLAY_COMMAND_NAME, "PLAY demo"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                "f2ed193b-12c1-4c96-bcad-c162229af440",
                "demo@example.com",
                123L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:1:123:1");
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }

  private static GetTenantMembershipForRuntimeResponse replaceCanonicalEvent(
      GetTenantMembershipForRuntimeResponse response,
      String canonicalJson,
      boolean synchronizeSourceMetadata) {
    var source =
        response.getOutboxSourceEvidence(0).toBuilder().setCanonicalEventJson(canonicalJson);
    if (synchronizeSourceMetadata) {
      var event = MembershipAuthorityEventV1Codec.verify(canonicalJson);
      source
          .setOutboxStreamKey(event.outboxStreamKey())
          .setOutboxSequence(event.outboxSequence())
          .setEventId(event.eventId())
          .setEventDigest(event.eventDigest());
    }
    return response.toBuilder().clearOutboxSourceEvidence().addOutboxSourceEvidence(source).build();
  }

  private static String resealWithMembershipVersion(
      String canonicalJson, String membershipVersion) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      ((ObjectNode) event.get("membershipVersion")).put(CANONICAL_TENANT_UUID, membershipVersion);
      event.remove("eventDigest");
      return MembershipAuthorityEventV1Codec.seal(
              JSON.convertValue(event, new TypeReference<Map<String, Object>>() {}))
          .canonicalJson();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static String changeCanonicalEventWithoutResealing(String canonicalJson) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      event.put("requestId", "tampered-membership-request");
      return event.toString();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static GetTenantMembershipForRuntimeResponse freshMembership(
      GetTenantMembershipForRuntimeResponse response) {
    String originalTenantId = response.getTenantId();
    var builder =
        response.toBuilder()
            .setTenantId(CANONICAL_TENANT_UUID)
            .setRequestTenantId(CANONICAL_TENANT_UUID)
            .setEvaluatedAt(Instant.now().toString());
    if (response.getMembershipVersionCount() > 0) {
      builder
          .clearMembershipVersion()
          .putAllMembershipVersion(rekeyMap(response.getMembershipVersionMap(), originalTenantId));
    }
    if (response.hasMembershipBaseline()) {
      builder.setMembershipBaseline(
          response.getMembershipBaseline().toBuilder()
              .clearMembershipVersion()
              .putAllMembershipVersion(
                  rekeyMap(
                      response.getMembershipBaseline().getMembershipVersionMap(), originalTenantId))
              .build());
    }
    if (response.hasAuthorityTuple()) {
      var tuple = response.getAuthorityTuple().toBuilder();
      tuple
          .clearTenantAuthorityGeneration()
          .putAllTenantAuthorityGeneration(
              rekeyMap(
                  response.getAuthorityTuple().getTenantAuthorityGenerationMap(),
                  originalTenantId));
      tuple
          .clearMembershipAuthorityGeneration()
          .putAllMembershipAuthorityGeneration(
              rekeyMap(
                  response.getAuthorityTuple().getMembershipAuthorityGenerationMap(),
                  originalTenantId));
      for (int index = 0;
          index < response.getAuthorityTuple().getPrivateRealmGrantVersionsCount();
          index++) {
        var grant = response.getAuthorityTuple().getPrivateRealmGrantVersions(index);
        if (originalTenantId.equals(grant.getTenantId())) {
          tuple.setPrivateRealmGrantVersions(
              index, grant.toBuilder().setTenantId(CANONICAL_TENANT_UUID).build());
        }
      }
      builder.setAuthorityTuple(tuple.build());
    }
    builder.clearOutboxCheckpoints();
    response
        .getOutboxCheckpointsList()
        .forEach(
            checkpoint ->
                builder.addOutboxCheckpoints(
                    checkpoint.toBuilder()
                        .setOutboxStreamKey(
                            rekeyPathTenant(checkpoint.getOutboxStreamKey(), originalTenantId))
                        .build()));
    builder.clearOutboxSourceEvidence();
    response
        .getOutboxSourceEvidenceList()
        .forEach(
            source -> {
              String canonicalEventJson =
                  rekeyMembershipEvent(
                      source.getCanonicalEventJson(), response.getAccountId(), originalTenantId);
              var event = MembershipAuthorityEventV1Codec.verify(canonicalEventJson);
              builder.addOutboxSourceEvidence(
                  source.toBuilder()
                      .setOutboxStreamKey(event.outboxStreamKey())
                      .setEventDigest(event.eventDigest())
                      .setCanonicalEventJson(event.canonicalJson())
                      .build());
            });
    return builder.build();
  }

  private static Map<String, String> rekeyMap(Map<String, String> values, String originalTenantId) {
    Map<String, String> result = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          String rekeyed = originalTenantId.equals(key) ? CANONICAL_TENANT_UUID : key;
          if (result.putIfAbsent(rekeyed, value) != null) {
            throw new IllegalArgumentException("Fixture tenant UUID rekey collides");
          }
        });
    return result;
  }

  private static String rekeyPathTenant(String value, String originalTenantId) {
    return value.replace("/" + originalTenantId, "/" + CANONICAL_TENANT_UUID);
  }

  private static String rekeyMembershipEvent(
      String canonicalJson, String accountId, String originalTenantId) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      event.put("tenantId", CANONICAL_TENANT_UUID);
      event.put(
          "outboxStreamKey",
          rekeyPathTenant(event.path("outboxStreamKey").asText(), originalTenantId));
      event.put("sourceScope", "membership/" + accountId + "/" + CANONICAL_TENANT_UUID);
      rekeyJsonMap((ObjectNode) event.get("membershipVersion"), originalTenantId);
      ObjectNode tuple = (ObjectNode) event.get("authorityTuple");
      rekeyJsonMap((ObjectNode) tuple.get("tenantAuthorityGeneration"), originalTenantId);
      rekeyJsonMap((ObjectNode) tuple.get("membershipAuthorityGeneration"), originalTenantId);
      if (tuple.has("privateRealmGrantVersions")) {
        for (var grant : tuple.withArray("privateRealmGrantVersions")) {
          if (grant instanceof ObjectNode grantObject
              && originalTenantId.equals(grantObject.path("tenantId").asText())) {
            grantObject.put("tenantId", CANONICAL_TENANT_UUID);
          }
        }
      }
      event.remove("eventDigest");
      return MembershipAuthorityEventV1Codec.seal(
              JSON.convertValue(event, new TypeReference<Map<String, Object>>() {}))
          .canonicalJson();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static void rekeyJsonMap(ObjectNode values, String originalTenantId) {
    if (values != null && values.has(originalTenantId)) {
      var value = values.remove(originalTenantId);
      values.set(CANONICAL_TENANT_UUID, value);
    }
  }

  private static GetTenantMembershipForRuntimeResponse leftMembershipWithDefect(String defect) {
    var response =
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
            "f2ed193b-12c1-4c96-bcad-c162229af440", 22L, List.of("designer", "player"));
    var builder = response.toBuilder();
    switch (defect) {
      case "membership-generation" -> {
        String tenantUuid = response.getTenantId();
        builder
            .setMembershipAuthorityGeneration("3")
            .setMembershipBaseline(
                response.getMembershipBaseline().toBuilder().setMembershipAuthorityGeneration("3"))
            .setAuthorityTuple(
                response.getAuthorityTuple().toBuilder()
                    .putMembershipAuthorityGeneration(tenantUuid, "3"));
      }
      case "issuance-fence" -> builder.setIssuanceFence("3");
      case "zero-sequence" -> {
        String membershipStream = response.getOutboxSourceEvidence(0).getOutboxStreamKey();
        boolean updated = false;
        for (int index = 0; index < response.getOutboxCheckpointsCount(); index++) {
          var checkpoint = response.getOutboxCheckpoints(index);
          if (checkpoint.getOutboxStreamKey().equals(membershipStream)) {
            builder.setOutboxCheckpoints(
                index, checkpoint.toBuilder().setOutboxSequence("0").build());
            updated = true;
            break;
          }
        }
        if (!updated) {
          throw new IllegalStateException("LEFT fixture is missing its membership checkpoint");
        }
      }
      case "removed-event" -> builder.clearOutboxSourceEvidence();
      case "altered-roles" -> builder.clearRoles().addRoles("player");
      case "admission-allowed" -> builder.setGameplayAdmissionAllowed(true);
      default -> throw new IllegalArgumentException("Unknown LEFT evidence defect: " + defect);
    }
    return builder.build();
  }

  private void assertIdentityUnavailableWithoutMutation(PlayCommandHandlingResult result) {
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  private void stubOwnedRoster(
      String gameInstanceId, PlayableStateScope scope, Character... characters) {
    Mockito.doReturn(roster(characters))
        .when(entityManagementClient)
        .listCharactersByAccount("22", PLAYER_ACCOUNT_ID, gameInstanceId, scope);
  }

  private static ListCharactersByAccountResponse roster(Character... characters) {
    return ListCharactersByAccountResponse.newBuilder()
        .addAllCharacters(List.of(characters))
        .build();
  }

  private static Character actor(String id, String name, PlayableStateScope scope) {
    return Character.newBuilder()
        .setId(id)
        .setTenantId("22")
        .setAccountId(PLAYER_ACCOUNT_ID)
        .setName(name)
        .setPlayableStateScope(scope)
        .build();
  }

  private static GetRealmAccessGrantForRuntimeResponse grantResponse(boolean granted) {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId(PLAYER_ACCOUNT_ID)
        .setTenantId(CANONICAL_TENANT_UUID)
        .setWorldSlug("sandbox")
        .setRealmSlug("preview")
        .setGranted(granted)
        .build();
  }

  private void markPreviewRealmInvisible() {
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(1).setVisible(false);
  }

  private SessionContext previewRealmContext() {
    return new SessionContext(
        1L,
        22L,
        "f2ed193b-12c1-4c96-bcad-c162229af440",
        "demo@example.com",
        123L,
        "Emberline",
        41L,
        "R-1",
        "jwt-token",
        null,
        41L,
        "sandbox",
        "preview",
        1L,
        "SHARED");
  }

  private SessionContext unboundContext() {
    return new SessionContext(
        1L,
        22L,
        "f2ed193b-12c1-4c96-bcad-c162229af440",
        "demo@example.com",
        0L,
        null,
        0L,
        null,
        "jwt-token",
        null,
        0L,
        null,
        null,
        0L,
        null);
  }

  private TextCommand previewRealmPlayCommand() {
    return new TextCommand(
        TextCommandType.PLAY,
        List.of("sandbox", "preview", "Emberline"),
        "PLAY sandbox preview Emberline");
  }

  private static GameplayCatalogProperties.World world(
      String slug, String displayName, List<GameplayCatalogProperties.Realm> realms) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(realms);
    return world;
  }

  private static GameplayCatalogProperties.Realm realm(
      String slug,
      String displayName,
      long tenantId,
      long gameInstanceId,
      boolean visible,
      boolean requiresCharacterSelection) {
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug(slug);
    realm.setDisplayName(displayName);
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(visible);
    realm.setPublicProductionRealm("production".equalsIgnoreCase(slug));
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }

  private static GameplayCommand command(String commandId, String commandName, String commandText) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    gameplayCommand.setCommandText(commandText);
    return gameplayCommand;
  }
}
