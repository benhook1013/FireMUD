package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable initial Account source capture for one exact bare-LOGIN private delegation identity.
 *
 * <p>The identity fence and its source version describe this new identity's initial durable row;
 * both are 1 and are distinct from the captured Account issuance-fence value and source version.
 * This capture does not implement later monotonic logout or rotation transitions, registry proof,
 * token issuance, or admission authority. Row deletion and truncation remain denied pending
 * separate safe-retention proof.
 */
public record AccountBareLoginTokenIdentityFence(
    String schemaName,
    UUID operationId,
    UUID sourceConnectOperationId,
    long accountId,
    UUID accountUuid,
    UUID tenantId,
    String connectScopeHash,
    String requestId,
    int requestDigestVersion,
    byte[] requestDigest,
    String profile,
    String tokenIdentity,
    byte[] tokenHash,
    long tokenIdentityFence,
    long tokenIdentityFenceSourceVersion,
    long accountIssuanceFence,
    long accountIssuanceFenceSourceVersion,
    Instant capturedAt) {

  public static final String SCHEMA_NAME = "account-bare-login-token-identity-fence/v1";
  public static final String PROFILE_NAME = "game-session-account-delegation";
  private static final Pattern SCOPE_HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TEXT_LENGTH = 128;

  public AccountBareLoginTokenIdentityFence {
    if (!SCHEMA_NAME.equals(schemaName)
        || isNil(operationId)
        || isNil(sourceConnectOperationId)
        || accountId <= 0L
        || isNil(accountUuid)
        || isNil(tenantId)
        || connectScopeHash == null
        || !SCOPE_HASH.matcher(connectScopeHash).matches()
        || !validText(requestId)
        || requestDigestVersion != 1
        || !PROFILE_NAME.equals(profile)
        || !validText(tokenIdentity)
        || tokenIdentityFence != 1L
        || tokenIdentityFenceSourceVersion != 1L
        || accountIssuanceFence <= 0L
        || accountIssuanceFenceSourceVersion <= 0L
        || capturedAt == null) {
      throw new IllegalArgumentException("Stored bare LOGIN token identity fence is malformed");
    }
    requestDigest = requireDigest(requestDigest, "request digest");
    tokenHash = requireDigest(tokenHash, "token hash");
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public byte[] tokenHash() {
    return tokenHash.clone();
  }

  /** Compares immutable capture contents while excluding the database-assigned capture time. */
  public boolean sameInitialCapture(AccountBareLoginTokenIdentityFence other) {
    return other != null
        && operationId.equals(other.operationId)
        && sourceConnectOperationId.equals(other.sourceConnectOperationId)
        && accountId == other.accountId
        && accountUuid.equals(other.accountUuid)
        && tenantId.equals(other.tenantId)
        && connectScopeHash.equals(other.connectScopeHash)
        && requestId.equals(other.requestId)
        && requestDigestVersion == other.requestDigestVersion
        && Arrays.equals(requestDigest, other.requestDigest)
        && profile.equals(other.profile)
        && tokenIdentity.equals(other.tokenIdentity)
        && Arrays.equals(tokenHash, other.tokenHash)
        && tokenIdentityFence == other.tokenIdentityFence
        && tokenIdentityFenceSourceVersion == other.tokenIdentityFenceSourceVersion
        && accountIssuanceFence == other.accountIssuanceFence
        && accountIssuanceFenceSourceVersion == other.accountIssuanceFenceSourceVersion;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountBareLoginTokenIdentityFence that)) {
      return false;
    }
    return sameInitialCapture(that)
        && schemaName.equals(that.schemaName)
        && capturedAt.equals(that.capturedAt);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            schemaName,
            operationId,
            sourceConnectOperationId,
            accountId,
            accountUuid,
            tenantId,
            connectScopeHash,
            requestId,
            requestDigestVersion,
            profile,
            tokenIdentity,
            tokenIdentityFence,
            tokenIdentityFenceSourceVersion,
            accountIssuanceFence,
            accountIssuanceFenceSourceVersion,
            capturedAt);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(tokenHash);
    return result;
  }

  @Override
  public String toString() {
    return "AccountBareLoginTokenIdentityFence{operationId="
        + operationId
        + ", accountUuid="
        + accountUuid
        + ", profile='"
        + profile
        + "', tokenEvidence=<redacted>, tokenIdentityFence="
        + tokenIdentityFence
        + ", tokenIdentityFenceSourceVersion="
        + tokenIdentityFenceSourceVersion
        + ", accountIssuanceFence="
        + accountIssuanceFence
        + ", accountIssuanceFenceSourceVersion="
        + accountIssuanceFenceSourceVersion
        + "}";
  }

  private static boolean validText(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= MAX_TEXT_LENGTH
        && value.indexOf('\0') < 0
        && !hasUnpairedSurrogate(value);
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNil(UUID value) {
    return value == null || value.equals(new UUID(0L, 0L));
  }

  private static byte[] requireDigest(byte[] value, String label) {
    if (value == null || value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(label + " must be 32 bytes");
    }
    return value.clone();
  }
}
