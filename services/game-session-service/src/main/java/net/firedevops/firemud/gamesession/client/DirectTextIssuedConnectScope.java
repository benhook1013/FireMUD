package net.firedevops.firemud.gamesession.client;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.common.security.JwtClaims;

/**
 * Immutable observational retention of an Account-issued direct-text scope and its resolved target.
 * This value is not authenticated authority, a store, or a public wire DTO.
 */
public final class DirectTextIssuedConnectScope {
  private static final int MAX_CONNECT_SCOPE_ID_LENGTH = 2048;

  private final String accountId;
  private final DirectTextConnectScopeTarget target;
  private final String connectScopeId;
  private final String evaluatedAt;
  private final Instant evaluatedAtInstant;
  private final String connectScopeExpiresAt;
  private final Instant connectScopeExpiresAtInstant;

  private DirectTextIssuedConnectScope(
      String accountId,
      DirectTextConnectScopeTarget target,
      String connectScopeId,
      String evaluatedAt,
      Instant evaluatedAtInstant,
      String connectScopeExpiresAt,
      Instant connectScopeExpiresAtInstant) {
    this.accountId = accountId;
    this.target = target;
    this.connectScopeId = connectScopeId;
    this.evaluatedAt = evaluatedAt;
    this.evaluatedAtInstant = evaluatedAtInstant;
    this.connectScopeExpiresAt = connectScopeExpiresAt;
    this.connectScopeExpiresAtInstant = connectScopeExpiresAtInstant;
  }

  /**
   * Retains one successful Account response without re-resolving or altering the supplied target.
   * The Account-owned evaluation time is preserved as returned; the local clock is used only to
   * reject a scope that is already expired.
   */
  public static DirectTextIssuedConnectScope fromAccountResponse(
      String accountId,
      DirectTextConnectScopeTarget target,
      IssueDirectTextConnectScopeResponse response,
      Clock clock) {
    String canonicalAccountId = JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
    if (target == null || response == null || clock == null || response.hasError()) {
      throw invalid();
    }

    String connectScopeId = response.getConnectScopeId();
    if (connectScopeId == null
        || connectScopeId.isBlank()
        || connectScopeId.length() > MAX_CONNECT_SCOPE_ID_LENGTH) {
      throw invalid();
    }

    String evaluatedAt = response.getEvaluatedAt();
    String connectScopeExpiresAt = response.getConnectScopeExpiresAt();
    Instant evaluatedAtInstant = parseCanonicalInstant(evaluatedAt);
    Instant connectScopeExpiresAtInstant = parseCanonicalInstant(connectScopeExpiresAt);
    if (!connectScopeExpiresAtInstant.isAfter(evaluatedAtInstant)
        || !connectScopeExpiresAtInstant.isAfter(clock.instant())) {
      throw invalid();
    }

    return new DirectTextIssuedConnectScope(
        canonicalAccountId,
        target,
        connectScopeId,
        evaluatedAt,
        evaluatedAtInstant,
        connectScopeExpiresAt,
        connectScopeExpiresAtInstant);
  }

  /** Returns whether this retained scope is live at the supplied clock observation. */
  public boolean isLive(Clock clock) {
    if (clock == null) {
      throw invalid();
    }
    return connectScopeExpiresAtInstant.isAfter(clock.instant());
  }

  public String accountId() {
    return accountId;
  }

  public DirectTextConnectScopeTarget target() {
    return target;
  }

  public String connectScopeId() {
    return connectScopeId;
  }

  public String evaluatedAt() {
    return evaluatedAt;
  }

  public Instant evaluatedAtInstant() {
    return evaluatedAtInstant;
  }

  public String connectScopeExpiresAt() {
    return connectScopeExpiresAt;
  }

  public Instant connectScopeExpiresAtInstant() {
    return connectScopeExpiresAtInstant;
  }

  private static Instant parseCanonicalInstant(String value) {
    if (value == null || value.isBlank()) {
      throw invalid();
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value)) {
        throw invalid();
      }
      return parsed;
    } catch (DateTimeParseException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Successful, live Account direct-text scope required");
  }
}
