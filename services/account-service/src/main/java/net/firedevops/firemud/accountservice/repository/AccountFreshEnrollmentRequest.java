package net.firedevops.firemud.accountservice.repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.common.EmailCanonicalization;

/** Internal insertion input, never an authorization credential or registration transport DTO. */
public final class AccountFreshEnrollmentRequest {
  private final UUID enrollmentId;
  private final String username;
  private final String email;
  private final String passwordHash;
  private final String role;
  private final boolean emailVerified;
  private final String loginAuthModes;

  public AccountFreshEnrollmentRequest(
      UUID enrollmentId,
      String username,
      String email,
      String passwordHash,
      String role,
      boolean emailVerified,
      String loginAuthModes) {
    this.enrollmentId = Objects.requireNonNull(enrollmentId);
    if (enrollmentId.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Enrollment ID required");
    this.username = required(username);
    this.email = required(EmailCanonicalization.normalize(email));
    this.passwordHash = required(passwordHash);
    this.role = required(role);
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
      role,
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
    account.setRole(role);
    account.setEmailVerified(emailVerified);
    account.setLoginAuthModes(loginAuthModes);
    return account;
  }

  @Override
  public String toString() {
    return "AccountFreshEnrollmentRequest[enrollmentId=" + enrollmentId + "]";
  }
}
