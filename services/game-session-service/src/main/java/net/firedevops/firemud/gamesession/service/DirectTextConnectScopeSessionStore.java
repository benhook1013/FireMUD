package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Holds Account-issued direct-text scopes only for the transport session that resolved them. */
@Component
public final class DirectTextConnectScopeSessionStore {
  private final ConcurrentMap<Long, SessionScopes> scopesBySessionId = new ConcurrentHashMap<>();

  public void replaceWorldScopes(
      SessionContext caller,
      String requestedWorldSelector,
      String canonicalWorldSlug,
      List<ScopedRealm> scopes) {
    Objects.requireNonNull(caller, "caller must not be null");
    requireCallerIdentity(caller);
    String requestedSelector = normalizeSelector(requestedWorldSelector);
    String worldSlug = normalizeSelector(canonicalWorldSlug);
    if (requestedSelector == null || worldSlug == null) {
      throw new IllegalArgumentException("world selectors must not be blank");
    }
    List<ScopedRealm> safeScopes =
        List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    if (safeScopes.stream().anyMatch(scope -> scope.playerContext().getAccountId().isBlank())) {
      throw new IllegalArgumentException("scope caller identity must not be blank");
    }
    safeScopes.forEach(
        scope -> {
          if (!scope.playerContext().getAccountId().equals(Long.toString(caller.accountId()))
              || !scope.playerContext().getSessionId().equals(Long.toString(caller.sessionId()))) {
            throw new IllegalArgumentException("scope caller identity did not match session");
          }
        });

    scopesBySessionId.compute(
        caller.sessionId(),
        (sessionId, existing) -> {
          Map<String, List<ScopedRealm>> byWorld = new HashMap<>();
          Map<String, String> worldBySelector = new HashMap<>();
          if (existing != null && existing.accountId() == caller.accountId()) {
            byWorld.putAll(existing.scopesByWorld());
            worldBySelector.putAll(existing.worldBySelector());
          }
          byWorld.put(worldSlug, safeScopes);
          worldBySelector.put(worldSlug, worldSlug);
          worldBySelector.put(requestedSelector, worldSlug);
          return new SessionScopes(
              caller.accountId(), Map.copyOf(byWorld), Map.copyOf(worldBySelector));
        });
  }

  public void clearWorldScopes(SessionContext caller, String worldSelector) {
    Objects.requireNonNull(caller, "caller must not be null");
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return;
    }
    scopesBySessionId.computeIfPresent(
        caller.sessionId(),
        (sessionId, existing) -> {
          if (existing.accountId() != caller.accountId()) {
            return null;
          }
          String worldSlug = existing.worldBySelector().get(selector);
          if (worldSlug == null) {
            return existing;
          }
          Map<String, List<ScopedRealm>> byWorld = new HashMap<>(existing.scopesByWorld());
          byWorld.remove(worldSlug);
          Map<String, String> worldBySelector = new HashMap<>(existing.worldBySelector());
          worldBySelector.entrySet().removeIf(entry -> worldSlug.equals(entry.getValue()));
          if (byWorld.isEmpty()) {
            return null;
          }
          return new SessionScopes(
              existing.accountId(), Map.copyOf(byWorld), Map.copyOf(worldBySelector));
        });
  }

  public Optional<ScopedRealm> publicProductionScope(
      SessionContext caller, String worldSelector, Instant now) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireCallerIdentity(caller);
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return Optional.empty();
    }
    SessionScopes sessionScopes = scopesBySessionId.get(caller.sessionId());
    if (sessionScopes == null || sessionScopes.accountId() != caller.accountId()) {
      if (sessionScopes != null) {
        scopesBySessionId.remove(caller.sessionId(), sessionScopes);
      }
      return Optional.empty();
    }
    String canonicalWorldSlug = sessionScopes.worldBySelector().get(selector);
    if (canonicalWorldSlug == null) {
      return Optional.empty();
    }
    List<ScopedRealm> validScopes =
        sessionScopes.scopesByWorld().getOrDefault(canonicalWorldSlug, List.of()).stream()
            .filter(scope -> scope.expiresAt().isAfter(now))
            .toList();
    List<ScopedRealm> publicScopes =
        validScopes.stream().filter(ScopedRealm::publicProductionRealm).toList();
    return publicScopes.size() == 1 ? Optional.of(publicScopes.getFirst()) : Optional.empty();
  }

  /**
   * Resolves a public scope and atomically binds one stable JOIN request ID to that scope. Repeated
   * JOIN commands reuse the ID; a fresh REALMS response replaces the scope and starts a new logical
   * attempt.
   */
  public Optional<JoinScope> publicProductionScopeForJoin(
      SessionContext caller, String worldSelector, Instant now) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireCallerIdentity(caller);
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return Optional.empty();
    }
    AtomicReference<JoinScope> selected = new AtomicReference<>();
    scopesBySessionId.computeIfPresent(
        caller.sessionId(),
        (sessionId, sessionScopes) -> {
          if (sessionScopes.accountId() != caller.accountId()) {
            return sessionScopes;
          }
          String worldSlug = sessionScopes.worldBySelector().get(selector);
          if (worldSlug == null) {
            return sessionScopes;
          }
          List<ScopedRealm> scopes =
              sessionScopes.scopesByWorld().getOrDefault(worldSlug, List.of());
          List<ScopedRealm> validPublicScopes =
              scopes.stream()
                  .filter(scope -> scope.expiresAt().isAfter(now))
                  .filter(ScopedRealm::publicProductionRealm)
                  .toList();
          if (validPublicScopes.size() != 1) {
            return sessionScopes;
          }

          ScopedRealm scope = validPublicScopes.getFirst();
          String requestId = scope.joinRequestId();
          ScopedRealm boundScope = scope;
          if (requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            boundScope = scope.withJoinRequestId(requestId);
          }

          if (boundScope != scope) {
            ScopedRealm updatedScope = boundScope;
            List<ScopedRealm> updatedScopes =
                scopes.stream()
                    .map(
                        candidate ->
                            candidate.connectScopeId().equals(scope.connectScopeId())
                                ? updatedScope
                                : candidate)
                    .toList();
            Map<String, List<ScopedRealm>> byWorld = new HashMap<>(sessionScopes.scopesByWorld());
            byWorld.put(worldSlug, updatedScopes);
            sessionScopes =
                new SessionScopes(
                    sessionScopes.accountId(), byWorld, sessionScopes.worldBySelector());
          }
          selected.set(new JoinScope(boundScope, requestId));
          return sessionScopes;
        });
    return Optional.ofNullable(selected.get());
  }

  public void clearSession(long sessionId) {
    scopesBySessionId.remove(sessionId);
  }

  @Scheduled(fixedDelay = 60_000L)
  void removeExpiredScopes() {
    Instant now = Instant.now();
    scopesBySessionId.forEach(
        (sessionId, current) -> {
          Map<String, List<ScopedRealm>> remainingByWorld = new HashMap<>();
          current
              .scopesByWorld()
              .forEach(
                  (worldSlug, worldScopes) -> {
                    List<ScopedRealm> remaining =
                        worldScopes.stream()
                            .filter(scope -> scope.expiresAt().isAfter(now))
                            .toList();
                    if (!remaining.isEmpty()) {
                      remainingByWorld.put(worldSlug, remaining);
                    }
                  });
          if (remainingByWorld.isEmpty()) {
            scopesBySessionId.remove(sessionId, current);
            return;
          }
          Map<String, String> remainingSelectors = new HashMap<>(current.worldBySelector());
          remainingSelectors
              .entrySet()
              .removeIf(entry -> !remainingByWorld.containsKey(entry.getValue()));
          SessionScopes updated =
              new SessionScopes(
                  current.accountId(),
                  Map.copyOf(remainingByWorld),
                  Map.copyOf(remainingSelectors));
          scopesBySessionId.replace(sessionId, current, updated);
        });
  }

  private void requireCallerIdentity(SessionContext caller) {
    if (caller.accountId() <= 0 || caller.sessionId() <= 0) {
      throw new IllegalArgumentException(
          "authenticated account and transport session are required");
    }
  }

  private String normalizeSelector(String selector) {
    if (selector == null || selector.isBlank()) {
      return null;
    }
    return selector.trim().toLowerCase(Locale.ROOT);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf execution contexts are immutable value messages.")
  public record ScopedRealm(
      String realmSlug,
      boolean publicProductionRealm,
      String connectScopeId,
      Instant expiresAt,
      PlayerExecutionContext playerContext,
      String joinRequestId) {
    public ScopedRealm(
        String realmSlug,
        boolean publicProductionRealm,
        String connectScopeId,
        Instant expiresAt,
        PlayerExecutionContext playerContext) {
      this(realmSlug, publicProductionRealm, connectScopeId, expiresAt, playerContext, "");
    }

    public ScopedRealm {
      if (realmSlug == null || realmSlug.isBlank()) {
        throw new IllegalArgumentException("realmSlug must not be blank");
      }
      if (connectScopeId == null || connectScopeId.isBlank()) {
        throw new IllegalArgumentException("connectScopeId must not be blank");
      }
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
      Objects.requireNonNull(playerContext, "playerContext must not be null");
      joinRequestId = joinRequestId == null ? "" : joinRequestId;
    }

    private ScopedRealm withJoinRequestId(String requestId) {
      return new ScopedRealm(
          realmSlug, publicProductionRealm, connectScopeId, expiresAt, playerContext, requestId);
    }
  }

  public record JoinScope(ScopedRealm scope, String requestId) {
    public JoinScope {
      Objects.requireNonNull(scope, "scope must not be null");
      if (requestId == null || requestId.isBlank()) {
        throw new IllegalArgumentException("requestId must not be blank");
      }
    }
  }

  private record SessionScopes(
      long accountId,
      Map<String, List<ScopedRealm>> scopesByWorld,
      Map<String, String> worldBySelector) {
    private SessionScopes {
      Map<String, List<ScopedRealm>> safeWorlds = new HashMap<>();
      scopesByWorld.forEach((slug, scopes) -> safeWorlds.put(slug, List.copyOf(scopes)));
      scopesByWorld = Map.copyOf(safeWorlds);
      worldBySelector = Map.copyOf(worldBySelector);
    }
  }
}
