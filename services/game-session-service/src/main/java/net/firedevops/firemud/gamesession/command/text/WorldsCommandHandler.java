package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles public world-browse commands before login and after login. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class WorldsCommandHandler {
  private final GameplayWorldCatalog worldCatalog;
  private final EntityManagementClient entityManagementClient;
  private final AccountClient accountClient;
  private final DirectTextConnectScopeSessionStore connectScopeSessionStore;

  @Autowired
  public WorldsCommandHandler(
      GameplayWorldCatalog worldCatalog,
      EntityManagementClient entityManagementClient,
      AccountClient accountClient,
      DirectTextConnectScopeSessionStore connectScopeSessionStore) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.connectScopeSessionStore =
        Objects.requireNonNull(
            connectScopeSessionStore, "connectScopeSessionStore must not be null");
  }

  WorldsCommandHandler(
      GameplayWorldCatalog worldCatalog, EntityManagementClient entityManagementClient) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
    this.accountClient = null;
    this.connectScopeSessionStore = new DirectTextConnectScopeSessionStore();
  }

  public WorldsViewOutput browseView() {
    return worldCatalog.browseView();
  }

  public java.util.Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return worldCatalog.browseRealms(worldSelector);
  }

  public RealmBrowseResult browseRealms(SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    if (sessionContext.accountId() <= 0 || sessionContext.sessionId() <= 0) {
      return RealmBrowseResult.failure("LOGIN_REQUIRED");
    }
    if (connectScopeSessionStore != null) {
      connectScopeSessionStore.clearWorldScopes(sessionContext, worldSelector);
    }
    Optional<GameplayWorldCatalog.WorldView> maybeWorld = worldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isEmpty()) {
      return RealmBrowseResult.invalidSelector();
    }

    GameplayWorldCatalog.WorldView world = maybeWorld.orElseThrow();
    List<RealmBrowseViewOutput.RealmEntry> visibleEntries = new ArrayList<>();
    List<DirectTextConnectScopeSessionStore.ScopedRealm> issuedScopes = new ArrayList<>();
    for (GameplayWorldCatalog.RealmView realm : worldCatalog.visibleRealms(world)) {
      if (realm.publicProductionRealm()) {
        if (accountClient == null || connectScopeSessionStore == null) {
          return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
        }
        DirectTextConnectScopeTarget target = connectScopeTarget(world, realm);
        if (target == null) {
          return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
        }
        PlayerExecutionContext playerContext = playerContext(sessionContext, realm);
        IssueDirectTextConnectScopeResponse scopeResponse =
            accountClient.issueDirectTextConnectScope(playerContext, target);
        if (scopeResponse.hasError()) {
          String errorCode = scopeResponse.getError().getCode();
          if ("REALM_UNAVAILABLE".equals(errorCode)) {
            continue;
          }
          return RealmBrowseResult.failure(errorCode.isBlank() ? "AUTH_UNAVAILABLE" : errorCode);
        }
        if (scopeResponse.getConnectScopeId().isBlank()) {
          return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
        }
        Instant expiresAt;
        try {
          expiresAt = Instant.parse(scopeResponse.getConnectScopeExpiresAt());
        } catch (DateTimeParseException ex) {
          return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
        }
        if (!expiresAt.isAfter(Instant.now())) {
          return RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
        }
        issuedScopes.add(
            new DirectTextConnectScopeSessionStore.ScopedRealm(
                realm.slug(), true, scopeResponse.getConnectScopeId(), expiresAt, playerContext));
      }
      visibleEntries.add(realmEntry(visibleEntries.size() + 1, realm));
    }

    connectScopeSessionStore.replaceWorldScopes(
        sessionContext, worldSelector, world.slug(), issuedScopes);
    return RealmBrowseResult.success(new RealmBrowseViewOutput(world.slug(), visibleEntries));
  }

  public JoinMembershipResult joinPublicProductionMembership(
      SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    if (sessionContext.accountId() <= 0 || sessionContext.sessionId() <= 0) {
      return JoinMembershipResult.failure("LOGIN_REQUIRED");
    }
    if (accountClient == null || connectScopeSessionStore == null) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    Optional<DirectTextConnectScopeSessionStore.JoinScope> maybeJoinScope =
        connectScopeSessionStore.publicProductionScopeForJoin(
            sessionContext, worldSelector, Instant.now());
    if (maybeJoinScope.isEmpty()) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    DirectTextConnectScopeSessionStore.JoinScope joinScope = maybeJoinScope.orElseThrow();
    DirectTextConnectScopeSessionStore.ScopedRealm scope = joinScope.scope();
    String requestId = joinScope.requestId();
    PlayerExecutionContext playerContext =
        scope.playerContext().toBuilder().setRequestId(requestId).build();
    return JoinMembershipResult.response(
        accountClient.joinPublicProductionMembership(
            playerContext, scope.connectScopeId(), requestId));
  }

  private DirectTextConnectScopeTarget connectScopeTarget(
      GameplayWorldCatalog.WorldView world, GameplayWorldCatalog.RealmView realm) {
    if (realm.tenantId() <= 0
        || realm.gameInstanceId() <= 0
        || realm.realmId() == null
        || realm.playableStateNamespaceId() == null
        || realm.catalogRevision() <= 0
        || realm.pointerVersion() <= 0
        || !"SHARED".equals(realm.stateScope()) && !"ISOLATED".equals(realm.stateScope())) {
      return null;
    }
    return new DirectTextConnectScopeTarget(
        Long.toString(realm.tenantId()),
        world.slug(),
        realm.slug(),
        realm.realmId().toString(),
        realm.playableStateNamespaceId().toString(),
        realm.stateScope(),
        Long.toString(realm.gameInstanceId()),
        realm.catalogRevision(),
        realm.pointerVersion());
  }

  private PlayerExecutionContext playerContext(
      SessionContext caller, GameplayWorldCatalog.RealmView realm) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(Long.toString(caller.accountId()))
        .setSessionId(Long.toString(caller.sessionId()))
        .setTenantId(Long.toString(realm.tenantId()))
        .setRealmId(realm.realmId().toString())
        .setPlayableStateNamespaceId(realm.playableStateNamespaceId().toString())
        .setPlayableStateScope(realm.stateScope())
        .setGameInstanceId(Long.toString(realm.gameInstanceId()))
        .build();
  }

  private RealmBrowseViewOutput.RealmEntry realmEntry(
      int ordinal, GameplayWorldCatalog.RealmView realm) {
    return new RealmBrowseViewOutput.RealmEntry(
        ordinal,
        realm.slug(),
        realm.displayName(),
        realm.gameInstanceId(),
        realm.requiresCharacterSelection(),
        realm.stateScope(),
        realm.characterCreationPolicy());
  }

  public sealed interface RealmBrowseResult
      permits RealmBrowseResult.Success,
          RealmBrowseResult.InvalidSelector,
          RealmBrowseResult.Failure {
    static RealmBrowseResult success(RealmBrowseViewOutput output) {
      return new Success(output);
    }

    static RealmBrowseResult invalidSelector() {
      return new InvalidSelector();
    }

    static RealmBrowseResult failure(String code) {
      return new Failure(code);
    }

    record Success(RealmBrowseViewOutput output) implements RealmBrowseResult {}

    record InvalidSelector() implements RealmBrowseResult {}

    record Failure(String code) implements RealmBrowseResult {}
  }

  public sealed interface JoinMembershipResult
      permits JoinMembershipResult.Response, JoinMembershipResult.Failure {
    static JoinMembershipResult response(
        net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse response) {
      return new Response(response);
    }

    static JoinMembershipResult failure(String code) {
      return new Failure(code);
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Generated protobuf responses are immutable value messages.")
    record Response(
        net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse response)
        implements JoinMembershipResult {}

    record Failure(String code) implements JoinMembershipResult {}
  }

  public CharacterBrowseResult browseCharacters(
      SessionContext sessionContext, String worldSelector, String realmSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    java.util.Optional<GameplayWorldCatalog.WorldView> maybeWorld =
        worldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isEmpty()) {
      return CharacterBrowseResult.invalidWorld();
    }
    GameplayWorldCatalog.WorldView world = maybeWorld.orElseThrow();
    java.util.Optional<GameplayWorldCatalog.RealmView> maybeRealm =
        StringUtils.hasText(realmSelector)
            ? worldCatalog.resolveRealm(world, realmSelector)
            : worldCatalog.requiresExplicitRealmSelection(world)
                ? java.util.Optional.empty()
                : worldCatalog.resolveDefaultRealm(world);
    if (StringUtils.hasText(realmSelector) && maybeRealm.isEmpty()) {
      return CharacterBrowseResult.invalidRealm(world.slug());
    }
    if (!StringUtils.hasText(realmSelector) && maybeRealm.isEmpty()) {
      return CharacterBrowseResult.realmSelectionRequired(world.slug());
    }

    GameplayWorldCatalog.RealmView realm = maybeRealm.orElseThrow();
    ListCharactersByAccountResponse response =
        entityManagementClient.listCharactersByAccount(
            Long.toString(realm.tenantId()),
            Long.toString(sessionContext.accountId()),
            Long.toString(realm.gameInstanceId()),
            toPlayableStateScope(realm));
    if (response.hasError()) {
      return CharacterBrowseResult.unavailable();
    }
    java.util.List<CharacterBrowseViewOutput.CharacterEntry> entries =
        new java.util.ArrayList<>(response.getCharactersCount());
    for (int i = 0; i < response.getCharactersCount(); i++) {
      net.firedevops.firemud.entitymanagement.v1.Character character = response.getCharacters(i);
      entries.add(
          new CharacterBrowseViewOutput.CharacterEntry(
              i + 1, character.getId(), character.getName(), character.getLevel()));
    }
    return CharacterBrowseResult.success(
        new CharacterBrowseViewOutput(
            world.slug(),
            realm.slug(),
            realm.stateScope(),
            realm.characterCreationPolicy(),
            entries));
  }

  public sealed interface CharacterBrowseResult
      permits CharacterBrowseResult.Success,
          CharacterBrowseResult.InvalidWorld,
          CharacterBrowseResult.InvalidRealm,
          CharacterBrowseResult.RealmSelectionRequired,
          CharacterBrowseResult.Unavailable {
    static CharacterBrowseResult success(CharacterBrowseViewOutput output) {
      return new Success(output);
    }

    static CharacterBrowseResult invalidWorld() {
      return new InvalidWorld();
    }

    static CharacterBrowseResult invalidRealm(String worldSlug) {
      return new InvalidRealm(worldSlug);
    }

    static CharacterBrowseResult realmSelectionRequired(String worldSlug) {
      return new RealmSelectionRequired(worldSlug);
    }

    static CharacterBrowseResult unavailable() {
      return new Unavailable();
    }

    record Success(CharacterBrowseViewOutput output) implements CharacterBrowseResult {}

    record InvalidWorld() implements CharacterBrowseResult {}

    record InvalidRealm(String worldSlug) implements CharacterBrowseResult {}

    record RealmSelectionRequired(String worldSlug) implements CharacterBrowseResult {}

    record Unavailable() implements CharacterBrowseResult {}
  }

  private PlayableStateScope toPlayableStateScope(GameplayWorldCatalog.RealmView realm) {
    String scope =
        realm.stateScope() == null
            ? ""
            : realm.stateScope().trim().toUpperCase(java.util.Locale.ROOT);
    return switch (scope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }
}
