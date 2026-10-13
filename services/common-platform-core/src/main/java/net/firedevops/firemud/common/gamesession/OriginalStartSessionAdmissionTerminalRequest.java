package net.firedevops.firemud.common.gamesession;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;

/** Exact complete Account protection evidence selecting one original-admission terminal read. */
public final class OriginalStartSessionAdmissionTerminalRequest {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_CANONICAL_BYTES =
      AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES;

  private final AccountStartSessionAdmissionProtectionEvidence protectionEvidence;
  private final byte[] canonicalBytes;

  /** Decodes one bounded, exact canonical Account evidence value. */
  public OriginalStartSessionAdmissionTerminalRequest(byte[] canonicalProtectionEvidenceBytes) {
    Objects.requireNonNull(
        canonicalProtectionEvidenceBytes, "canonicalProtectionEvidenceBytes is required");
    if (canonicalProtectionEvidenceBytes.length == 0
        || canonicalProtectionEvidenceBytes.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException(
          "Canonical Account protection evidence is empty or exceeds its byte bound");
    }
    this.protectionEvidence =
        AccountStartSessionAdmissionProtectionEvidence.decode(canonicalProtectionEvidenceBytes);
    this.canonicalBytes = protectionEvidence.canonicalBytes();
    if (!Arrays.equals(canonicalProtectionEvidenceBytes, canonicalBytes)) {
      throw new IllegalArgumentException(
          "Account protection evidence bytes are not the exact canonical value");
    }
  }

  public AccountStartSessionAdmissionProtectionEvidence protectionEvidence() {
    return protectionEvidence;
  }

  public String targetNamespace() {
    return protectionEvidence
        .request()
        .originalTuple()
        .preAuthorizationTuple()
        .action()
        .scope()
        .targetNamespace();
  }

  /** Returns the exact complete Account evidence bytes, including all retained source children. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof OriginalStartSessionAdmissionTerminalRequest that
            && Arrays.equals(canonicalBytes, that.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }
}
