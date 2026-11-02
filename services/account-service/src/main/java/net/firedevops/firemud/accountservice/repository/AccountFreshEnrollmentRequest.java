package net.firedevops.firemud.accountservice.repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.common.EmailCanonicalization;

/**
 * Internal insertion input, never an authorization credential or registration transport DTO. The
 * passwordHash field is an already prepared salted verifier, not plaintext; a producer retrying
 * after a lost response must retain and reuse this exact request and verifier rather than rehashing
 * the same plaintext into different verifier bytes. Changed verifier bytes remain a request-digest
 * conflict. Fresh enrollment has no legacy role input; the Account birth owner creates its
 * explicit-empty global role source from the null legacy role.
 */
public final class AccountFreshEnrollmentRequest {
  private final UUID enrollmentId;
  private final String username;
  private final String email;
  private final String passwordHash;
  private final boolean emailVerified;
  private final String loginAuthModes;

  public AccountFreshEnrollmentRequest(
      UUID enrollmentId,
      String username,
      String email,
      String passwordHash,
      boolean emailVerified,
      String loginAuthModes) {
    this.enrollmentId = Objects.requireNonNull(enrollmentId);
    if (enrollmentId.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Enrollment ID required");
    this.username = required(username);
    this.email = required(EmailCanonicalization.normalize(email));
    this.passwordHash = required(passwordHash);
    this.emailVerified = emailVerified;
    this.loginAuthModes = AccountLoginAuthModes.normalize(loginAuthModes);
  }

  private static String required(String value) {
    if (value == null || value.isBlank() || value.length() > 8192) {
      throw new IllegalArgumentException("Bounded complete insertion fields required");
    }
    return value;
  }

  public UUID enrollmentId() {
    return enrollmentId;
  }

  public String requestDigest() {
    String[] fields = {
      "account-fresh-enrollment-request/v1",
      enrollmentId.toString(),
      username,
      email,
      passwordHash,
      Boolean.toString(emailVerified),
      loginAuthModes
    };
    var bytes = new java.io.ByteArrayOutputStream();
    for (String field : fields) {
      byte[] value = field.getBytes(StandardCharsets.UTF_8);
      bytes.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
      bytes.writeBytes(value);
    }
    return AccountFreshEnrollmentPolicySource.digest(bytes.toByteArray());
  }

  Account newAccount() {
    Account account = new Account();
    account.setUsername(username);
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setRole(null);
    account.setEmailVerified(emailVerified);
    account.setLoginAuthModes(loginAuthModes);
    return account;
  }

  @Override
  public String toString() {
    return "AccountFreshEnrollmentRequest[enrollmentId=" + enrollmentId + "]";
  }
}
