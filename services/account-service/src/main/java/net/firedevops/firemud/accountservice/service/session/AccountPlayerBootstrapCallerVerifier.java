package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.dao.DataAccessException;

/**
 * Verifies the existing browser-bootstrap credential and its persisted Account owner. This manually
 * composed verifier supplies identity only; it grants no tenant, membership, or admission
 * authority.
 */
public final class AccountPlayerBootstrapCallerVerifier {
  private static final UUID NIL_ACCOUNT_UUID = new UUID(0L, 0L);

  private final JwtUtil jwtUtil;
  private final AccountRepository accountRepository;
  private final SessionService sessionService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Retains the owner-managed JWT, persistence, and session collaborators")
  public AccountPlayerBootstrapCallerVerifier(
      JwtUtil jwtUtil, AccountRepository accountRepository, SessionService sessionService) {
    this.jwtUtil = jwtUtil;
    this.accountRepository = accountRepository;
    this.sessionService = sessionService;
  }

  public VerifiedCaller requireCaller(String bootstrapToken) {
    Claims claims = requireSignedBootstrapClaims(bootstrapToken);
    UUID subject =
        claims.get("sub") instanceof String text ? AccountUuidText.parseOrNull(text) : null;
    UUID accountUuid =
        claims.get("accountId") instanceof String text ? AccountUuidText.parseOrNull(text) : null;
    if (subject == null || accountUuid == null || !subject.equals(accountUuid)) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
    }
    final long accountId;
    try {
      Account account =
          accountRepository
              .findByAccountUuid(accountUuid)
              .orElseThrow(() -> new IllegalArgumentException("Account not found"));
      accountId = requireAccountStorageId(account, accountUuid);
    } catch (IllegalArgumentException | IllegalStateException | DataAccessException ex) {
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Account authority is unavailable", ex);
    }
    if (!sessionService.isAccountSessionActive(accountId, bootstrapToken)) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap token expired");
    }
    return new VerifiedCaller(accountId, accountUuid);
  }

  private Claims requireSignedBootstrapClaims(String token) {
    if (token == null || token.isBlank()) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Missing bootstrap token");
    }
    Claims claims;
    try {
      claims = jwtUtil.parseToken(token).getPayload();
    } catch (JwtException | IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
    try {
      if (!"player-bootstrap".equals(JwtClaims.requireText(claims.get("aud"), "aud"))) {
        throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token");
      }
      return claims;
    } catch (IllegalArgumentException ex) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap token", ex);
    }
  }

  private long requireAccountStorageId(Account account, UUID accountUuid) {
    if (account == null) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
    AccountIdentityProvenance provenance = account.getAccountUuidProvenance();
    if (account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || NIL_ACCOUNT_UUID.equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || (provenance != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            && provenance != AccountIdentityProvenance.ACCOUNT_V29_MIGRATION
            && provenance != AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT)
        || !accountUuid.equals(account.getAccountUuid())) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
    return account.getId();
  }

  /**
   * Identity produced only after signed-token, persisted-owner, and active-session verification.
   */
  public static final class VerifiedCaller {
    private final long accountId;
    private final UUID accountUuid;

    private VerifiedCaller(long accountId, UUID accountUuid) {
      this.accountId = accountId;
      this.accountUuid = accountUuid;
    }

    public long accountId() {
      return accountId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }
  }
}
