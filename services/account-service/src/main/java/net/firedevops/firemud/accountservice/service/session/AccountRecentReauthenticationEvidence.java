package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;

/** Immutable typed handle for one retained Account-authenticated reauthentication operation. */
public final class AccountRecentReauthenticationEvidence {
  private final UUID evidenceId;
  private final UUID requestId;
  private final UUID accountId;
  private final String purpose;
  private final UUID creationOperationId;
  private final String requestDigest;
  private final AccountIdentityProvenance identityProvenance;
  private final long identitySourceNumericId;
  private final AccountLifecycleState lifecycleState;
  private final long accountAuthorityGeneration;
  private final long accountAuthoritySourceVersion;
  private final long issuanceFence;
  private final long issuanceFenceSourceVersion;
  private final long platformPolicyVersion;
  private final long deploymentPolicyVersion;
  private final String effectivePolicyVersion;
  private final long recentAgeSeconds;
  private final long maxClockSkewSeconds;
  private final Instant issuedAt;

  AccountRecentReauthenticationEvidence(
      UUID evidenceId,
      UUID requestId,
      UUID accountId,
      String purpose,
      UUID creationOperationId,
      String requestDigest,
      AccountIdentityProvenance identityProvenance,
      long identitySourceNumericId,
      AccountLifecycleState lifecycleState,
      long accountAuthorityGeneration,
      long accountAuthoritySourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long platformPolicyVersion,
      long deploymentPolicyVersion,
      String effectivePolicyVersion,
      long recentAgeSeconds,
      long maxClockSkewSeconds,
      Instant issuedAt) {
    this.evidenceId = Objects.requireNonNull(evidenceId);
    this.requestId = Objects.requireNonNull(requestId);
    this.accountId = Objects.requireNonNull(accountId);
    this.purpose = Objects.requireNonNull(purpose);
    this.creationOperationId = Objects.requireNonNull(creationOperationId);
    this.requestDigest = Objects.requireNonNull(requestDigest);
    this.identityProvenance = Objects.requireNonNull(identityProvenance);
    this.identitySourceNumericId = identitySourceNumericId;
    this.lifecycleState = Objects.requireNonNull(lifecycleState);
    this.accountAuthorityGeneration = accountAuthorityGeneration;
    this.accountAuthoritySourceVersion = accountAuthoritySourceVersion;
    this.issuanceFence = issuanceFence;
    this.issuanceFenceSourceVersion = issuanceFenceSourceVersion;
    this.platformPolicyVersion = platformPolicyVersion;
    this.deploymentPolicyVersion = deploymentPolicyVersion;
    this.effectivePolicyVersion = Objects.requireNonNull(effectivePolicyVersion);
    this.recentAgeSeconds = recentAgeSeconds;
    this.maxClockSkewSeconds = maxClockSkewSeconds;
    this.issuedAt = Objects.requireNonNull(issuedAt);
    if (AccountRecentReauthenticationPolicy.PURPOSE.equals(purpose) == false
        || identitySourceNumericId <= 0L
        || lifecycleState != AccountLifecycleState.ACTIVE
        || accountAuthorityGeneration <= 0L
        || accountAuthoritySourceVersion <= 0L
        || issuanceFence <= 0L
        || issuanceFenceSourceVersion <= 0L
        || platformPolicyVersion <= 0L
        || deploymentPolicyVersion <= 0L
        || recentAgeSeconds <= 0L
        || maxClockSkewSeconds < 0L
        || maxClockSkewSeconds >= recentAgeSeconds) {
      throw new IllegalArgumentException("Immutable recent-reauthentication evidence is invalid");
    }
  }

  public UUID evidenceId() {
    return evidenceId;
  }

  public UUID requestId() {
    return requestId;
  }

  public UUID accountId() {
    return accountId;
  }

  public String purpose() {
    return purpose;
  }

  public UUID creationOperationId() {
    return creationOperationId;
  }

  public String requestDigest() {
    return requestDigest;
  }

  public AccountIdentityProvenance identityProvenance() {
    return identityProvenance;
  }

  public long identitySourceNumericId() {
    return identitySourceNumericId;
  }

  public AccountLifecycleState lifecycleState() {
    return lifecycleState;
  }

  public long accountAuthorityGeneration() {
    return accountAuthorityGeneration;
  }

  public long accountAuthoritySourceVersion() {
    return accountAuthoritySourceVersion;
  }

  public long issuanceFence() {
    return issuanceFence;
  }

  public long issuanceFenceSourceVersion() {
    return issuanceFenceSourceVersion;
  }

  public long platformPolicyVersion() {
    return platformPolicyVersion;
  }

  public long deploymentPolicyVersion() {
    return deploymentPolicyVersion;
  }

  public String effectivePolicyVersion() {
    return effectivePolicyVersion;
  }

  public long recentAgeSeconds() {
    return recentAgeSeconds;
  }

  public long maxClockSkewSeconds() {
    return maxClockSkewSeconds;
  }

  public Instant issuedAt() {
    return issuedAt;
  }

  @Override
  public String toString() {
    return "AccountRecentReauthenticationEvidence[redacted]";
  }
}
