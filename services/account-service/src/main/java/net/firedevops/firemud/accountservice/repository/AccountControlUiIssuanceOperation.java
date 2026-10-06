package net.firedevops.firemud.accountservice.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;

/** Credential-free, immutable correlation row and optional encrypted original response. */
public record AccountControlUiIssuanceOperation(
    UUID operationId,
    AccountControlUiIssuanceRequest request,
    long accountId,
    AccountIdentityProvenance accountProvenance,
    byte[] requestDigest,
    Lifecycle lifecycle,
    OriginalCapture originalCapture,
    CompletedResponse completedResponse,
    Instant createdAt,
    Instant updatedAt) {

  private static final int DIGEST_LENGTH = 32;

  public AccountControlUiIssuanceOperation {
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(request, "request");
    if (accountId <= 0L) {
      throw new IllegalArgumentException("Invalid Account numeric association");
    }
    Objects.requireNonNull(accountProvenance, "accountProvenance");
    requestDigest = copyDigest(requestDigest, "requestDigest");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(originalCapture, "originalCapture");
    if (originalCapture.accountId() != accountId
        || originalCapture.accountProvenance() != accountProvenance) {
      throw new IllegalArgumentException("Original capture Account association differs");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if ((lifecycle == Lifecycle.PENDING) != (completedResponse == null)) {
      throw new IllegalArgumentException("Operation lifecycle/result shape differs");
    }
    if (completedResponse != null) {
      requireCompletedBinding(
          completedResponse, operationId, request, requestDigest, originalCapture);
    }
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof AccountControlUiIssuanceOperation that)) return false;
    return accountId == that.accountId
        && operationId.equals(that.operationId)
        && request.equals(that.request)
        && accountProvenance == that.accountProvenance
        && Arrays.equals(requestDigest, that.requestDigest)
        && lifecycle == that.lifecycle
        && originalCapture.equals(that.originalCapture)
        && Objects.equals(completedResponse, that.completedResponse)
        && createdAt.equals(that.createdAt)
        && updatedAt.equals(that.updatedAt);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            operationId,
            request,
            accountId,
            accountProvenance,
            lifecycle,
            originalCapture,
            completedResponse,
            createdAt,
            updatedAt);
    return 31 * result + Arrays.hashCode(requestDigest);
  }

  @Override
  public String toString() {
    return "AccountControlUiIssuanceOperation{operationId="
        + operationId
        + ", requestId="
        + request.requestId()
        + ", lifecycle="
        + lifecycle
        + ", originalEvidence=<redacted>}";
  }

  private static void requireCompletedBinding(
      CompletedResponse result,
      UUID operationId,
      AccountControlUiIssuanceRequest request,
      byte[] requestDigest,
      OriginalCapture capture) {
    var binding = result.binding();
    if (!binding.accountId().equals(request.accountUuid())
        || !binding.operationId().equals(operationId.toString())
        || !binding.requestId().equals(request.requestId())
        || !MessageDigest.isEqual(binding.requestDigest(), requestDigest)
        || !MessageDigest.isEqual(
            binding.authorityCaptureDigest(), capture.authorityCaptureDigest())
        || !MessageDigest.isEqual(binding.issuanceFenceDigest(), capture.issuanceFenceDigest())
        || result.envelope().purpose() != AccountEnvelopePurpose.CONTROL_UI_RESPONSE) {
      throw new IllegalArgumentException(
          "Committed response does not match its original operation");
    }
  }

  private static byte[] copyDigest(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length != DIGEST_LENGTH) {
      throw new IllegalArgumentException("Invalid " + name);
    }
    return value.clone();
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be non-nil");
    }
  }

  public enum Lifecycle {
    PENDING,
    COMMITTED
  }

  /**
   * Opaque complete original authority and issuance-fence bytes; digests establish identity only.
   */
  public static final class OriginalCapture {
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    private final long accountId;
    private final AccountIdentityProvenance accountProvenance;
    private final byte[] authorityCapture;
    private final byte[] issuanceFenceCapture;

    public OriginalCapture(
        long accountId,
        AccountIdentityProvenance accountProvenance,
        byte[] authorityCapture,
        byte[] issuanceFenceCapture) {
      if (accountId <= 0L) {
        throw new IllegalArgumentException("Invalid Account numeric association");
      }
      this.accountId = accountId;
      this.accountProvenance = Objects.requireNonNull(accountProvenance, "accountProvenance");
      this.authorityCapture = copyCapture(authorityCapture, "authorityCapture");
      this.issuanceFenceCapture = copyCapture(issuanceFenceCapture, "issuanceFenceCapture");
    }

    public long accountId() {
      return accountId;
    }

    public AccountIdentityProvenance accountProvenance() {
      return accountProvenance;
    }

    public byte[] authorityCapture() {
      return authorityCapture.clone();
    }

    public byte[] issuanceFenceCapture() {
      return issuanceFenceCapture.clone();
    }

    public byte[] authorityCaptureDigest() {
      return sha256(authorityCapture);
    }

    public byte[] issuanceFenceDigest() {
      return sha256(issuanceFenceCapture);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof OriginalCapture that)) return false;
      return accountId == that.accountId
          && accountProvenance == that.accountProvenance
          && Arrays.equals(authorityCapture, that.authorityCapture)
          && Arrays.equals(issuanceFenceCapture, that.issuanceFenceCapture);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(accountId, accountProvenance);
      result = 31 * result + Arrays.hashCode(authorityCapture);
      result = 31 * result + Arrays.hashCode(issuanceFenceCapture);
      return result;
    }

    @Override
    public String toString() {
      return "OriginalCapture{evidence=<redacted>}";
    }

    private static byte[] copyCapture(byte[] value, String name) {
      Objects.requireNonNull(value, name);
      if (value.length == 0 || value.length > MAX_CAPTURE_BYTES) {
        throw new IllegalArgumentException("Invalid " + name);
      }
      return value.clone();
    }
  }

  public record CompletedResponse(
      AccountControlUiResponseEnvelopeBinding binding, AccountEncryptedEnvelope envelope) {
    public CompletedResponse {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(envelope, "envelope");
      if (envelope.purpose() != AccountEnvelopePurpose.CONTROL_UI_RESPONSE) {
        throw new IllegalArgumentException("Wrong encrypted response purpose");
      }
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
