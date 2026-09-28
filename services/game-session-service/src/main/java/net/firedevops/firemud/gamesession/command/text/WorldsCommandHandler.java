package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
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
    if (!worldCatalog.hasValidPublicProductionRealm(world)) {
      return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    List<RealmBrowseViewOutput.RealmEntry> visibleEntries = new ArrayList<>();
    List<DirectTextConnectScopeSessionStore.ScopedRealm> issuedScopes = new ArrayList<>();
    String requestId = sessionContext.sessionId() + ":" + UUID.randomUUID();
    for (GameplayWorldCatalog.RealmView realm : worldCatalog.visibleRealms(world)) {
      if (!realm.publicProductionRealm()) {
        NonPublicRealmAuthorization authorization =
            authorizeNonPublicRealm(sessionContext, world, realm, requestId);
        if (authorization == NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE) {
          return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
        }
        if (authorization == NonPublicRealmAuthorization.ENTITLEMENT_UNAVAILABLE) {
          return RealmBrowseResult.failure("ENTITLEMENT_UNAVAILABLE");
        }
        if (authorization != NonPublicRealmAuthorization.AUTHORIZED) {
          continue;
        }
      }
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

  private NonPublicRealmAuthorization authorizeNonPublicRealm(
      SessionContext sessionContext,
      GameplayWorldCatalog.WorldView world,
      GameplayWorldCatalog.RealmView realm,
      String requestId) {
    if (accountClient == null) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    GetTenantMembershipForRuntimeResponse membershipResponse =
        accountClient.getTenantMembershipForRuntime(
            Long.toString(sessionContext.accountId()), Long.toString(realm.tenantId()), requestId);
    if (membershipResponse == null) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    if (membershipResponse.hasError()) {
      if (!StringUtils.hasText(membershipResponse.getError().getCode())) {
        return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
      }
      return isAuthorityUnavailable(membershipResponse.getError().getCode())
          ? NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE
          : NonPublicRealmAuthorization.DENIED;
    }
    if (!membershipResponse.getMembershipExists()
        || !membershipResponse.getGameplayAdmissionAllowed()
        || "INACTIVE".equalsIgnoreCase(membershipResponse.getMembershipLifecycleState())) {
      return NonPublicRealmAuthorization.ENROLLMENT_REQUIRED;
    }
    if (!isValidActiveMembership(membershipResponse, sessionContext, realm)) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }

    GetRealmAccessGrantForRuntimeResponse grantResponse =
        accountClient.getRealmAccessGrantForRuntime(
            Long.toString(sessionContext.accountId()),
            Long.toString(realm.tenantId()),
            world.slug(),
            realm.slug(),
            requestId);
    if (grantResponse == null) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    if (grantResponse.hasError()) {
      if (!StringUtils.hasText(grantResponse.getError().getCode())) {
        return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
      }
      return isAuthorityUnavailable(grantResponse.getError().getCode())
          ? NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE
          : NonPublicRealmAuthorization.DENIED;
    }
    if (!grantResponse.getGranted()) {
      return NonPublicRealmAuthorization.DENIED;
    }
    if (!isValidGrant(grantResponse, sessionContext, world, realm)) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }

    GetTenantEntitlementsForRuntimeResponse entitlementResponse =
        accountClient.getTenantEntitlementsForRuntime(
            Long.toString(realm.tenantId()), requestId);
    if (entitlementResponse == null) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    if (entitlementResponse.hasError()) {
      String errorCode = entitlementResponse.getError().getCode();
      if (!StringUtils.hasText(errorCode)) {
        return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
      }
      if (GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE.equalsIgnoreCase(errorCode)) {
        return NonPublicRealmAuthorization.ENTITLEMENT_UNAVAILABLE;
      }
      return isAuthorityUnavailable(errorCode)
          ? NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE
          : NonPublicRealmAuthorization.DENIED;
    }
    if (!isValidEntitlement(entitlementResponse, realm)) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    return entitlementResponse.getGameplayAvailable()
        ? NonPublicRealmAuthorization.AUTHORIZED
        : NonPublicRealmAuthorization.DENIED;
  }

  private boolean isValidEntitlement(
      GetTenantEntitlementsForRuntimeResponse response,
      GameplayWorldCatalog.RealmView realm) {
    return hasMatchingTenantId(response.getTenantId(), realm.tenantId())
        && isInstant(response.getEvaluatedAt());
  }

  private boolean hasMatchingTenantId(String tenantId, long expectedTenantId) {
    try {
      return Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private boolean isValidActiveMembership(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext sessionContext,
      GameplayWorldCatalog.RealmView realm) {
    return "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())
        && response.getMembershipVersion() > 0L
        && response.getMembershipAuthorityGeneration() > 0L
        && hasMatchingAuthorityIdentity(
            response.getAccountId(),
            response.getTenantId(),
            sessionContext.accountId(),
            realm.tenantId())
        && isInstant(response.getEvaluatedAt());
  }

  private boolean isValidGrant(
      GetRealmAccessGrantForRuntimeResponse response,
      SessionContext sessionContext,
      GameplayWorldCatalog.WorldView world,
      GameplayWorldCatalog.RealmView realm) {
    return response.getGrantVersion() > 0L
        && hasMatchingAuthorityIdentity(
            response.getAccountId(),
            response.getTenantId(),
            sessionContext.accountId(),
            realm.tenantId())
        && world.slug().equals(response.getWorldSlug())
        && realm.slug().equals(response.getRealmSlug())
        && isInstant(response.getEvaluatedAt());
  }

  private boolean hasMatchingAuthorityIdentity(
      String accountId, String tenantId, long expectedAccountId, long expectedTenantId) {
    try {
      return Long.parseLong(accountId) == expectedAccountId
          && Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private boolean isInstant(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    try {
      Instant.parse(value);
      return true;
    } catch (DateTimeParseException ex) {
      return false;
    }
  }

  private boolean isAuthorityUnavailable(String code) {
    return AuthenticationErrorCodes.UNAVAILABLE.equalsIgnoreCase(code)
        || "UNAVAILABLE".equalsIgnoreCase(code);
  }

  private enum NonPublicRealmAuthorization {
    AUTHORIZED,
    DENIED,
    ENROLLMENT_REQUIRED,
    AUTHORITY_UNAVAILABLE,
    ENTITLEMENT_UNAVAILABLE
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
    Optional<GameplayWorldCatalog.WorldView> maybeWorld = worldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isEmpty()) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (!worldCatalog.hasValidPublicProductionRealm(maybeWorld.orElseThrow())) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
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
    if (sessionContext.accountId() <= 0 || sessionContext.sessionId() <= 0) {
      return CharacterBrowseResult.failure("LOGIN_REQUIRED");
    }
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
    if (!worldCatalog.hasValidPublicProductionRealm(realm.tenantId())
        || !hasCompleteSelectedRealmPointerEvidence(realm)) {
      return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }

    String requestId = sessionContext.sessionId() + ":" + UUID.randomUUID();
    CharacterBrowseAuthorization authorization =
        realm.publicProductionRealm()
            ? authorizePublicCharacterBrowse(sessionContext, realm, requestId)
            : mapNonPublicCharacterAuthorization(
                authorizeNonPublicRealm(sessionContext, world, realm, requestId));
    if (authorization != CharacterBrowseAuthorization.AUTHORIZED) {
      return CharacterBrowseResult.failure(authorization.code());
    }

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

  private CharacterBrowseAuthorization authorizePublicCharacterBrowse(
      SessionContext sessionContext,
      GameplayWorldCatalog.RealmView realm,
      String requestId) {
    if (accountClient == null) {
      return CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
    }
    GetTenantMembershipForRuntimeResponse membershipResponse =
        accountClient.getTenantMembershipForRuntime(
            Long.toString(sessionContext.accountId()), Long.toString(realm.tenantId()), requestId);
    if (membershipResponse == null) {
      return CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
    }
    if (membershipResponse.hasError()) {
      String errorCode = membershipResponse.getError().getCode();
      if (!StringUtils.hasText(errorCode)) {
        return CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
      }
      return isAuthorityUnavailable(errorCode)
          ? CharacterBrowseAuthorization.AUTH_UNAVAILABLE
          : CharacterBrowseAuthorization.DENIED;
    }
    if (!isValidMembershipAuthoritySnapshot(membershipResponse, sessionContext, realm)) {
      return CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
    }

    PublicEntitlementAuthorization entitlementAuthorization =
        authorizePublicCharacterEntitlement(realm, requestId);
    if (entitlementAuthorization.authorization() != CharacterBrowseAuthorization.AUTHORIZED) {
      return entitlementAuthorization.authorization();
    }
    if (!membershipResponse.getMembershipExists()
        || !membershipResponse.getGameplayAdmissionAllowed()
        || !"ACTIVE".equalsIgnoreCase(membershipResponse.getMembershipLifecycleState())) {
      return entitlementAuthorization.allowPublicJoin()
          ? CharacterBrowseAuthorization.JOIN_REQUIRED
          : CharacterBrowseAuthorization.PUBLIC_PRODUCTION_ADMISSION_DENIED;
    }
    if (!isValidActiveMembership(membershipResponse, sessionContext, realm)) {
      return CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
    }
    return CharacterBrowseAuthorization.AUTHORIZED;
  }

  private PublicEntitlementAuthorization authorizePublicCharacterEntitlement(
      GameplayWorldCatalog.RealmView realm, String requestId) {
    if (accountClient == null) {
      return new PublicEntitlementAuthorization(
          CharacterBrowseAuthorization.AUTH_UNAVAILABLE, false);
    }
    GetTenantEntitlementsForRuntimeResponse entitlementResponse =
        accountClient.getTenantEntitlementsForRuntime(Long.toString(realm.tenantId()), requestId);
    if (entitlementResponse == null) {
      return new PublicEntitlementAuthorization(
          CharacterBrowseAuthorization.AUTH_UNAVAILABLE, false);
    }
    if (entitlementResponse.hasError()) {
      String errorCode = entitlementResponse.getError().getCode();
      if (!StringUtils.hasText(errorCode)) {
        return new PublicEntitlementAuthorization(
            CharacterBrowseAuthorization.AUTH_UNAVAILABLE, false);
      }
      if (GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE.equalsIgnoreCase(errorCode)) {
        return new PublicEntitlementAuthorization(
            CharacterBrowseAuthorization.ENTITLEMENT_UNAVAILABLE, false);
      }
      return new PublicEntitlementAuthorization(
          isAuthorityUnavailable(errorCode)
              ? CharacterBrowseAuthorization.AUTH_UNAVAILABLE
              : CharacterBrowseAuthorization.DENIED,
          false);
    }
    if (!isValidEntitlement(entitlementResponse, realm)) {
      return new PublicEntitlementAuthorization(
          CharacterBrowseAuthorization.AUTH_UNAVAILABLE, false);
    }
    if (!entitlementResponse.getGameplayAvailable()) {
      return new PublicEntitlementAuthorization(
          CharacterBrowseAuthorization.TENANT_BILLING_BLOCKED, false);
    }
    return new PublicEntitlementAuthorization(
        CharacterBrowseAuthorization.AUTHORIZED, entitlementResponse.getAllowPublicJoin());
  }

  private CharacterBrowseAuthorization mapNonPublicCharacterAuthorization(
      NonPublicRealmAuthorization authorization) {
    return switch (authorization) {
      case AUTHORIZED -> CharacterBrowseAuthorization.AUTHORIZED;
      case DENIED -> CharacterBrowseAuthorization.DENIED;
      case ENROLLMENT_REQUIRED -> CharacterBrowseAuthorization.NON_PUBLIC_ENROLLMENT_REQUIRED;
      case AUTHORITY_UNAVAILABLE -> CharacterBrowseAuthorization.AUTH_UNAVAILABLE;
      case ENTITLEMENT_UNAVAILABLE -> CharacterBrowseAuthorization.ENTITLEMENT_UNAVAILABLE;
    };
  }

  private boolean isValidMembershipAuthoritySnapshot(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext sessionContext,
      GameplayWorldCatalog.RealmView realm) {
    if (!hasMatchingAuthorityIdentity(
            response.getAccountId(),
            response.getTenantId(),
            sessionContext.accountId(),
            realm.tenantId())
        || !isInstant(response.getEvaluatedAt())) {
      return false;
    }
    if (!response.getMembershipExists()) {
      return response.getMembershipVersion() == 0L
          && response.getMembershipAuthorityGeneration() == 0L;
    }
    return response.getMembershipVersion() > 0L
        && response.getMembershipAuthorityGeneration() > 0L
        && StringUtils.hasText(response.getMembershipLifecycleState());
  }

  private boolean hasCompleteSelectedRealmPointerEvidence(
      GameplayWorldCatalog.RealmView realm) {
    return realm.visible()
        && realm.tenantId() > 0L
        && realm.gameInstanceId() > 0L
        && realm.pointerVersion() > 0L
        && StringUtils.hasText(realm.slug());
  }

  private enum CharacterBrowseAuthorization {
    AUTHORIZED(null),
    JOIN_REQUIRED("JOIN_REQUIRED"),
    DENIED("WORLD_ACCESS_DENIED"),
    PUBLIC_PRODUCTION_ADMISSION_DENIED("PUBLIC_PRODUCTION_ADMISSION_DENIED"),
    NON_PUBLIC_ENROLLMENT_REQUIRED("NON_PUBLIC_ENROLLMENT_REQUIRED"),
    TENANT_BILLING_BLOCKED("TENANT_BILLING_BLOCKED"),
    AUTH_UNAVAILABLE("AUTH_UNAVAILABLE"),
    ENTITLEMENT_UNAVAILABLE("ENTITLEMENT_UNAVAILABLE");

    private final String code;

    CharacterBrowseAuthorization(String code) {
      this.code = code;
    }

    private String code() {
      return code;
    }
  }

  private record PublicEntitlementAuthorization(
      CharacterBrowseAuthorization authorization, boolean allowPublicJoin) {}

  public sealed interface CharacterBrowseResult
      permits CharacterBrowseResult.Success,
          CharacterBrowseResult.InvalidWorld,
          CharacterBrowseResult.InvalidRealm,
          CharacterBrowseResult.RealmSelectionRequired,
          CharacterBrowseResult.Unavailable,
          CharacterBrowseResult.Failure {
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

    static CharacterBrowseResult failure(String code) {
      return new Failure(code);
    }

    record Success(CharacterBrowseViewOutput output) implements CharacterBrowseResult {}

    record InvalidWorld() implements CharacterBrowseResult {}

    record InvalidRealm(String worldSlug) implements CharacterBrowseResult {}

    record RealmSelectionRequired(String worldSlug) implements CharacterBrowseResult {}

    record Unavailable() implements CharacterBrowseResult {}

    record Failure(String code) implements CharacterBrowseResult {}
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
