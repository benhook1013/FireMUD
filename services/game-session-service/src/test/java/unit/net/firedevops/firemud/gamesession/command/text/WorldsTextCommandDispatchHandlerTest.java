package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
              Mockito.mock(AccountClient.class),
              DirectTextConnectScopeSessionStore.inMemoryForTest()),
          scriptEventPublisher);

  @Test
  void publishesCommandEventForGameplayScopedWorldsBrowse() {
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "7",
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
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
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
                .setEvaluatedAt(Instant.now().toString())
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
                .setEvaluatedAt(Instant.now().toString())
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
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
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
  void closesPublicCharsBrowseBeforeAuthorityOrRosterReads() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.CHARS, List.of("demo"), "CHARS demo"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, scriptEventPublisher);
  }

  @Test
  void dispatchDoesNotRevealDeniedPrivateRealmInPublicWorld() {
    GameplayCatalogProperties.World world = world("demo", 22L, 1L, false);
    GameplayCatalogProperties.Realm production = world.getRealms().getFirst();
    production.setPublicProductionRealm(true);
    GameplayCatalogProperties.Realm playtest = world("demo", 22L, 2L, false).getRealms().getFirst();
    playtest.setSlug("playtest");
    playtest.setDisplayName("Playtest Realm");
    playtest.setPublicProductionRealm(false);
    world.setRealms(List.of(production, playtest));
    gameplayCatalogProperties.setWorlds(List.of(world));

    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult unknownRealm =
        scopedHandler.handle(charsRequest("guessed", context));
    assertThat(unknownRealm.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(unknownRealm.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isEqualTo(
            new net.firedevops.firemud.gamesession.presentation.ErrorOutput(
                "INVALID_ARGUMENT", "Use REALMS demo to choose a visible realm first."));

    for (int i = 0; i < 3; i++) {
      TextCommandInterpretationResult deniedRealm =
          scopedHandler.handle(charsRequest("playtest", context));
      assertThat(deniedRealm.commandResult().errorCode())
          .isEqualTo(unknownRealm.commandResult().errorCode());
      assertThat(deniedRealm.outputs()).isEqualTo(unknownRealm.outputs());
    }

    Mockito.verifyNoInteractions(accountClient, entityManagementClient, scriptEventPublisher);
  }

  @Test
  void routesExplicitPublicCharsBrowseToUnavailableBeforeAuthorityOrRosterReads() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(
                    TextCommandType.CHARS, List.of("demo", "production"), "CHARS demo production"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
    assertThat(result.outputs()).singleElement().extracting(output -> output.payload()).isNotNull();
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, scriptEventPublisher);
  }

  @Test
  void skipsCommandEventWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "71",
                new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  @Test
  void realMsRetainsAccountScopeAndJoinSendsTheBoundContextAndServerRequestId() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
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
            new WorldsCommandHandler(catalog, accountClient, scopeStore), scriptEventPublisher);
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("opaque-account-scope")
                .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
                .build());
    when(accountClient.joinPublicProductionMembership(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(Instant.class)))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(false)
                .setOutcomeCode("ENTITLEMENT_UNAVAILABLE")
                .build(),
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
    TextCommandInterpretationResult finalJoinResult =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
                false,
                Optional.of(context)));

    assertThat(realmsResult.commandResult().accepted()).isTrue();
    assertThat(firstJoinResult.commandResult().accepted()).isFalse();
    assertThat(firstJoinResult.commandResult().errorCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(firstJoinResult.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isEqualTo(
            new net.firedevops.firemud.gamesession.presentation.ErrorOutput(
                "ENTITLEMENT_UNAVAILABLE",
                "Join policy could not be checked. Retry the same JOIN while its realm scope is valid.",
                "error.join.entitlement-unavailable",
                java.util.Map.of()));
    assertThat(retryJoinResult.commandResult().errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(finalJoinResult.commandResult().accepted()).isTrue();
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
    Mockito.verify(accountClient, Mockito.times(3))
        .joinPublicProductionMembership(
            joinContextCaptor.capture(),
            scopeIdCaptor.capture(),
            requestIdCaptor.capture(),
            Mockito.any(Instant.class));
    List<net.firedevops.firemud.shared.v1.PlayerExecutionContext> joinContexts =
        joinContextCaptor.getAllValues();
    List<String> scopeIds = scopeIdCaptor.getAllValues();
    List<String> requestIds = requestIdCaptor.getAllValues();
    assertThat(scopeIds.getFirst()).isEqualTo("opaque-account-scope");
    assertThat(requestIds.getFirst()).isNotBlank();
    assertThat(requestIds.getLast()).isEqualTo(requestIds.getFirst());
    assertThat(requestIds.get(1)).isEqualTo(requestIds.getFirst());
    assertThat(scopeIds.getLast()).isEqualTo(scopeIds.getFirst());
    assertThat(scopeIds.get(1)).isEqualTo(scopeIds.getFirst());
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
                catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest()),
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
    when(accountClient.joinPublicProductionMembership(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(Instant.class)))
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
                "CONNECT_SCOPE_INVALID",
                "Join scope is invalid or expired. Run REALMS again.",
                "error.join.connect-scope-invalid",
                java.util.Map.of()));
    assertThat(reconciliationResult.commandResult().errorCode())
        .isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(reconciliationResult.outputs())
        .singleElement()
        .extracting(output -> output.payload())
        .isEqualTo(
            new net.firedevops.firemud.gamesession.presentation.ErrorOutput(
                "MEMBERSHIP_RECONCILIATION_REQUIRED",
                "Membership needs reconciliation. Contact support before retrying JOIN.",
                "error.join.membership-reconciliation-required",
                java.util.Map.of()));
  }

  @Test
  void joinMissingSelectorRendersLocalizedErrorWithoutCallingAccount() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    TextCommand command = new TextCommand(TextCommandType.JOIN, List.of(), "JOIN");
    TextCommandInterpretationResult result =
        scopedHandler.handle(new TextCommandDispatchRequest("7", command, false, Optional.empty()));
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new net.firedevops.firemud.gamesession.config.PresentationProperties());
    var error =
        (net.firedevops.firemud.gamesession.presentation.ErrorOutput)
            result.outputs().getFirst().payload();

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("JOIN requires a world selector after LOGIN.");
    assertThat(error.code()).isEqualTo("INVALID_ARGUMENT");
    assertThat(error.message()).isEqualTo("JOIN requires a world selector after LOGIN.");
    assertThat(error.messageKey()).isEqualTo("error.join.invalid-request");
    assertThat(renderer.renderAll(command, result.commandResult(), result.outputs(), "fr"))
        .isEqualTo("ERROR INVALID_ARGUMENT JOIN requiert un sélecteur de monde après LOGIN.");
    assertThat(renderer.renderAll(command, result.commandResult(), result.outputs(), "de"))
        .isEqualTo("ERROR INVALID_ARGUMENT JOIN requires a world selector after LOGIN.");
    Mockito.verifyNoInteractions(accountClient);
  }

  @ParameterizedTest
  @MethodSource("joinPresentationCases")
  void joinDispatchOutputsRenderLocalizedGuidanceAndEnglishFallback(
      String outcomeCode,
      boolean success,
      String expectedCode,
      String expectedEnglish,
      String expectedFrench) {
    TextCommandInterpretationResult result = dispatchJoinOutcome(outcomeCode, success);
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new net.firedevops.firemud.gamesession.config.PresentationProperties());
    TextCommand command =
        new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world");

    assertThat(result.commandResult().accepted()).isEqualTo(success);
    assertThat(result.outputs().getFirst().replayPolicy())
        .isEqualTo(net.firedevops.firemud.gamesession.presentation.ReplayPolicy.NO_REPLAY);
    assertThat(result.outputs().getFirst().briefRenderPolicy())
        .isEqualTo(net.firedevops.firemud.gamesession.presentation.BriefRenderPolicy.ALWAYS_SHOW);
    if (!success) {
      assertThat(result.commandResult().errorCode()).isEqualTo(expectedCode);
      assertThat(result.commandResult().errorMessage()).isEqualTo(expectedEnglish);
      var error =
          (net.firedevops.firemud.gamesession.presentation.ErrorOutput)
              result.outputs().getFirst().payload();
      assertThat(error.code()).isEqualTo(expectedCode);
      assertThat(error.message()).isEqualTo(expectedEnglish);
    }

    assertThat(renderer.renderAll(command, result.commandResult(), result.outputs(), "fr"))
        .isEqualTo(expectedFrench);
    String fallback = renderer.renderAll(command, result.commandResult(), result.outputs(), "de");
    assertThat(fallback)
        .isEqualTo(
            success
                ? "OK JOIN " + expectedEnglish
                : "ERROR " + expectedCode + " " + expectedEnglish);
    if (success) {
      assertThat(expectedEnglish).doesNotContain("CHARS");
      assertThat(expectedFrench).doesNotContain("CHARS");
      assertThat(fallback).doesNotContain("CHARS");
    }
  }

  private static Stream<Arguments> joinPresentationCases() {
    return Stream.of(
        Arguments.of(
            "CONNECT_SCOPE_INVALID",
            false,
            "CONNECT_SCOPE_INVALID",
            "Join scope is invalid or expired. Run REALMS again.",
            "ERROR CONNECT_SCOPE_INVALID La portée de JOIN est invalide ou expirée. Relancez REALMS."),
        Arguments.of(
            "CONNECT_SCOPE_MISMATCH",
            false,
            "CONNECT_SCOPE_MISMATCH",
            "Join scope expired or changed. Run REALMS again.",
            "ERROR CONNECT_SCOPE_MISMATCH La portée de JOIN a expiré ou changé. Relancez REALMS."),
        Arguments.of(
            "MEMBERSHIP_RECONCILIATION_REQUIRED",
            false,
            "MEMBERSHIP_RECONCILIATION_REQUIRED",
            "Membership needs reconciliation. Contact support before retrying JOIN.",
            "ERROR MEMBERSHIP_RECONCILIATION_REQUIRED L’adhésion nécessite une réconciliation. Contactez le support avant de réessayer JOIN."),
        Arguments.of(
            "AUTH_UNAVAILABLE",
            false,
            "AUTH_UNAVAILABLE",
            "Account authority unavailable. Retry JOIN shortly.",
            "ERROR AUTH_UNAVAILABLE L’autorité du compte est indisponible. Réessayez JOIN sous peu."),
        Arguments.of(
            "UNAVAILABLE",
            false,
            "UNAVAILABLE",
            "Account authority unavailable. Retry JOIN shortly.",
            "ERROR UNAVAILABLE L’autorité du compte est indisponible. Réessayez JOIN sous peu."),
        Arguments.of(
            "DEADLINE_EXCEEDED",
            false,
            "DEADLINE_EXCEEDED",
            "Account authority unavailable. Retry JOIN shortly.",
            "ERROR DEADLINE_EXCEEDED L’autorité du compte est indisponible. Réessayez JOIN sous peu."),
        Arguments.of(
            "ENTITLEMENT_UNAVAILABLE",
            false,
            "ENTITLEMENT_UNAVAILABLE",
            "Join policy could not be checked. Retry the same JOIN while its realm scope is valid.",
            "ERROR ENTITLEMENT_UNAVAILABLE La politique d’adhésion n’a pas pu être vérifiée. Réessayez le même JOIN tant que la portée de son royaume est valide."),
        Arguments.of(
            "LOGIN_REQUIRED",
            false,
            "LOGIN_REQUIRED",
            "Log in before joining a world.",
            "ERROR LOGIN_REQUIRED Connectez-vous avant de rejoindre un monde."),
        Arguments.of(
            "",
            false,
            "JOIN_FAILED",
            "The selected world could not be joined.",
            "ERROR JOIN_FAILED Le monde sélectionné n’a pas pu être rejoint."),
        Arguments.of(
            "UNRECOGNIZED_ACCOUNT_OUTCOME",
            false,
            "UNRECOGNIZED_ACCOUNT_OUTCOME",
            "The selected world could not be joined.",
            "ERROR UNRECOGNIZED_ACCOUNT_OUTCOME Le monde sélectionné n’a pas pu être rejoint."),
        Arguments.of(
            "",
            true,
            null,
            "Membership is ready. Continue with PLAY <world> [realm] <character> using a known character; character browsing is currently unavailable.",
            "OK JOIN L’adhésion est prête. Continuez avec PLAY <world> [realm] <character> avec un personnage connu ; la consultation des personnages n’est pas disponible actuellement."));
  }

  private TextCommandInterpretationResult dispatchJoinOutcome(String outcomeCode, boolean success) {
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
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("opaque-account-scope")
                .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
                .build());
    when(accountClient.joinPublicProductionMembership(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(Instant.class)))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(success)
                .setOutcomeCode(outcomeCode)
                .build());
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(7L, 22L, 41L, "emberline@example.com", 0L, null, 0L, "jwt");
    scopedHandler.handle(
        new TextCommandDispatchRequest(
            "7",
            new TextCommand(TextCommandType.REALMS, List.of("demo-world"), "REALMS demo-world"),
            false,
            Optional.of(context)));
    return scopedHandler.handle(
        new TextCommandDispatchRequest(
            "7",
            new TextCommand(TextCommandType.JOIN, List.of("demo-world"), "JOIN demo-world"),
            false,
            Optional.of(context)));
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
    realm.setPublicProductionRealm(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }

  private TextCommandDispatchRequest charsRequest(String realmSelector, SessionContext context) {
    return new TextCommandDispatchRequest(
        "7",
        new TextCommand(
            TextCommandType.CHARS, List.of("demo", realmSelector), "CHARS demo " + realmSelector),
        false,
        Optional.of(context));
  }

  @Test
  void realmsBrowseFailsClosedWhenAccountScopeIssuerIsUnavailable() {
    gameplayCatalogProperties.setWorlds(List.of(world("sandbox", 22L, 2L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE")
                        .build())
                .build());
    WorldsTextCommandDispatchHandler scopedHandler =
        new WorldsTextCommandDispatchHandler(
            new WorldsCommandHandler(
                TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
                accountClient,
                DirectTextConnectScopeSessionStore.inMemoryForTest()),
            scriptEventPublisher);
    SessionContext context =
        new SessionContext(
            7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        scopedHandler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.REALMS, List.of("sandbox"), "REALMS sandbox"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verifyNoInteractions(entityManagementClient, scriptEventPublisher);
  }

  @Test
  void unavailableCharsDoesNotReadRosterOrPublishGameplayEvent() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 41L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    SessionContext context =
        new SessionContext(
            7L, 22L, 123L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "7",
                new TextCommand(TextCommandType.CHARS, List.of("demo"), "CHARS demo"),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
    Mockito.verifyNoInteractions(entityManagementClient, scriptEventPublisher);
  }
}
