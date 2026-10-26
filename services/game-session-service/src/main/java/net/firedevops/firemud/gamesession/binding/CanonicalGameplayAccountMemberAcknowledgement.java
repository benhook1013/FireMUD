package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Completed schema-v2 acknowledgement for one exact binding-local account-index member. */
public record CanonicalGameplayAccountMemberAcknowledgement(
    CanonicalGameplayAccountMemberRepairOperation operation,
    BigInteger coverageGeneration,
    String member) {
  public CanonicalGameplayAccountMemberAcknowledgement {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(coverageGeneration, "coverageGeneration");
    if (coverageGeneration.compareTo(operation.inventorySnapshotRevision()) <= 0) {
      throw new IllegalArgumentException(
          "coverageGeneration must be newer than the immutable inventory snapshot");
    }
    Objects.requireNonNull(member, "member");
    if (!member.equals(operation.member())) {
      throw new IllegalArgumentException("member differs from the exact owner operation");
    }
  }

  public int schemaVersion() {
    return 2;
  }

  public AccountCoverageState accountCoverageState() {
    return AccountCoverageState.NO_ACTIVE_ACCOUNT_WIDE_FLOW;
  }

  public UUID operationId() {
    return operation.operationId();
  }

  public NotApplicable accountCoverageOperationId() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public NotApplicable accountCoverageLifecycle() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public NotApplicable accountCoverageOperationFence() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public NotApplicable accountAdmissionFence() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public NotApplicable historicalAccountAdmissionFence() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public NotApplicable resolvedScope() {
    return NotApplicable.NOT_APPLICABLE;
  }

  public UUID transitionId() {
    return operation.transitionId();
  }

  public CanonicalGameplayBindingRef bindingRef() {
    return operation.bindingRef();
  }

  public UUID accountId() {
    return operation.accountId();
  }

  public UUID tenantId() {
    return operation.tenantId();
  }

  public BigInteger bindingGeneration() {
    return operation.bindingGeneration();
  }

  public UUID accountIndexFence() {
    return operation.accountIndexFence();
  }

  public BigInteger operationFence() {
    return operation.operationFence();
  }

  public UUID coverageFence() {
    return operation.coverageFence();
  }

  public BigInteger inventorySnapshotRevision() {
    return operation.inventorySnapshotRevision();
  }

  public CanonicalGameplayBindingAccountIndexObligation.Action action() {
    return CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN;
  }

  public Readback readback() {
    return new Readback(ReadbackState.PRESENT, member);
  }

  public enum AccountCoverageState {
    NO_ACTIVE_ACCOUNT_WIDE_FLOW
  }

  public enum NotApplicable {
    NOT_APPLICABLE
  }

  public enum ReadbackState {
    PRESENT
  }

  public record Readback(ReadbackState state, String member) {
    public Readback {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(member, "member");
      if (state != ReadbackState.PRESENT) {
        throw new IllegalArgumentException("binding-local ADD_OR_RETAIN requires PRESENT");
      }
    }
  }
}
