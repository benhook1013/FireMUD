package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/** Secret-free lifecycle evidence. No result authorizes LOGIN, recovery, activation or PLAY. */
public record InitialGameplayLoginDisposalResult(
    UUID requestId,
    UUID callerContextId,
    UUID disposalOperationId,
    Disposition disposition,
    ProofKind proofKind) {
  public InitialGameplayLoginDisposalResult {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(callerContextId);
    Objects.requireNonNull(disposalOperationId);
    Objects.requireNonNull(disposition);
    Objects.requireNonNull(proofKind);
    if (switch (disposition) {
      case PENDING -> proofKind != ProofKind.NONE;
      case RETIRED -> proofKind != ProofKind.PHYSICAL_TOKEN_DENIAL;
      case NO_TOKEN -> proofKind != ProofKind.NO_ISSUANCE_BINDING;
    }) throw new IllegalArgumentException("Exact initial LOGIN lifecycle proof kind required");
    for (UUID value : new UUID[] {requestId, callerContextId, disposalOperationId}) {
      if (value.version() != 4 || value.variant() != 2) {
        throw new IllegalArgumentException("Original LOGIN lifecycle UUIDv4 required");
      }
    }
  }

  public InitialGameplayLoginDisposalResult(
      UUID requestId, UUID callerContextId, UUID disposalOperationId, Disposition disposition) {
    this(
        requestId,
        callerContextId,
        disposalOperationId,
        disposition,
        switch (disposition) {
          case PENDING -> ProofKind.NONE;
          case RETIRED -> ProofKind.PHYSICAL_TOKEN_DENIAL;
          case NO_TOKEN ->
              throw new IllegalArgumentException("Explicit Account nonissuance proof required");
        });
  }

  public enum Disposition {
    PENDING,
    RETIRED,
    NO_TOKEN
  }

  public enum ProofKind {
    NONE,
    PHYSICAL_TOKEN_DENIAL,
    NO_ISSUANCE_BINDING
  }
}
