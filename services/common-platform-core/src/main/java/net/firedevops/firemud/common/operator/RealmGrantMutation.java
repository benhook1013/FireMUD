package net.firedevops.firemud.common.operator;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Closed owner-local realm-grant mutation data using ADR 0047's seven-field digest grammar.
 * Constructing it authenticates no actor or lifecycle and enables no operator route or reference.
 */
public record RealmGrantMutation(
    String targetNamespace,
    GrantAuthorityScopeCodec.Scope grant,
    Operation operation,
    Long expectedGrantRevision,
    Instant grantExpiresAt,
    String auditReason) {
  public static final String ACTION_FAMILY_SCHEMA_ID = "firemud.account.realm-grant";
  public static final String ACTION_FAMILY_SCHEMA_VERSION = "1";

  public RealmGrantMutation {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical target workload namespace is required");
    }
    Objects.requireNonNull(grant, "Lifecycle-bound grant identity is required");
    Objects.requireNonNull(operation, "Grant operation is required");
    if (operation == Operation.CREATE) {
      if (expectedGrantRevision != null) {
        throw new IllegalArgumentException("CREATE requires an absent grant revision");
      }
    } else if (expectedGrantRevision == null || expectedGrantRevision <= 0) {
      throw new IllegalArgumentException("A positive expected grant revision is required");
    }
    if (operation == Operation.REVOKE) {
      if (grantExpiresAt != null) {
        throw new IllegalArgumentException("REVOKE requires absent requested expiry");
      }
    } else {
      Objects.requireNonNull(grantExpiresAt, "Explicit requested grant expiry is required");
    }
    auditReason =
        Normalizer.normalize(
            Objects.requireNonNull(auditReason, "Audit reason is required"), Normalizer.Form.NFC);
    // Reuse the standard scalar validation and byte bound rather than another digest grammar.
    MutationDigestV1.string(auditReason);
    if (auditReason.indexOf(0) >= 0) {
      throw new IllegalArgumentException("Audit reason must not contain NUL");
    }
    if (auditReason.codePoints().allMatch(RealmGrantMutation::isSharedWhitespace)) {
      throw new IllegalArgumentException("Audit reason must not be blank");
    }
  }

  private static boolean isSharedWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000D)
        || codePoint == 0x0020
        || codePoint == 0x0085
        || codePoint == 0x00A0
        || codePoint == 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x200A)
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x202F
        || codePoint == 0x205F
        || codePoint == 0x3000;
  }

  public enum Operation {
    CREATE,
    EXTEND,
    REVOKE,
    REGRANT
  }

  /** Exact seven-field preimage; stable request and authenticated actor identities are separate. */
  public byte[] canonicalPreimage() {
    return MutationDigestV1.preimage(this);
  }

  /** Lowercase SHA-256 hex without an additional family-specific digest domain. */
  public String mutationDigest() {
    return MutationDigestV1.sha256Hex(canonicalPreimage());
  }
}
