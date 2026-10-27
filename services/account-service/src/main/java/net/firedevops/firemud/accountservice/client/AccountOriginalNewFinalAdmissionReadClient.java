package net.firedevops.firemud.accountservice.client;

import java.util.Objects;
import java.util.UUID;

/**
 * Protected Account-to-Game-Session boundary for the actual ORIGINAL final-admission readback.
 *
 * <p>Account calls this source after its original credential has been issued. Implementations must
 * perform the authenticated same-namespace Game Session final-result read themselves; no RPC
 * caller-provided timestamp, boolean, candidate, or receipt can construct this result for Account.
 * The concrete manual mTLS reader is available for explicit composition; default runtime wiring
 * remains absent and fails closed.
 */
@FunctionalInterface
public interface AccountOriginalNewFinalAdmissionReadClient {
  AuthenticatedFinalAdmissionReadback readActualOriginalFinalAdmission(
      ExpectedOriginalAdmission expected);

  /** Complete immutable Account anchors supplied to the protected Game Session read. */
  record ExpectedOriginalAdmission(
      UUID requestId,
      UUID accountId,
      UUID tenantId,
      UUID sessionId,
      UUID bindingDecisionId,
      UUID originalLeaseId,
      long originalLeaseFence,
      String originalLeaseEvidenceSha256,
      UUID lineageId,
      UUID tokenJti,
      String tokenSha256,
      long tokenGeneration,
      long issuanceFence,
      String bindingScopeSha256,
      String membershipBaselineSha256,
      String checkpointsSha256,
      String rebindHandleSha256) {
    public ExpectedOriginalAdmission {
      requireUuid(requestId);
      requireUuid(accountId);
      requireUuid(tenantId);
      requireUuid(sessionId);
      requireUuid(bindingDecisionId);
      requireUuid(originalLeaseId);
      requireUuid(lineageId);
      requireUuid(tokenJti);
      requireSha256(originalLeaseEvidenceSha256);
      requireSha256(tokenSha256);
      requireSha256(bindingScopeSha256);
      requireSha256(membershipBaselineSha256);
      requireSha256(checkpointsSha256);
      requireSha256(rebindHandleSha256);
      if (originalLeaseFence <= 0L
          || tokenGeneration != 1L
          || issuanceFence <= 0L
          || lineageId.equals(tokenJti)) throw invalid();
    }

    @Override
    public String toString() {
      return "ExpectedOriginalAdmission[redacted]";
    }
  }

  /**
   * Typed response only constructible by the protected reader implementation. Account exact-
   * compares every anchor and checks the canonical continuity deadline before persisting it.
   */
  final class AuthenticatedFinalAdmissionReadback {
    private final int sessionSchemaVersion;
    private final String admissionState;
    private final ExpectedOriginalAdmission anchors;
    private final long admissionAtMillis;
    private final long continuityExpiresAtMillis;
    private final long canonicalSessionDurationMillis;
    private final String canonicalReadbackSha256;

    AuthenticatedFinalAdmissionReadback(
        int sessionSchemaVersion,
        String admissionState,
        ExpectedOriginalAdmission anchors,
        long admissionAtMillis,
        long continuityExpiresAtMillis,
        long canonicalSessionDurationMillis,
        String canonicalReadbackSha256) {
      this.sessionSchemaVersion = sessionSchemaVersion;
      this.admissionState = Objects.requireNonNull(admissionState);
      this.anchors = Objects.requireNonNull(anchors);
      this.admissionAtMillis = admissionAtMillis;
      this.continuityExpiresAtMillis = continuityExpiresAtMillis;
      this.canonicalSessionDurationMillis = canonicalSessionDurationMillis;
      this.canonicalReadbackSha256 = canonicalReadbackSha256;
      requireSha256(canonicalReadbackSha256);
      long expectedExpiry;
      try {
        expectedExpiry = Math.addExact(admissionAtMillis, canonicalSessionDurationMillis);
      } catch (ArithmeticException malformed) {
        throw invalid();
      }
      if (sessionSchemaVersion != 2
          || !"COMPLETE".equals(admissionState)
          || admissionAtMillis <= 0L
          || canonicalSessionDurationMillis <= 0L
          || continuityExpiresAtMillis != expectedExpiry) throw invalid();
    }

    public int sessionSchemaVersion() {
      return sessionSchemaVersion;
    }

    public String admissionState() {
      return admissionState;
    }

    public ExpectedOriginalAdmission anchors() {
      return anchors;
    }

    public long admissionAtMillis() {
      return admissionAtMillis;
    }

    public long continuityExpiresAtMillis() {
      return continuityExpiresAtMillis;
    }

    public long canonicalSessionDurationMillis() {
      return canonicalSessionDurationMillis;
    }

    public String canonicalReadbackSha256() {
      return canonicalReadbackSha256;
    }

    @Override
    public String toString() {
      return "AuthenticatedFinalAdmissionReadback[redacted]";
    }
  }

  private static void requireUuid(UUID value) {
    Objects.requireNonNull(value);
    if (value.version() != 4 || value.variant() != 2) throw invalid();
  }

  private static void requireSha256(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Protected original final-admission readback is malformed");
  }
}
