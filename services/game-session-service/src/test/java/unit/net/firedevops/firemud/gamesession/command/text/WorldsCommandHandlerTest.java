package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(
          TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
          entityManagementClient);

  @Test
  void browseViewReturnsStructuredWorldList() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    WorldsViewOutput response = handler.browseView();

    assertThat(response.worlds()).hasSize(2);
    assertThat(response.worlds().get(0).slug()).isEqualTo("demo");
    assertThat(response.worlds().get(0).displayName()).isEqualTo("Demo World");
    assertThat(response.worlds().get(1).displayName()).isEqualTo("Builder Sandbox");
  }

  @Test
  void browseRealmsReturnsStructuredRealmList() {
    RealmBrowseViewOutput response = handler.browseRealms("sandbox").orElseThrow();

    assertThat(response.worldSlug()).isEqualTo("sandbox");
    assertThat(response.realms()).hasSize(1);
    assertThat(response.realms().get(0).realmSlug()).isEqualTo("production");
    assertThat(response.realms().get(0).stateScope()).isEqualTo("SHARED");
    assertThat(response.realms().get(0).characterCreationPolicy()).isEqualTo("ALLOW_NEW");
  }

  @Test
  void browseRealmsToleratesNullRealmEnums() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setCharacterCreationPolicy(null);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
            entityManagementClient);

    RealmBrowseViewOutput response = localHandler.browseRealms("demo").orElseThrow();

    assertThat(response.realms().getFirst().stateScope()).isEqualTo("UNSPECIFIED");
    assertThat(response.realms().getFirst().characterCreationPolicy()).isEqualTo("UNSPECIFIED");
  }

  @Test
  void browseCharactersReturnsStructuredCharacterList() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("sandbox", 22L, 2L, true)));
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    addPublicProductionAuthority(gameplayCatalogProperties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
    WorldsCommandHandler localHandler = authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    CharacterBrowseViewOutput output =
        ((WorldsCommandHandler.CharacterBrowseResult.Success) result).output();
    assertThat(output.worldSlug()).isEqualTo("demo");
    assertThat(output.realmSlug()).isEqualTo("production");
    assertThat(output.stateScope()).isEqualTo("SHARED");
    assertThat(output.characterCreationPolicy()).isEqualTo("ALLOW_NEW");
    assertThat(output.characters()).hasSize(1);
    assertThat(output.characters().get(0).characterName()).isEqualTo("Emberline");
  }

  @Test
  void browseCharactersUsesIsolatedStateRealmRoster() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.COPIED_ONLY);
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setGameInstanceId(41L);
    addPublicProductionAuthority(gameplayCatalogProperties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("8001")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setName("Forkline")
                        .setLevel(5)
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    CharacterBrowseViewOutput output =
        ((WorldsCommandHandler.CharacterBrowseResult.Success) result).output();
    assertThat(output.stateScope()).isEqualTo("ISOLATED");
    assertThat(output.characterCreationPolicy()).isEqualTo("COPIED_ONLY");
    assertThat(output.characters())
        .extracting(CharacterBrowseViewOutput.CharacterEntry::characterName)
        .containsExactly("Forkline");
  }

  @Test
  void browseCharactersRejectsMismatchedOrDuplicateEntityRows() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.getTenantMembershipForRuntime(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(publicProductionProperties(), accountClient);
    net.firedevops.firemud.entitymanagement.v1.Character valid =
        net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
            .setId("7001")
            .setTenantId("22")
            .setAccountId("123")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setName("Emberline")
            .build();
    for (net.firedevops.firemud.entitymanagement.v1.Character invalid :
        List.of(
            valid.toBuilder().setTenantId("23").build(),
            valid.toBuilder().setAccountId("456").build(),
            valid.toBuilder().setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED).build(),
            valid.toBuilder().clearId().build(),
            valid.toBuilder().clearName().build())) {
      Mockito.when(entityManagementClient.listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
          .thenReturn(ListCharactersByAccountResponse.newBuilder().addCharacters(invalid).build());
      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    }

    Mockito.when(entityManagementClient.listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder()
            .addCharacters(valid)
            .addCharacters(valid)
            .build());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
  }

  @Test
  void browseCharactersFallsBackToUnspecifiedRosterScopeForNullRealmScope() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    addPublicProductionAuthority(gameplayCatalogProperties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().build());
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
  }

  @Test
  void browseCharactersReturnsJoinRequiredForPublicNonMemberWithoutReadingEntityRoster() {
    GameplayCatalogProperties properties = publicProductionProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            publicMembership(false, false, 0L, 0L, "MISSING"));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("JOIN_REQUIRED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersReturnsJoinRequiredForPublicInactiveMemberWithoutReadingEntityRoster() {
    GameplayCatalogProperties properties = publicProductionProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            publicMembership(true, false, 3L, 4L, "INACTIVE"));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("JOIN_REQUIRED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersFailsClosedForUnknownPublicMembershipLifecycleWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(true, true, 1L, 1L, "UNKNOWN"));
  }

  @Test
  void browseCharactersFailsClosedForActivePublicMembershipWithoutAdmissionWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(true, false, 1L, 1L, "ACTIVE"));
  }

  @Test
  void browseCharactersFailsClosedForAbsentPublicMembershipWithActiveLifecycleWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(false, false, 0L, 0L, "ACTIVE"));
  }

  @Test
  void browseCharactersFailsClosedForInactivePublicMembershipWithAdmissionWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(true, true, 1L, 1L, "INACTIVE"));
  }

  @Test
  void browseCharactersDeniesPublicJoinWhenAdmissionPolicyIsClosedBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
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
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "PUBLIC_PRODUCTION_ADMISSION_DENIED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersReturnsUnavailableEntitlementBeforeReadingPublicEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
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
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITLEMENT_UNAVAILABLE")
                        .setMessage("Entitlement authority unavailable")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "ENTITLEMENT_UNAVAILABLE"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersDeniesRevokedPrivateMembershipBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            activeMembership().toBuilder()
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("INACTIVE")
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "NON_PUBLIC_ENROLLMENT_REQUIRED"));
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void browseCharactersDeniesPrivateRealmWithoutExactGrantBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("WORLD_ACCESS_DENIED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersRejectsZeroPublicProductionCardinalityWithExplicitRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersRejectsMultiplePublicProductionCardinalityWithExplicitRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("alternate", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersAllowsActivePublicMemberWhenJoiningIsClosedAndReadsEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.getDefaultInstance());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmForNonMember() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder().setMembershipExists(false).build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .isEmpty();
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmWithoutExactGrant() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .isEmpty();
  }

  @Test
  void authenticatedBrowseFailsClosedWhenMembershipAuthorityUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
  }

  @Test
  void authenticatedBrowseIncludesPrivateRealmForActiveMemberWithExactGrant() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(true));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
  }

  @Test
  void authenticatedBrowseRejectsPrivateRealmWhenEntitlementTenantDoesNotMatch() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(true));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("23")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmWhenGameplayEntitlementIsUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(true));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .isEmpty();
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseFailsClosedWhenGameplayEntitlementIsUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(true));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITLEMENT_UNAVAILABLE")
                        .setMessage("Entitlement authority unavailable")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ENTITLEMENT_UNAVAILABLE"));
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseKeepsPublicRealmDiscoveryAndScopeIssuanceWithoutMembership() {
    UUID realmId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
            "production",
            "Live Realm",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            realmId,
            namespaceId);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-1")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(realm)))),
            entityManagementClient,
            accountClient,
            new DirectTextConnectScopeSessionStore());

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void authenticatedBrowsePropagatesClosedPublicRealmInsteadOfReturningEmptySuccess() {
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
            "production", "Live Realm", 22L, 1L, 1L, true, true, false, "SHARED", "ALLOW_NEW", 1L,
            UUID.randomUUID(), UUID.randomUUID());
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(IssueDirectTextConnectScopeResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("REALM_UNAVAILABLE"))
            .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(realm)))),
            entityManagementClient,
            accountClient,
            new DirectTextConnectScopeSessionStore());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("REALM_UNAVAILABLE"));
  }

  @Test
  void joinUsesRetainedRealmScopeWhenCurrentWorldOrdinalChanges() {
    GameplayWorldCatalog.RealmView realmA =
        new GameplayWorldCatalog.RealmView(
            "production", "A", 22L, 1L, 1L, true, true, false, "SHARED", "ALLOW_NEW", 1L,
            UUID.randomUUID(), UUID.randomUUID());
    GameplayWorldCatalog.RealmView realmB =
        new GameplayWorldCatalog.RealmView(
            "production", "B", 23L, 2L, 1L, true, true, false, "SHARED", "ALLOW_NEW", 1L,
            UUID.randomUUID(), UUID.randomUUID());
    GameplayWorldCatalog.WorldView worldA =
        new GameplayWorldCatalog.WorldView("a", "A", List.of(realmA));
    GameplayWorldCatalog.WorldView worldB =
        new GameplayWorldCatalog.WorldView("b", "B", List.of(realmB));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(worldA, worldB));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(worlds::get);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(IssueDirectTextConnectScopeResponse.newBuilder()
            .setConnectScopeId("scope-a")
            .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
            .build());
    Mockito.when(accountClient.joinPublicProductionMembership(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(JoinPublicProductionMembershipResponse.newBuilder()
            .setSuccess(false).setOutcomeCode("CONNECT_SCOPE_MISMATCH").build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(catalog, entityManagementClient, accountClient,
            new DirectTextConnectScopeSessionStore());

    assertThat(localHandler.browseRealms(authenticatedSession(), "1"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    worlds.set(List.of(worldB, worldA));
    assertThat(catalog.resolveWorld("1")).hasValue(worldB);
    WorldsCommandHandler.JoinMembershipResult join =
        localHandler.joinPublicProductionMembership(authenticatedSession(), "1");

    assertThat(join).isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    assertThat(((WorldsCommandHandler.JoinMembershipResult.Response) join).response().getOutcomeCode())
        .isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verify(accountClient).joinPublicProductionMembership(
        Mockito.argThat(context -> context.getTenantId().equals("22")
            && context.getRealmId().equals(realmA.realmId().toString())),
        Mockito.eq("scope-a"), Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void authenticatedBrowseFailsClosedWhenTenantHasNoPublicProductionRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void authenticatedBrowseFailsClosedWhenPublicProductionRealmIsDuplicatedAcrossWorlds() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("alternate", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  private WorldsCommandHandler authenticatedHandler(
      GameplayCatalogProperties properties, AccountClient accountClient) {
    return new WorldsCommandHandler(
        TestGameplayWorldCatalogs.fromProperties(properties),
        entityManagementClient,
        accountClient,
        new DirectTextConnectScopeSessionStore());
  }

  private GameplayCatalogProperties publicProductionProperties() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    return properties;
  }

  private void assertPublicMembershipFailsClosed(
      GetTenantMembershipForRuntimeResponse membership) {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membership);
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  private void addPublicProductionAuthority(GameplayCatalogProperties properties) {
    GameplayCatalogProperties.World authority = world("production-authority", 22L, 1L, false);
    authority.getRealms().getFirst().setPublicProductionRealm(true);
    List<GameplayCatalogProperties.World> worlds = new ArrayList<>(properties.getWorlds());
    worlds.add(authority);
    properties.setWorlds(worlds);
  }

  private SessionContext authenticatedSession() {
    return new SessionContext(7L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");
  }

  private GetTenantMembershipForRuntimeResponse activeMembership() {
    return publicMembership(true, true, 1L, 1L, "ACTIVE");
  }

  private GetTenantMembershipForRuntimeResponse publicMembership(
      boolean membershipExists,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String lifecycleState) {
    return GetTenantMembershipForRuntimeResponse.newBuilder()
        .setAccountId("123")
        .setTenantId("22")
        .setMembershipExists(membershipExists)
        .setGameplayAdmissionAllowed(gameplayAdmissionAllowed)
        .setMembershipVersion(membershipVersion)
        .setMembershipLifecycleState(lifecycleState)
        .setMembershipAuthorityGeneration(membershipAuthorityGeneration)
        .setEvaluatedAt("2026-03-30T00:00:00Z")
        .build();
  }

  private GetTenantEntitlementsForRuntimeResponse publicEntitlement(boolean allowPublicJoin) {
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setTenantId("22")
        .setGameplayAvailable(true)
        .setAllowPublicJoin(allowPublicJoin)
        .setEntitlementVersion(1L)
        .setEvaluatedAt("2026-03-30T00:00:00Z")
        .build();
  }

  private GetRealmAccessGrantForRuntimeResponse grant(boolean granted) {
    return grant("preview", granted);
  }

  private GetRealmAccessGrantForRuntimeResponse grant(
      String worldSlug, boolean granted) {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId("123")
        .setTenantId("22")
        .setWorldSlug(worldSlug)
        .setRealmSlug("production")
        .setGranted(granted)
        .setGrantVersion(granted ? 1L : 0L)
        .setEvaluatedAt("2026-03-30T00:00:00Z")
        .build();
  }

  private void stubActivePrivateAuthorization(AccountClient accountClient, String worldSlug) {
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq(worldSlug),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(worldSlug, true));
    Mockito.when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
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
