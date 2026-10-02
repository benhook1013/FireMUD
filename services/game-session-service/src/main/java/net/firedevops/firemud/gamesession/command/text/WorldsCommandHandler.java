package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
import net.firedevops.firemud.gamesession.service.PositiveLongParsing;
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
    this.connectScopeSessionStore = null;
  }

  public WorldsViewOutput browseView() {
    return worldCatalog.browseView();
  }

  public WorldsViewOutput browseView(
      String transportSessionId, Optional<SessionContext> maybeCaller) {
    long sessionId = requireTransportSessionId(transportSessionId);
    Objects.requireNonNull(maybeCaller, "maybeCaller must not be null");
    if (maybeCaller.isPresent() && maybeCaller.orElseThrow().sessionId() != sessionId) {
      throw new DirectTextConnectScopeSessionStore.ConflictingIdentityException(
          "transport session did not match authenticated session");
    }
    if (connectScopeSessionStore == null) {
      throw new DirectTextConnectScopeSessionStore.StoreUnavailableException(
          "lobby snapshot storage is unavailable");
    }
    GameplayWorldCatalog.DiscoverySnapshot snapshot = worldCatalog.readDiscoverySnapshot();
    connectScopeSessionStore.replaceWorldSnapshot(
        sessionId,
        maybeCaller.map(SessionContext::accountId).orElse(0L),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        Instant.now());
    return snapshot.output();
  }

  public java.util.Optional<RealmBrowseViewOutput> browseRealms(String worldSelector) {
    return worldCatalog.browseRealms(worldSelector);
  }

  public RealmBrowseResult browseRealms(SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    return browseRealms(Long.toString(sessionContext.sessionId()), sessionContext, worldSelector);
  }

  public RealmBrowseResult browseRealms(
      String transportSessionId, SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    OptionalLong parsedSessionId = parseTransportSessionId(transportSessionId);
    if (sessionContext.accountId() <= 0 || parsedSessionId.isEmpty()) {
      return RealmBrowseResult.failure("LOGIN_REQUIRED");
    }
    if (parsedSessionId.getAsLong() != sessionContext.sessionId()) {
      return RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    GameplayWorldCatalog.DiscoverySnapshot catalogSnapshot;
    try {
      catalogSnapshot = worldCatalog.readDiscoverySnapshot();
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid) {
      return RealmBrowseResult.invalidSelector();
    }
    if (selection instanceof WorldSelectorResolution.Stale) {
      return RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Unavailable) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }

    GameplayWorldCatalog.WorldView world = ((WorldSelectorResolution.Selected) selection).world();
    if (!worldCatalog.hasValidPublicProductionRealm(catalogSnapshot, world)) {
      return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    List<RealmBrowseViewOutput.RealmEntry> visibleEntries = new ArrayList<>();
    List<GameplayWorldCatalog.RealmView> responseRealms = new ArrayList<>();
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
        if (accountClient == null) {
          return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
        }
        DirectTextConnectScopeTarget target = connectScopeTarget(world, realm);
        if (target == null) {
          return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
        }
        PlayerExecutionContext playerContext = playerContext(sessionContext, realm, requestId);
        IssueDirectTextConnectScopeResponse scopeResponse =
            accountClient.issueDirectTextConnectScope(playerContext, target);
        if (scopeResponse.hasError()) {
          String errorCode = scopeResponse.getError().getCode();
          if ("REALM_UNAVAILABLE".equals(errorCode)) {
            return RealmBrowseResult.failure("REALM_UNAVAILABLE");
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
      responseRealms.add(realm);
    }

    // A private-only world is retained for granted admission, but a denied selector must be
    // indistinguishable from an unknown world and must not write lobby scope state.
    if (responseRealms.isEmpty() && !worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)) {
      return RealmBrowseResult.invalidSelector();
    }
    if (connectScopeSessionStore == null) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    try {
      connectScopeSessionStore.clearWorldScopes(sessionContext, worldTenantId(world), world.slug());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }

    try {
      GameplayWorldCatalog.RealmDiscoverySnapshot realmSnapshot =
          worldCatalog.realmDiscoverySnapshot(world, responseRealms);
      connectScopeSessionStore.replaceRealmSnapshot(
          sessionContext,
          worldSelector,
          worldTenantId(world),
          world.slug(),
          realmSnapshot.catalogFingerprint(),
          realmSnapshot.ordinalTargets(),
          issuedScopes,
          Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    return RealmBrowseResult.success(new RealmBrowseViewOutput(world.slug(), visibleEntries));
  }

  private WorldSelectorResolution resolveLobbyWorld(
      SessionContext caller,
      String selector,
      GameplayWorldCatalog.DiscoverySnapshot currentCatalog) {
    if (selector == null || selector.isBlank()) {
      return new WorldSelectorResolution.Invalid();
    }
    if (!GameplayWorldCatalog.isOrdinalSelector(selector)) {
      return worldCatalog
          .resolveStableWorld(currentCatalog, selector)
          .<WorldSelectorResolution>map(WorldSelectorResolution.Selected::new)
          .orElseGet(WorldSelectorResolution.Invalid::new);
    }
    if (connectScopeSessionStore == null) {
      return new WorldSelectorResolution.Unavailable();
    }
    Optional<DirectTextConnectScopeSessionStore.WorldsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot = connectScopeSessionStore.worldsSnapshot(caller, Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new WorldSelectorResolution.Unavailable();
    }
    if (maybeSnapshot.isEmpty()) {
      return new WorldSelectorResolution.Stale();
    }
    DirectTextConnectScopeSessionStore.WorldsSnapshot worldsSnapshot = maybeSnapshot.orElseThrow();
    if (!worldsSnapshot.catalogFingerprint().equals(currentCatalog.catalogFingerprint())) {
      return new WorldSelectorResolution.Stale();
    }
    int ordinal;
    try {
      ordinal = Integer.parseInt(selector.trim());
    } catch (NumberFormatException ex) {
      return new WorldSelectorResolution.Stale();
    }
    Optional<DirectTextConnectScopeSessionStore.WorldOrdinalTarget> maybeTarget =
        worldsSnapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == ordinal)
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new WorldSelectorResolution.Stale();
    }
    return worldCatalog
        .resolveSnapshotOrdinal(currentCatalog, maybeTarget.orElseThrow())
        .<WorldSelectorResolution>map(WorldSelectorResolution.Selected::new)
        .orElseGet(WorldSelectorResolution.Stale::new);
  }

  private static OptionalLong parseTransportSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return OptionalLong.empty();
    }
    String trimmed = sessionId.trim();
    if (!trimmed.chars().allMatch(Character::isDigit)) {
      return OptionalLong.empty();
    }
    try {
      long parsed = Long.parseLong(trimmed);
      return parsed > 0L ? OptionalLong.of(parsed) : OptionalLong.empty();
    } catch (NumberFormatException ex) {
      return OptionalLong.empty();
    }
  }

  private static long requireTransportSessionId(String sessionId) {
    return parseTransportSessionId(sessionId)
        .orElseThrow(
            () ->
                new IllegalArgumentException("positive numeric transport session ID is required"));
  }

  private RealmSelectorResolution resolveLobbyRealm(
      SessionContext caller,
      GameplayWorldCatalog.WorldView world,
      String selector,
      GameplayWorldCatalog.RealmDiscoverySnapshot currentCatalog) {
    if (!StringUtils.hasText(selector)) {
      return new RealmSelectorResolution.NoSelection();
    }
    if (!GameplayWorldCatalog.isOrdinalSelector(selector)) {
      return worldCatalog
          .resolveRealm(world, selector)
          .<RealmSelectorResolution>map(RealmSelectorResolution.Selected::new)
          .orElseGet(RealmSelectorResolution.Invalid::new);
    }
    if (connectScopeSessionStore == null) {
      return new RealmSelectorResolution.Unavailable();
    }
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot =
          connectScopeSessionStore.realmsSnapshot(
              caller, worldTenantId(world), world.slug(), Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new RealmSelectorResolution.Unavailable();
    }
    if (maybeSnapshot.isEmpty()) {
      return new RealmSelectorResolution.Stale();
    }
    DirectTextConnectScopeSessionStore.RealmsSnapshot snapshot = maybeSnapshot.orElseThrow();
    if (!snapshot.catalogFingerprint().equals(currentCatalog.catalogFingerprint())) {
      return new RealmSelectorResolution.Stale();
    }
    int ordinal;
    try {
      ordinal = Integer.parseInt(selector.trim());
    } catch (NumberFormatException ex) {
      return new RealmSelectorResolution.Stale();
    }
    Optional<DirectTextConnectScopeSessionStore.RealmOrdinalTarget> maybeTarget =
        snapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == ordinal)
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new RealmSelectorResolution.Stale();
    }
    return worldCatalog
        .resolveRealmSnapshotOrdinal(world, currentCatalog, maybeTarget.orElseThrow())
        .<RealmSelectorResolution>map(RealmSelectorResolution.Selected::new)
        .orElseGet(RealmSelectorResolution.Stale::new);
  }

  private sealed interface RealmSelectorResolution
      permits RealmSelectorResolution.Selected,
          RealmSelectorResolution.NoSelection,
          RealmSelectorResolution.Invalid,
          RealmSelectorResolution.Stale,
          RealmSelectorResolution.Unavailable {
    record Selected(GameplayWorldCatalog.RealmView realm) implements RealmSelectorResolution {}

    record NoSelection() implements RealmSelectorResolution {}

    record Invalid() implements RealmSelectorResolution {}

    record Stale() implements RealmSelectorResolution {}

    record Unavailable() implements RealmSelectorResolution {}
  }

  private sealed interface WorldSelectorResolution {
    record Selected(GameplayWorldCatalog.WorldView world) implements WorldSelectorResolution {}

    record Invalid() implements WorldSelectorResolution {}

    record Stale() implements WorldSelectorResolution {}

    record Unavailable() implements WorldSelectorResolution {}
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
    if (!isValidMembershipAuthoritySnapshot(membershipResponse, sessionContext, realm)) {
      return NonPublicRealmAuthorization.AUTHORITY_UNAVAILABLE;
    }
    if (!membershipResponse.getMembershipExists()
        || "INACTIVE".equalsIgnoreCase(membershipResponse.getMembershipLifecycleState())) {
      return NonPublicRealmAuthorization.ENROLLMENT_REQUIRED;
    }
    if (!membershipResponse.getGameplayAdmissionAllowed()) {
      return NonPublicRealmAuthorization.MEMBERSHIP_DENIED;
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
        accountClient.getTenantEntitlementsForRuntime(Long.toString(realm.tenantId()), requestId);
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
      return NonPublicRealmAuthorization.ENTITLEMENT_UNAVAILABLE;
    }
    return entitlementResponse.getGameplayAvailable()
        ? NonPublicRealmAuthorization.AUTHORIZED
        : NonPublicRealmAuthorization.DENIED;
  }

  private boolean isValidEntitlement(
      GetTenantEntitlementsForRuntimeResponse response, GameplayWorldCatalog.RealmView realm) {
    return hasMatchingTenantId(response.getTenantId(), realm.tenantId())
        && response.getEntitlementVersion() > 0L
        && response.getTenantBillingSequence() > 0L
        && isFreshAuthorityEvaluation(response.getEvaluatedAt());
  }

  private boolean isFreshAuthorityEvaluation(String evaluatedAt) {
    if (!StringUtils.hasText(evaluatedAt)) {
      return false;
    }
    try {
      Instant evaluated = Instant.parse(evaluatedAt);
      Instant now = Instant.now();
      return !evaluated.isAfter(now) && !evaluated.isBefore(now.minusSeconds(15));
    } catch (DateTimeParseException ex) {
      return false;
    }
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
        && isFreshAuthorityEvaluation(response.getEvaluatedAt());
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
        && isFreshAuthorityEvaluation(response.getEvaluatedAt());
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

  private boolean isAuthorityUnavailable(String code) {
    return AuthenticationErrorCodes.UNAVAILABLE.equalsIgnoreCase(code)
        || "UNAVAILABLE".equalsIgnoreCase(code);
  }

  private enum NonPublicRealmAuthorization {
    AUTHORIZED,
    DENIED,
    MEMBERSHIP_DENIED,
    ENROLLMENT_REQUIRED,
    AUTHORITY_UNAVAILABLE,
    ENTITLEMENT_UNAVAILABLE
  }

  public JoinMembershipResult joinPublicProductionMembership(
      SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    return joinPublicProductionMembership(
        Long.toString(sessionContext.sessionId()), sessionContext, worldSelector);
  }

  public JoinMembershipResult joinPublicProductionMembership(
      String transportSessionId, SessionContext sessionContext, String worldSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    OptionalLong parsedSessionId = parseTransportSessionId(transportSessionId);
    if (parsedSessionId.isEmpty() || parsedSessionId.getAsLong() != sessionContext.sessionId()) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (sessionContext.accountId() <= 0 || sessionContext.sessionId() <= 0) {
      return JoinMembershipResult.failure("LOGIN_REQUIRED");
    }
    if (accountClient == null || connectScopeSessionStore == null) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    GameplayWorldCatalog.DiscoverySnapshot catalogSnapshot;
    try {
      catalogSnapshot = worldCatalog.readDiscoverySnapshot();
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid
        || selection instanceof WorldSelectorResolution.Stale) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Unavailable) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    GameplayWorldCatalog.WorldView world = ((WorldSelectorResolution.Selected) selection).world();
    GameplayWorldCatalog.RealmDiscoverySnapshot currentRealmCatalog;
    try {
      currentRealmCatalog = worldCatalog.readRealmDiscoverySnapshot(world);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeRealmSnapshot;
    try {
      maybeRealmSnapshot =
          connectScopeSessionStore.realmsSnapshot(
              sessionContext, worldTenantId(world), world.slug(), Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    if (maybeRealmSnapshot.isEmpty()
        || !maybeRealmSnapshot
            .orElseThrow()
            .catalogFingerprint()
            .equals(currentRealmCatalog.catalogFingerprint())
        || !normalizeSelector(worldSelector)
            .equals(maybeRealmSnapshot.orElseThrow().requestedWorldSelector())) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    Optional<DirectTextConnectScopeSessionStore.JoinScope> maybeJoinScope;
    try {
      maybeJoinScope =
          connectScopeSessionStore.publicProductionScopeForJoin(
              sessionContext, worldSelector, worldTenantId(world), world.slug(), Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    if (maybeJoinScope.isEmpty()) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    DirectTextConnectScopeSessionStore.JoinScope joinScope = maybeJoinScope.orElseThrow();
    DirectTextConnectScopeSessionStore.ScopedRealm scope = joinScope.scope();
    long tenantId;
    try {
      tenantId = Long.parseLong(scope.playerContext().getTenantId());
    } catch (NumberFormatException ex) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    if (tenantId != worldTenantId(world)) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    boolean hasPublicProductionRealm;
    try {
      hasPublicProductionRealm = worldCatalog.hasValidPublicProductionRealm(tenantId);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    if (!hasPublicProductionRealm) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    String requestId = joinScope.requestId();
    PlayerExecutionContext playerContext =
        scope.playerContext().toBuilder().setRequestId(requestId).build();
    return JoinMembershipResult.response(
        accountClient.joinPublicProductionMembership(
            playerContext, scope.connectScopeId(), requestId));
  }

  private static long worldTenantId(GameplayWorldCatalog.WorldView world) {
    List<Long> tenantIds =
        world.realms().stream().map(GameplayWorldCatalog.RealmView::tenantId).distinct().toList();
    return tenantIds.size() == 1 ? tenantIds.getFirst() : -1L;
  }

  private static String normalizeSelector(String selector) {
    return selector == null ? "" : selector.trim().toLowerCase(java.util.Locale.ROOT);
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
      SessionContext caller, GameplayWorldCatalog.RealmView realm, String requestId) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(Long.toString(caller.accountId()))
        .setSessionId(Long.toString(caller.sessionId()))
        .setTenantId(Long.toString(realm.tenantId()))
        .setRealmId(realm.realmId().toString())
        .setPlayableStateNamespaceId(realm.playableStateNamespaceId().toString())
        .setPlayableStateScope(realm.stateScope())
        .setGameInstanceId(Long.toString(realm.gameInstanceId()))
        .setRequestId(requestId)
        .build();
  }

  private RealmBrowseViewOutput.RealmEntry realmEntry(
      int ordinal, GameplayWorldCatalog.RealmView realm) {
    return new RealmBrowseViewOutput.RealmEntry(
        ordinal,
        realm.slug(),
        realm.displayName(),
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
    return browseCharacters(
        Long.toString(sessionContext.sessionId()), sessionContext, worldSelector, realmSelector);
  }

  public CharacterBrowseResult browseCharacters(
      String transportSessionId,
      SessionContext sessionContext,
      String worldSelector,
      String realmSelector) {
    Objects.requireNonNull(sessionContext, "sessionContext must not be null");
    OptionalLong parsedSessionId = parseTransportSessionId(transportSessionId);
    if (sessionContext.accountId() <= 0 || parsedSessionId.isEmpty()) {
      return CharacterBrowseResult.failure("LOGIN_REQUIRED");
    }
    if (parsedSessionId.getAsLong() != sessionContext.sessionId()) {
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    GameplayWorldCatalog.DiscoverySnapshot catalogSnapshot;
    GameplayWorldCatalog.RealmDiscoverySnapshot currentRealmCatalog;
    try {
      catalogSnapshot = worldCatalog.readDiscoverySnapshot();
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid) {
      return CharacterBrowseResult.invalidWorld();
    }
    if (selection instanceof WorldSelectorResolution.Stale) {
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Unavailable) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    GameplayWorldCatalog.WorldView world = ((WorldSelectorResolution.Selected) selection).world();
    try {
      currentRealmCatalog = worldCatalog.readRealmDiscoverySnapshot(world);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    RealmSelectorResolution realmSelection =
        resolveLobbyRealm(sessionContext, world, realmSelector, currentRealmCatalog);
    if (realmSelection instanceof RealmSelectorResolution.Unavailable) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    if (realmSelection instanceof RealmSelectorResolution.Stale) {
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    java.util.Optional<GameplayWorldCatalog.RealmView> maybeRealm =
        realmSelection instanceof RealmSelectorResolution.Selected selected
            ? java.util.Optional.of(selected.realm())
            : StringUtils.hasText(realmSelector)
                ? java.util.Optional.empty()
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
    boolean currentPointerMatches;
    try {
      currentPointerMatches = worldCatalog.matchesCurrentAdmissionPointer(world, realm);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    if (!worldCatalog.hasValidPublicProductionRealm(catalogSnapshot, world)
        || !hasCompleteSelectedRealmPointerEvidence(realm)
        || !currentPointerMatches) {
      return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }

    String requestId = sessionContext.sessionId() + ":" + UUID.randomUUID();
    CharacterBrowseAuthorization authorization =
        realm.publicProductionRealm()
            ? authorizePublicCharacterBrowse(sessionContext, realm, requestId)
            : mapNonPublicCharacterAuthorization(
                authorizeNonPublicRealm(sessionContext, world, realm, requestId));
    if (authorization != CharacterBrowseAuthorization.AUTHORIZED) {
      if (!realm.publicProductionRealm() && authorization.isDefinitiveAccessDenial()) {
        return worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)
            ? CharacterBrowseResult.invalidRealm(world.slug())
            : CharacterBrowseResult.invalidWorld();
      }
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
    Set<String> characterIds = new HashSet<>();
    for (int i = 0; i < response.getCharactersCount(); i++) {
      net.firedevops.firemud.entitymanagement.v1.Character character = response.getCharacters(i);
      PositiveLongParsing.ParsedPositiveLong parsedCharacterId =
          PositiveLongParsing.parseOptionalText(character.getId(), "characterId");
      if (!Long.toString(realm.tenantId()).equals(character.getTenantId())
          || !Long.toString(sessionContext.accountId()).equals(character.getAccountId())
          || character.getPlayableStateScope() != toPlayableStateScope(realm)
          || !parsedCharacterId.valid()
          || !StringUtils.hasText(character.getName())
          || !characterIds.add(character.getId())) {
        return CharacterBrowseResult.unavailable();
      }
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
      SessionContext sessionContext, GameplayWorldCatalog.RealmView realm, String requestId) {
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
        || "INACTIVE".equalsIgnoreCase(membershipResponse.getMembershipLifecycleState())) {
      return entitlementAuthorization.allowPublicJoin()
          ? CharacterBrowseAuthorization.JOIN_REQUIRED
          : CharacterBrowseAuthorization.PUBLIC_PRODUCTION_ADMISSION_DENIED;
    }
    if (!membershipResponse.getGameplayAdmissionAllowed()) {
      return CharacterBrowseAuthorization.PUBLIC_PRODUCTION_ADMISSION_DENIED;
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
          CharacterBrowseAuthorization.ENTITLEMENT_UNAVAILABLE, false);
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
      case DENIED, MEMBERSHIP_DENIED -> CharacterBrowseAuthorization.DENIED;
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
        || !isFreshAuthorityEvaluation(response.getEvaluatedAt())) {
      return false;
    }
    if (!response.getMembershipExists()) {
      return "MISSING".equalsIgnoreCase(response.getMembershipLifecycleState())
          && !response.getGameplayAdmissionAllowed()
          && response.getMembershipVersion() == 0L
          && response.getMembershipAuthorityGeneration() == 0L;
    }
    if (response.getMembershipVersion() <= 0L
        || response.getMembershipAuthorityGeneration() <= 0L) {
      return false;
    }
    if ("ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())) {
      return true;
    }
    return "INACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())
        && !response.getGameplayAdmissionAllowed();
  }

  private boolean hasCompleteSelectedRealmPointerEvidence(GameplayWorldCatalog.RealmView realm) {
    return realm.visible()
        && realm.tenantId() > 0L
        && realm.gameInstanceId() > 0L
        && realm.catalogRevision() > 0L
        && realm.pointerVersion() > 0L
        && realm.realmId() != null
        && realm.playableStateNamespaceId() != null
        && ("SHARED".equals(realm.stateScope()) || "ISOLATED".equals(realm.stateScope()))
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

    private boolean isDefinitiveAccessDenial() {
      return this == DENIED
          || this == PUBLIC_PRODUCTION_ADMISSION_DENIED
          || this == NON_PUBLIC_ENROLLMENT_REQUIRED;
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
