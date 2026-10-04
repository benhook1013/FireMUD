package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
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
  private final AccountClient accountClient;
  private final DirectTextConnectScopeSessionStore connectScopeSessionStore;
  private final Clock clock;

  @Autowired
  public WorldsCommandHandler(
      GameplayWorldCatalog worldCatalog,
      AccountClient accountClient,
      DirectTextConnectScopeSessionStore connectScopeSessionStore) {
    this(worldCatalog, accountClient, connectScopeSessionStore, Clock.systemUTC());
  }

  WorldsCommandHandler(
      GameplayWorldCatalog worldCatalog,
      AccountClient accountClient,
      DirectTextConnectScopeSessionStore connectScopeSessionStore,
      Clock clock) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.connectScopeSessionStore =
        Objects.requireNonNull(
            connectScopeSessionStore, "connectScopeSessionStore must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        maybeCaller.map(SessionContext::accountId).orElse(null),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        clock.instant());
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
    if (!sessionContext.hasAccountIdentity() || parsedSessionId.isEmpty()) {
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
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid) {
      try {
        // A tenant with invalid public-production cardinality is intentionally omitted from the
        // discovery snapshot. Recheck the no-caller public projection to distinguish that
        // fail-closed state from an ordinary unknown selector before returning INVALID_SELECTOR.
        worldCatalog.resolvePublicWorldFromAuthoritySnapshot(worldSelector);
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
      } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
        return RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
      }
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
        // The runtime grant reader is slug-only and cannot bind authority to this target's
        // lifecycle. Do not disclose or scope non-public entries until that proof is available.
        continue;
      }
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
      if (!expiresAt.isAfter(clock.instant())) {
        return RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
      }
      issuedScopes.add(
          new DirectTextConnectScopeSessionStore.ScopedRealm(
              realm.slug(), true, scopeResponse.getConnectScopeId(), expiresAt, playerContext));
      visibleEntries.add(realmEntry(visibleEntries.size() + 1, realm));
      responseRealms.add(realm);
    }

    // A world without a supported visible realm is indistinguishable from an unknown world and
    // must not write lobby scope state.
    if (responseRealms.isEmpty() && !worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)) {
      return RealmBrowseResult.invalidSelector();
    }
    if (connectScopeSessionStore == null) {
      return RealmBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    try {
      connectScopeSessionStore.clearWorldScopes(
          sessionContext,
          DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
          world.slug(),
          clock.instant());
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
          DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
          world.slug(),
          realmSnapshot.catalogFingerprint(),
          realmSnapshot.ordinalTargets(),
          issuedScopes,
          clock.instant());
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
    DirectTextOrdinalSelectionResolver.Resolution<GameplayWorldCatalog.WorldView> resolution =
        DirectTextOrdinalSelectionResolver.resolveWorldOrdinal(
            selector,
            connectScopeSessionStore,
            caller,
            clock.instant(),
            currentCatalog,
            worldCatalog);
    Optional<GameplayWorldCatalog.WorldView> selected = resolution.selectedValue();
    if (selected.isPresent()) {
      return new WorldSelectorResolution.Selected(selected.orElseThrow());
    }
    return resolution instanceof DirectTextOrdinalSelectionResolver.Unavailable<?>
        ? new WorldSelectorResolution.Unavailable()
        : new WorldSelectorResolution.Stale();
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
    DirectTextOrdinalSelectionResolver.Resolution<GameplayWorldCatalog.RealmView> resolution =
        DirectTextOrdinalSelectionResolver.resolveRealmOrdinal(
            selector,
            connectScopeSessionStore,
            caller,
            DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
            world,
            clock.instant(),
            () -> currentCatalog,
            worldCatalog);
    Optional<GameplayWorldCatalog.RealmView> selected = resolution.selectedValue();
    if (selected.isPresent()) {
      return new RealmSelectorResolution.Selected(selected.orElseThrow());
    }
    if (resolution instanceof DirectTextOrdinalSelectionResolver.Unavailable<?>) {
      return new RealmSelectorResolution.Unavailable();
    }
    if (resolution instanceof DirectTextOrdinalSelectionResolver.PointerUnavailable<?>) {
      return new RealmSelectorResolution.PointerUnavailable();
    }
    return resolution instanceof DirectTextOrdinalSelectionResolver.UnboundSelector<?>
        ? new RealmSelectorResolution.Stale(RealmSelectorStaleReason.UNBOUND_SELECTOR)
        : new RealmSelectorResolution.Stale(RealmSelectorStaleReason.SNAPSHOT_MISMATCH);
  }

  private RealmSelectorResolution resolveOmittedLobbyRealm(
      SessionContext caller,
      GameplayWorldCatalog.DiscoverySnapshot currentWorldCatalog,
      GameplayWorldCatalog.WorldView world) {
    if (connectScopeSessionStore == null) {
      return new RealmSelectorResolution.Unavailable();
    }
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot =
          connectScopeSessionStore.realmsSnapshot(
              caller,
              DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
              world.slug(),
              clock.instant());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new RealmSelectorResolution.Unavailable();
    }
    if (maybeSnapshot.isPresent()) {
      DirectTextConnectScopeSessionStore.RealmsSnapshot snapshot = maybeSnapshot.orElseThrow();
      Optional<GameplayWorldCatalog.RealmDiscoverySnapshot> maybeCurrentCatalog;
      try {
        maybeCurrentCatalog =
            worldCatalog.revalidateRealmDiscoverySnapshot(world, snapshot.ordinalTargets());
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return new RealmSelectorResolution.Unavailable();
      }
      if (!snapshot.worldSlug().equalsIgnoreCase(world.slug())
          || snapshot.tenantId() != DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world)
          || maybeCurrentCatalog.isEmpty()
          || !snapshot
              .catalogFingerprint()
              .equals(maybeCurrentCatalog.orElseThrow().catalogFingerprint())) {
        return new RealmSelectorResolution.Stale(RealmSelectorStaleReason.SNAPSHOT_MISMATCH);
      }
      if (snapshot.ordinalTargets().size() > 1) {
        return new RealmSelectorResolution.RequiresSelection();
      }
      if (snapshot.ordinalTargets().isEmpty()) {
        return new RealmSelectorResolution.Stale(RealmSelectorStaleReason.SNAPSHOT_MISMATCH);
      }
      GameplayWorldCatalog.RealmDiscoverySnapshot currentRealmCatalog =
          maybeCurrentCatalog.orElseThrow();
      return worldCatalog
          .resolveRealmSnapshotOrdinal(
              world, currentRealmCatalog, snapshot.ordinalTargets().getFirst())
          .<RealmSelectorResolution>map(RealmSelectorResolution.Selected::new)
          .orElseGet(
              () -> new RealmSelectorResolution.Stale(RealmSelectorStaleReason.SNAPSHOT_MISMATCH));
    }

    // Without a caller-specific REALMS snapshot, only the canonical public default is a safe
    // omitted target. Private catalog cardinality is not evidence that the caller may discover or
    // select any of those realms.
    if (!worldCatalog.isPubliclyDiscoverable(currentWorldCatalog, world)) {
      return new RealmSelectorResolution.Invalid();
    }
    List<GameplayWorldCatalog.RealmView> publicRealms =
        worldCatalog.visibleRealms(world).stream()
            .filter(GameplayWorldCatalog.RealmView::publicProductionRealm)
            .toList();
    return publicRealms.size() == 1
        ? new RealmSelectorResolution.Selected(publicRealms.getFirst())
        : new RealmSelectorResolution.RequiresSelection();
  }

  private boolean isBoundToCurrentRealmSnapshot(
      SessionContext caller,
      GameplayWorldCatalog.WorldView world,
      GameplayWorldCatalog.RealmView realm) {
    if (connectScopeSessionStore == null) {
      return false;
    }
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot =
          connectScopeSessionStore.realmsSnapshot(
              caller,
              DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
              world.slug(),
              clock.instant());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return false;
    }
    if (maybeSnapshot.isEmpty()) {
      return false;
    }
    DirectTextConnectScopeSessionStore.RealmsSnapshot snapshot = maybeSnapshot.orElseThrow();
    if (!snapshot.worldSlug().equalsIgnoreCase(world.slug())
        || snapshot.tenantId()
            != DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world)) {
      return false;
    }
    Optional<GameplayWorldCatalog.RealmDiscoverySnapshot> maybeCurrentCatalog;
    try {
      maybeCurrentCatalog =
          worldCatalog.revalidateRealmDiscoverySnapshot(world, snapshot.ordinalTargets());
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return false;
    }
    if (maybeCurrentCatalog.isEmpty()
        || !snapshot
            .catalogFingerprint()
            .equals(maybeCurrentCatalog.orElseThrow().catalogFingerprint())) {
      return false;
    }
    GameplayWorldCatalog.RealmDiscoverySnapshot currentCatalog = maybeCurrentCatalog.orElseThrow();
    return snapshot.ordinalTargets().stream()
        .filter(target -> target.tenantId() == realm.tenantId())
        .filter(target -> target.realmSlug().equalsIgnoreCase(realm.slug()))
        .anyMatch(
            target ->
                worldCatalog
                    .resolveRealmSnapshotOrdinal(world, currentCatalog, target)
                    .filter(realm::equals)
                    .isPresent());
  }

  private enum RealmSelectorStaleReason {
    UNBOUND_SELECTOR,
    SNAPSHOT_MISMATCH
  }

  private sealed interface RealmSelectorResolution
      permits RealmSelectorResolution.Selected,
          RealmSelectorResolution.NoSelection,
          RealmSelectorResolution.Invalid,
          RealmSelectorResolution.RequiresSelection,
          RealmSelectorResolution.Stale,
          RealmSelectorResolution.Unavailable,
          RealmSelectorResolution.PointerUnavailable {
    record Selected(GameplayWorldCatalog.RealmView realm) implements RealmSelectorResolution {}

    record NoSelection() implements RealmSelectorResolution {}

    record Invalid() implements RealmSelectorResolution {}

    record RequiresSelection() implements RealmSelectorResolution {}

    record Stale(RealmSelectorStaleReason reason) implements RealmSelectorResolution {}

    record Unavailable() implements RealmSelectorResolution {}

    record PointerUnavailable() implements RealmSelectorResolution {}
  }

  private sealed interface WorldSelectorResolution {
    record Selected(GameplayWorldCatalog.WorldView world) implements WorldSelectorResolution {}

    record Invalid() implements WorldSelectorResolution {}

    record Stale() implements WorldSelectorResolution {}

    record Unavailable() implements WorldSelectorResolution {}
  }

  private boolean isAuthorityUnavailable(String code) {
    return AuthenticationErrorCodes.UNAVAILABLE.equalsIgnoreCase(code)
        || "UNAVAILABLE".equalsIgnoreCase(code);
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
    if (!sessionContext.hasAccountIdentity() || sessionContext.sessionId() <= 0L) {
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
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid) {
      try {
        worldCatalog.resolvePublicWorldFromAuthoritySnapshot(worldSelector);
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
      } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
        return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
      }
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Stale) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Unavailable) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    GameplayWorldCatalog.WorldView world = ((WorldSelectorResolution.Selected) selection).world();
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeRealmSnapshot;
    try {
      maybeRealmSnapshot =
          connectScopeSessionStore.realmsSnapshot(
              sessionContext,
              DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
              world.slug(),
              clock.instant());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    if (maybeRealmSnapshot.isEmpty()
        || !normalizeSelector(worldSelector)
            .equals(maybeRealmSnapshot.orElseThrow().requestedWorldSelector())) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    DirectTextConnectScopeSessionStore.RealmsSnapshot realmSnapshot =
        maybeRealmSnapshot.orElseThrow();
    Optional<GameplayWorldCatalog.RealmDiscoverySnapshot> revalidatedRealmCatalog;
    try {
      revalidatedRealmCatalog =
          worldCatalog.revalidateRealmDiscoverySnapshot(world, realmSnapshot.ordinalTargets());
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    }
    if (revalidatedRealmCatalog.isEmpty()
        || !realmSnapshot
            .catalogFingerprint()
            .equals(revalidatedRealmCatalog.orElseThrow().catalogFingerprint())) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    Optional<DirectTextConnectScopeSessionStore.JoinScope> maybeJoinScope;
    try {
      maybeJoinScope =
          connectScopeSessionStore.publicProductionScopeForJoin(
              sessionContext,
              worldSelector,
              DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world),
              world.slug(),
              clock.instant());
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
    if (tenantId != DirectTextOrdinalSelectionResolver.worldTenantIdOrInvalid(world)) {
      return JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    boolean hasPublicProductionRealm;
    try {
      hasPublicProductionRealm = worldCatalog.hasValidPublicProductionRealm(tenantId);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return JoinMembershipResult.failure("AUTH_UNAVAILABLE");
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    if (!hasPublicProductionRealm) {
      return JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    String requestId = joinScope.requestId();
    PlayerExecutionContext playerContext =
        scope.playerContext().toBuilder().setRequestId(requestId).build();
    return JoinMembershipResult.response(
        accountClient.joinPublicProductionMembership(
            playerContext, scope.connectScopeId(), requestId, scope.expiresAt()));
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
        .setAccountId(caller.accountId())
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
    if (!sessionContext.hasAccountIdentity() || parsedSessionId.isEmpty()) {
      return CharacterBrowseResult.failure("LOGIN_REQUIRED");
    }
    if (parsedSessionId.getAsLong() != sessionContext.sessionId()) {
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    GameplayWorldCatalog.DiscoverySnapshot catalogSnapshot;
    try {
      catalogSnapshot = worldCatalog.readDiscoverySnapshot();
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    WorldSelectorResolution selection =
        resolveLobbyWorld(sessionContext, worldSelector, catalogSnapshot);
    if (selection instanceof WorldSelectorResolution.Invalid) {
      try {
        worldCatalog.resolvePublicWorldFromAuthoritySnapshot(worldSelector);
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
      } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
        return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
      }
      return CharacterBrowseResult.invalidWorld();
    }
    if (selection instanceof WorldSelectorResolution.Stale) {
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (selection instanceof WorldSelectorResolution.Unavailable) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    GameplayWorldCatalog.WorldView world = ((WorldSelectorResolution.Selected) selection).world();
    // Redact private-only targets before CHARS can reveal realm-selection hints.
    if (!worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)) {
      return CharacterBrowseResult.invalidWorld();
    }
    GameplayWorldCatalog.RealmDiscoverySnapshot currentRealmCatalog;
    try {
      currentRealmCatalog = worldCatalog.readRealmDiscoverySnapshot(world);
    } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    } catch (GameplayWorldCatalog.AuthorityPointerUnavailableException ex) {
      return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    RealmSelectorResolution realmSelection =
        resolveLobbyRealm(sessionContext, world, realmSelector, currentRealmCatalog);
    if (realmSelection instanceof RealmSelectorResolution.NoSelection) {
      realmSelection = resolveOmittedLobbyRealm(sessionContext, catalogSnapshot, world);
    }
    if (realmSelection instanceof RealmSelectorResolution.Unavailable) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    if (realmSelection instanceof RealmSelectorResolution.PointerUnavailable) {
      return CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE");
    }
    if (realmSelection instanceof RealmSelectorResolution.Stale stale) {
      if (stale.reason() == RealmSelectorStaleReason.UNBOUND_SELECTOR
          && !worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)) {
        return CharacterBrowseResult.invalidWorld();
      }
      return CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH");
    }
    if (realmSelection instanceof RealmSelectorResolution.RequiresSelection) {
      return CharacterBrowseResult.realmSelectionRequired(world.slug());
    }
    if (realmSelection instanceof RealmSelectorResolution.Invalid) {
      return StringUtils.hasText(realmSelector)
              && worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)
          ? CharacterBrowseResult.invalidRealm(world.slug())
          : CharacterBrowseResult.invalidWorld();
    }

    GameplayWorldCatalog.RealmView realm =
        ((RealmSelectorResolution.Selected) realmSelection).realm();
    if (!realm.publicProductionRealm()
        && !isBoundToCurrentRealmSnapshot(sessionContext, world, realm)) {
      // A fresh caller-bound snapshot establishes knowledge of the target, not grant authority.
      return worldCatalog.isPubliclyDiscoverable(catalogSnapshot, world)
          ? CharacterBrowseResult.invalidRealm(world.slug())
          : CharacterBrowseResult.invalidWorld();
    }
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
    if (!realm.publicProductionRealm()) {
      return CharacterBrowseResult.failure("AUTH_UNAVAILABLE");
    }
    // The current Entity roster API is not qualified by the stable playable-state namespace.
    // Keep CHARS closed until that actor-identity contract is available.
    return CharacterBrowseResult.unavailable();
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

  public sealed interface CharacterBrowseResult
      permits CharacterBrowseResult.InvalidWorld,
          CharacterBrowseResult.InvalidRealm,
          CharacterBrowseResult.RealmSelectionRequired,
          CharacterBrowseResult.Unavailable,
          CharacterBrowseResult.Failure {
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

    record InvalidWorld() implements CharacterBrowseResult {}

    record InvalidRealm(String worldSlug) implements CharacterBrowseResult {}

    record RealmSelectionRequired(String worldSlug) implements CharacterBrowseResult {}

    record Unavailable() implements CharacterBrowseResult {}

    record Failure(String code) implements CharacterBrowseResult {}
  }
}
