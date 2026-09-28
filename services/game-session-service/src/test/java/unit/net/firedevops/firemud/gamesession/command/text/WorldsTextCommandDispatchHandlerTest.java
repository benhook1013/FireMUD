package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsTextCommandDispatchHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final WorldsTextCommandDispatchHandler handler =
      new WorldsTextCommandDispatchHandler(
          new WorldsCommandHandler(
              TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
              entityManagementClient),
          scriptEventPublisher);

  @Test
  void publishesCommandEventForGameplayScopedWorldsBrowse() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(WorldsViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "WORLDS".equals(gameplayCommand.getCommandName())
                        && "WORLDS".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("worlds-")));
  }

  @Test
  void publishesCommandEventForGameplayScopedRealmsBrowse() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("sandbox", 1L, 2L, false), world("authority", 1L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("41")
                .setTenantId("1")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            GetRealmAccessGrantForRuntimeResponse.newBuilder()
                .setAccountId("41")
                .setTenantId("1")
                .setWorldSlug("sandbox")
                .setRealmSlug("production")
                .setGranted(true)
                .setGrantVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("1")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                entityManagementClient,
                accountClient,
                new DirectTextConnectScopeSessionStore()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.REALMS, List.of("sandbox"), "REALMS sandbox"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(RealmBrowseViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "REALMS".equals(gameplayCommand.getCommandName())
                        && "REALMS sandbox".equals(gameplayCommand.getCommandText())));
  }

  @Test
  void publishesCommandEventForGameplayScopedCharsBrowse() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                        .setName("Emberline")
                        .setLevel(12)
                        .build())
                .build());
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                entityManagementClient,
                accountClient,
                new DirectTextConnectScopeSessionStore()),
            scriptEventPublisher);
    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.CHARS, List.of("demo"), "CHARS demo"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(CharacterBrowseViewOutput.class);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "CHARS".equals(gameplayCommand.getCommandName())
                        && "CHARS demo".equals(gameplayCommand.getCommandText())));
  }

  @Test
  void routesJoinRequiredCharsFailureWithoutPublishingSuccessEventOrReadingEntity() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setMembershipLifecycleState("MISSING")
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                entityManagementClient,
                accountClient,
                new DirectTextConnectScopeSessionStore()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.CHARS, List.of("demo", "production"), "CHARS demo production"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("JOIN_REQUIRED");
    assertThat(result.outputs()).singleElement().extracting(output -> output.payload()).isNotNull();
    Mockito.verifyNoInteractions(entityManagementClient, scriptEventPublisher);
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void realMsRetainsAccountScopeAndJoinSendsTheBoundContextAndServerRequestId() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore = new DirectTextConnectScopeSessionStore();
    UUID realmId = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
    UUID namespaceId = UUID.fromString("42d234a2-7487-4dda-a7e5-a3831214328e");
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo-world",
                    "Demo World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            22L,
                            9L,
                            4L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW",
                            7L,
                            realmId,
                            namespaceId)))));
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(catalog, entityManagementClient, accountClient, scopeStore),
            scriptEventPublisher);
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("opaque-account-scope")
                .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
                .build());
    when(accountClient.joinPublicProductionMembership(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(false)
                .setOutcomeCode("AUTH_UNAVAILABLE")
                .build(),
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(true)
                .setOutcomeCode("CREATED")
                .build());
    SessionContext context =
        new SessionContext(7L, 22L, 41L, "emberline@example.com", 0L, null, 0L, "jwt");

    TextCommandInterpretationResult realmsResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.REALMS, List.of("demo-world"), "REALMS demo-world"),
                false,
                Optional.of(context)));
    TextCommandInterpretationResult firstJoinResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
                false,
                Optional.of(context)));
    TextCommandInterpretationResult retryJoinResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
                false,
                Optional.of(context)));

    assertThat(realmsResult.commandResult().accepted()).isTrue();
    assertThat(firstJoinResult.commandResult().accepted()).isFalse();
    assertThat(retryJoinResult.commandResult().accepted()).isTrue();
    assertThat(realmsResult.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isInstanceOf(RealmBrowseViewOutput.class);
    org.mockito.ArgumentCaptor<net.firedevops.firemud.shared.v1.PlayerExecutionContext>
        callerCaptor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.class);
    org.mockito.ArgumentCaptor<DirectTextConnectScopeTarget> targetCaptor =
        org.mockito.ArgumentCaptor.forClass(DirectTextConnectScopeTarget.class);
    Mockito.verify(accountClient)
        .issueDirectTextConnectScope(callerCaptor.capture(), targetCaptor.capture());
    assertThat(callerCaptor.getValue().getAccountId()).isEqualTo("41");
    assertThat(callerCaptor.getValue().getSessionId()).isEqualTo("7");
    assertThat(targetCaptor.getValue().realmId()).isEqualTo(realmId.toString());
    assertThat(targetCaptor.getValue().catalogRevision()).isEqualTo(7L);
    assertThat(targetCaptor.getValue().pointerVersion()).isEqualTo(4L);

    org.mockito.ArgumentCaptor<net.firedevops.firemud.shared.v1.PlayerExecutionContext>
        joinContextCaptor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.class);
    org.mockito.ArgumentCaptor<String> scopeIdCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<String> requestIdCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    Mockito.verify(accountClient, Mockito.times(2))
        .joinPublicProductionMembership(
            joinContextCaptor.capture(), scopeIdCaptor.capture(), requestIdCaptor.capture());
    List<net.firedevops.firemud.shared.v1.PlayerExecutionContext> joinContexts =
        joinContextCaptor.getAllValues();
    List<String> scopeIds = scopeIdCaptor.getAllValues();
    List<String> requestIds = requestIdCaptor.getAllValues();
    assertThat(scopeIds.getFirst()).isEqualTo("opaque-account-scope");
    assertThat(requestIds.getFirst()).isNotBlank();
    assertThat(requestIds.getLast()).isEqualTo(requestIds.getFirst());
    assertThat(scopeIds.getLast()).isEqualTo(scopeIds.getFirst());
    assertThat(joinContexts.getFirst().getRequestId()).isEqualTo(requestIds.getFirst());
    assertThat(joinContexts.getFirst().getAccountId()).isEqualTo("41");
    assertThat(joinContexts.getFirst().getSessionId()).isEqualTo("7");
    assertThat(joinContexts.getFirst().getRealmId()).isEqualTo(realmId.toString());
    assertThat(joinContexts.getLast()).isEqualTo(joinContexts.getFirst());
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(gameplayCommand -> "REALMS".equals(gameplayCommand.getCommandName())));
  }

  @Test
  void joinPreservesTerminalAccountOutcomesWithSpecificGuidance() {
    UUID realmId = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
    UUID namespaceId = UUID.fromString("42d234a2-7487-4dda-a7e5-a3831214328e");
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "demo-world",
                    "Demo World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            22L,
                            9L,
                            4L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW",
                            7L,
                            realmId,
                            namespaceId)))));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                catalog, entityManagementClient, accountClient, new DirectTextConnectScopeSessionStore()),
            scriptEventPublisher);
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("opaque-account-scope-1")
                .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
                .build(),
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("opaque-account-scope-2")
                .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
                .build());
    when(accountClient.joinPublicProductionMembership(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(false)
                .setOutcomeCode("CONNECT_SCOPE_INVALID")
                .build(),
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(false)
                .setOutcomeCode("MEMBERSHIP_RECONCILIATION_REQUIRED")
                .build());
    SessionContext context =
        new SessionContext(7L, 22L, 41L, "emberline@example.com", 0L, null, 0L, "jwt");

    scopedHandler.handle(
        new TextCommandDispatchRequest(
            "7",
            new TextCommand(TextCommandType.REALMS, List.of("demo-world"), "REALMS demo-world"),
            false,
            Optional.of(context)));
    TextCommandInterpretationResult invalidScopeResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
                false,
                Optional.of(context)));
    scopedHandler.handle(
        new TextCommandDispatchRequest(
            "7",
            new TextCommand(TextCommandType.REALMS, List.of("demo-world"), "REALMS demo-world"),
            false,
            Optional.of(context)));
    TextCommandInterpretationResult reconciliationResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
                false,
                Optional.of(context)));

    assertThat(invalidScopeResult.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_INVALID");
    assertThat(invalidScopeResult.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isEqualTo(
            new net.firedevops.firemud.gamesession.presentation.ErrorOutput(
                "CONNECT_SCOPE_INVALID", "Join scope is invalid or expired. Run REALMS again."));
    assertThat(reconciliationResult.commandResult().errorCode())
        .isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(reconciliationResult.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isEqualTo(
            new net.firedevops.firemud.gamesession.presentation.ErrorOutput(
                "MEMBERSHIP_RECONCILIATION_REQUIRED",
                "Membership needs reconciliation. Contact support before retrying JOIN."));
  }

  private static GameplayCatalogProperties.World world(
      String slug, long tenantId, long gameInstanceId, boolean requiresCharacterSelection) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(slug);
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }
}
