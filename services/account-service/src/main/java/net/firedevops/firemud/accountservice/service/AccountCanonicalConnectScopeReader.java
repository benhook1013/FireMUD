package net.firedevops.firemud.accountservice.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered reader for an exact signed V2 public-production connect scope and its Account row.
 *
 * <p>The expected Account UUID is comparison data supplied by an outer authenticating owner; this
 * reader does not authenticate a caller or create a caller context. It returns only the persisted
 * V2 selector. Neither constructing nor reading the selector grants membership, JOIN, or gameplay
 * authority.
 */
public final class AccountCanonicalConnectScopeReader {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String AUDIENCE = "bootstrap-connect-scope";
  private static final String INVALID_MESSAGE = "Canonical connect scope is invalid";
  private static final String UNAVAILABLE_MESSAGE = "Account authority is unavailable";

  private final JwtUtil jwt;
  private final AccountConnectScopeRepository scopes;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public AccountCanonicalConnectScopeReader(
      JwtUtil jwt,
      AccountConnectScopeRepository scopes,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.jwt = Objects.requireNonNull(jwt, "JWT verifier is required");
    this.scopes = Objects.requireNonNull(scopes, "Account scope repository is required");
    this.clock = Objects.requireNonNull(clock, "Account clock is required");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    transaction.setTimeout(5);
  }

  /** Reads the persisted scope only when it exactly matches this signed bearer and Account UUID. */
  public CanonicalJoinScopeV2 read(UUID expectedAccountId, String presentedScope) {
    requireNoAmbientTransaction();
    if (expectedAccountId == null || NIL_UUID.equals(expectedAccountId)) throw invalid(null);

    final CanonicalJoinScopeV2 signed;
    try {
      signed = verifySignedScope(expectedAccountId, presentedScope);
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    String tokenHash = AccountJoinDigest.tokenHash(presentedScope);
    final CanonicalJoinScopeV2 retained;
    try {
      retained =
          Objects.requireNonNull(
              transaction.execute(status -> readRetained(expectedAccountId, tokenHash, signed)),
              "Account scope transaction returned no result");
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }

    requireCurrentScopeTime(signed);
    if (!signed.equals(retained)) throw invalid(null);
    return retained;
  }

  private CanonicalJoinScopeV2 verifySignedScope(UUID expectedAccountId, String token) {
    if (token == null || token.isBlank() || !token.equals(token.trim())) throw invalid(null);

    final Claims claims;
    try {
      claims = jwt.parseToken(token).getPayload();
    } catch (JwtException | IllegalArgumentException failure) {
      throw invalid(null);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }

    try {
      // JJWT normalizes the signed aud string to a set. Require this one exact audience rather
      // than relying on a string cast or accepting the first value of a multi-audience token.
      if (!Set.of(AUDIENCE).equals(claims.getAudience())) throw invalid(null);
      String subject = JwtClaims.requireCanonicalAccountUuid(claims.getSubject(), "sub");
      String accountText =
          JwtClaims.requireCanonicalAccountUuid(claims.get("accountId"), "accountId");
      if (!subject.equals(accountText) || !expectedAccountId.toString().equals(accountText)) {
        throw invalid(null);
      }
      requireNonNilUuid(claims.get("jti"), "jti");
      if (!"PUBLIC_PRODUCTION".equals(requireString(claims.get("targetClass"), "targetClass"))) {
        throw invalid(null);
      }
      if (requireIntegralLong(claims.get("scopeDigestVersion"), "scopeDigestVersion") != 2L) {
        throw invalid(null);
      }

      UUID tenantId = requireNonNilUuid(claims.get("tenantId"), "tenantId");
      UUID realmId = requireNonNilUuid(claims.get("realmId"), "realmId");
      UUID namespaceId =
          requireNonNilUuid(claims.get("playableStateNamespaceId"), "playableStateNamespaceId");
      UUID gameInstanceId = requireNonNilUuid(claims.get("gameInstanceId"), "gameInstanceId");
      long catalogRevision = requireIntegralLong(claims.get("catalogRevision"), "catalogRevision");
      long pointerVersion = requireIntegralLong(claims.get("pointerVersion"), "pointerVersion");
      Instant evaluatedAt = requireCanonicalInstant(claims.get("evaluatedAt"), "evaluatedAt");
      Instant expiresAt =
          requireCanonicalInstant(claims.get("connectScopeExpiresAt"), "connectScopeExpiresAt");
      Date issuedAt = claims.getIssuedAt();
      Date jwtExpiresAt = claims.getExpiration();
      if (issuedAt == null
          || jwtExpiresAt == null
          || !issuedAt.toInstant().isAfter(Instant.EPOCH)
          || !issuedAt.toInstant().isBefore(jwtExpiresAt.toInstant())
          || !expiresAt.equals(jwtExpiresAt.toInstant())
          || !expiresAt.isAfter(evaluatedAt)) {
        throw invalid(null);
      }

      CanonicalJoinScopeV2 scope =
          new CanonicalJoinScopeV2(
              token,
              expectedAccountId,
              tenantId,
              realmId,
              requireString(claims.get("tenantSlug"), "tenantSlug"),
              requireString(claims.get("worldSlug"), "worldSlug"),
              requireString(claims.get("realmSlug"), "realmSlug"),
              namespaceId,
              requireString(claims.get("playableStateScope"), "playableStateScope"),
              gameInstanceId,
              catalogRevision,
              pointerVersion,
              evaluatedAt.toString(),
              expiresAt.toString());
      requireCurrentScopeTime(scope);
      return scope;
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (JwtException
        | DateTimeException
        | IllegalArgumentException
        | ArithmeticException failure) {
      throw invalid(failure);
    }
  }

  private CanonicalJoinScopeV2 readRetained(
      UUID expectedAccountId, String tokenHash, CanonicalJoinScopeV2 signed) {
    try {
      CanonicalJoinScopeV2 retained =
          scopes.findCanonical(signed.connectScopeId()).orElseThrow(() -> invalid(null));
      CanonicalConnectScopeEvidence evidence =
          scopes.findCanonicalEvidenceByTokenHash(tokenHash).orElseThrow(() -> invalid(null));
      String digest = AccountJoinDigest.scopeV2(signed);
      if (!expectedAccountId.equals(retained.accountId())
          || !signed.equals(retained)
          || !tokenHash.equals(evidence.scopeTokenHash())
          || evidence.privateAccountId() <= 0L
          || !"PUBLIC_PRODUCTION".equals(evidence.targetClass())
          || evidence.scopeDigestVersion() != 2
          || !digest.equals(evidence.scopeDigest())
          || evidence.tenantProvenance() == null
          || !evidence.accountUuid().equals(signed.accountId())
          || !evidence.tenantUuid().equals(signed.tenantId())
          || !evidence.realmId().equals(signed.realmId())
          || !evidence.tenantSlug().equals(signed.tenantSlug())
          || !evidence.worldSlug().equals(signed.worldSlug())
          || !evidence.realmSlug().equals(signed.realmSlug())
          || !evidence.playableStateNamespaceUuid().equals(signed.playableStateNamespaceId())
          || !evidence.playableStateScope().equals(signed.playableStateScope())
          || !evidence.gameInstanceUuid().equals(signed.gameInstanceId())
          || evidence.catalogRevision() != signed.catalogRevision()
          || evidence.pointerVersion() != signed.pointerVersion()
          || !evidence.evaluatedAt().equals(signed.evaluatedAt())
          || !evidence.connectScopeExpiresAt().equals(signed.connectScopeExpiresAt())) {
        throw invalid(null);
      }
      return retained;
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private void requireCurrentScopeTime(CanonicalJoinScopeV2 scope) {
    Instant now = clock.instant();
    Instant evaluatedAt = Instant.parse(scope.evaluatedAt());
    Instant expiresAt = Instant.parse(scope.connectScopeExpiresAt());
    if (now.isBefore(evaluatedAt) || !now.isBefore(expiresAt)) throw invalid(null);
  }

  private static String requireString(Object value, String field) {
    if (!(value instanceof String text) || text.isEmpty() || !text.equals(text.trim())) {
      throw new IllegalArgumentException("Malformed claim: " + field);
    }
    return text;
  }

  private static UUID requireNonNilUuid(Object value, String field) {
    String text = requireString(value, field);
    UUID parsed = UUID.fromString(text);
    if (!parsed.toString().equals(text) || NIL_UUID.equals(parsed)) {
      throw new IllegalArgumentException("Malformed claim: " + field);
    }
    return parsed;
  }

  private static long requireIntegralLong(Object value, String field) {
    final long result;
    if (value instanceof Integer integer) result = integer.longValue();
    else if (value instanceof Long longValue) result = longValue;
    else if (value instanceof Short shortValue) result = shortValue.longValue();
    else if (value instanceof Byte byteValue) result = byteValue.longValue();
    else throw new IllegalArgumentException("Malformed numeric claim: " + field);
    if (result <= 0L) throw new IllegalArgumentException("Invalid numeric claim: " + field);
    return result;
  }

  private static Instant requireCanonicalInstant(Object value, String field) {
    String text = requireString(value, field);
    Instant parsed = Instant.parse(text);
    if (!parsed.toString().equals(text)) {
      throw new IllegalArgumentException("Non-canonical instant claim: " + field);
    }
    return parsed;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical connect-scope read requires no ambient Account transaction");
    }
  }

  private static AuthenticationException invalid(Throwable ignored) {
    // Do not retain the bearer or parser diagnostics that may include attacker-controlled text.
    return new AuthenticationException("CONNECT_SCOPE_INVALID", INVALID_MESSAGE);
  }

  private static AuthenticationException unavailable(Throwable cause) {
    return new AuthenticationException("AUTH_UNAVAILABLE", UNAVAILABLE_MESSAGE, cause);
  }
}
