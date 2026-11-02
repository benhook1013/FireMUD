package net.firedevops.firemud.accountservice.service.session;

import java.util.UUID;

/** Account-only slot relationship; the canonical token key remains untagged and unchanged. */
public final class AccountTenantCreationAdmissionRedisKeys {
  public static final String TOKEN_PREFIX = "session:auth:token:";
  public static final String RECEIPT_PREFIX = "session:auth:creation-admission:";

  /** External qualification covers the token comparison and this companion family together. */
  public static final String QUALIFICATION_SCOPE = "account-tenant-creation-admission-v1";

  private AccountTenantCreationAdmissionRedisKeys() {}

  public static String token(String tokenHash) {
    if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact canonical token hash is required");
    }
    return TOKEN_PREFIX + tokenHash;
  }

  public static String receipt(String tokenHash, UUID creationOperationId) {
    if (creationOperationId == null
        || creationOperationId.getMostSignificantBits() == 0L
            && creationOperationId.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException("Original creation operation is required");
    }
    // Redis hashes every byte of the untagged token key, not merely its hash suffix.
    return RECEIPT_PREFIX + "{" + token(tokenHash) + "}:" + creationOperationId;
  }
}
