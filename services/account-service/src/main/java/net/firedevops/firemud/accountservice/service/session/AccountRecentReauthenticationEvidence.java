package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;

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
  private final BillingContext billingContext;

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
    this(
        evidenceId,
        requestId,
        accountId,
        purpose,
        creationOperationId,
        requestDigest,
        identityProvenance,
        identitySourceNumericId,
        lifecycleState,
        accountAuthorityGeneration,
        accountAuthoritySourceVersion,
        issuanceFence,
        issuanceFenceSourceVersion,
        platformPolicyVersion,
        deploymentPolicyVersion,
        effectivePolicyVersion,
        recentAgeSeconds,
        maxClockSkewSeconds,
        issuedAt,
        null);
  }

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
      Instant issuedAt,
      BillingContext billingContext) {
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
    this.billingContext = billingContext;
    if (!AccountRecentReauthenticationPolicy.supportedPurpose(purpose)
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
    if (billingContext != null
        && (!AccountRecentReauthenticationPolicy.BILLING_PURPOSE.equals(purpose)
            || !accountId.equals(billingContext.accountId())
            || !creationOperationId.equals(billingContext.authorizationRequestId())
            || !requestDigest.equals(
                AccountRecentReauthenticationRepository.digest(
                    AccountRecentReauthenticationRepository.requestPayload(
                        requestId, accountId, purpose, creationOperationId, billingContext))))) {
      throw new IllegalArgumentException("Immutable billing reauthentication binding is invalid");
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

  /** Null only for historical/unbound billing rows and non-billing reauthentication purposes. */
  public BillingContext billingContext() {
    return billingContext;
  }

  @Override
  public String toString() {
    return "AccountRecentReauthenticationEvidence[redacted]";
  }

  /** Exact immutable checkout context; credentials never enter this value. */
  public record BillingContext(
      UUID accountId,
      UUID subscriptionId,
      UUID originalRequestId,
      String tupleDigest,
      long expectedRowVersion,
      Instant invoiceExpiresAt,
      UUID authorizationRequestId,
      long expectedAuthorizationVersion,
      BillingAuthorizationPurpose authorizationPurpose) {
    private static final DateTimeFormatter CANONICAL_MICROSECOND_INSTANT =
        new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);

    public BillingContext {
      AccountBillingCustomerRecord.requireUuid(accountId);
      AccountBillingCustomerRecord.requireUuid(subscriptionId);
      AccountBillingCustomerRecord.requireUuid(originalRequestId);
      AccountBillingCustomerRecord.requireUuid(authorizationRequestId);
      Objects.requireNonNull(invoiceExpiresAt);
      Objects.requireNonNull(authorizationPurpose);
      if (tupleDigest == null
          || !tupleDigest.matches("sha256:[0-9a-f]{64}")
          || expectedRowVersion <= 0L
          || expectedAuthorizationVersion < 0L
          || invoiceExpiresAt.getNano() % 1_000 != 0) {
        throw new IllegalArgumentException("Exact billing reauthentication context required");
      }
      switch (authorizationPurpose) {
        case INITIAL_CONFIRMATION -> {
          if (!originalRequestId.equals(authorizationRequestId)
              || expectedAuthorizationVersion != 0L) {
            throw new IllegalArgumentException("Initial billing proof must name version zero");
          }
        }
        case PRE_ROW_RENEWAL -> {
          if (originalRequestId.equals(authorizationRequestId)
              || expectedAuthorizationVersion != 0L) {
            throw new IllegalArgumentException(
                "Pre-row renewal must name a distinct version-zero attempt");
          }
        }
        case FOLLOWUP_RENEWAL -> {
          if (originalRequestId.equals(authorizationRequestId)
              || expectedAuthorizationVersion < 1L) {
            throw new IllegalArgumentException("Follow-up renewal must name a distinct retained attempt");
          }
        }
      }
    }

    public byte[] canonicalPayload() {
      String payload =
          "account-hosting-billing-reauth-context/v1\n"
              + "accountId="
              + accountId
              + "\nsubscriptionId="
              + subscriptionId
              + "\noriginalRequestId="
              + originalRequestId
              + "\ntupleDigest="
              + tupleDigest
              + "\nexpectedRowVersion="
              + expectedRowVersion
              + "\ninvoiceExpiresAt="
              + CANONICAL_MICROSECOND_INSTANT.format(invoiceExpiresAt)
              + "\nauthorizationRequestId="
              + authorizationRequestId
              + "\nexpectedAuthorizationVersion="
              + expectedAuthorizationVersion
              + "\nauthorizationPurpose="
              + authorizationPurpose.name()
              + "\n";
      return payload.getBytes(StandardCharsets.UTF_8);
    }

    public String digest() {
      return AccountRecentReauthenticationRepository.digest(canonicalPayload());
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationEvidence.BillingContext[redacted]";
    }
  }

  public enum BillingAuthorizationPurpose {
    INITIAL_CONFIRMATION,
    PRE_ROW_RENEWAL,
    FOLLOWUP_RENEWAL
  }
}
