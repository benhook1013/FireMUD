package net.firedevops.firemud.accountservice.repository;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;

/**
 * Pure hosting lifecycle mapping of actual SDK observations. Manually composed; no live defaults,
 * timers, provider mutation, current-row selection, or entitlement authority. The lifecycle owner
 * must separately fence the original binding and atomically commit its receipt and canonical
 * events.
 */
public final class AccountHostingSubscriptionLifecyclePolicy {
  private final Duration maximumObservationAge;
  private final RecoveryTiming recovery;

  public AccountHostingSubscriptionLifecyclePolicy(
      Duration maximumObservationAge, RecoveryTiming recovery) {
    this.maximumObservationAge = positive(maximumObservationAge, "maximum observation age");
    this.recovery =
        Objects.requireNonNull(recovery, "Explicit payment recovery timing is required");
  }

  public Duration maximumObservationAge() {
    return maximumObservationAge;
  }

  public RecoveryTiming recoveryTiming() {
    return recovery;
  }

  /**
   * The recovery origin must come from retained Account history for this uninterrupted payment
   * failure episode, never from the delivery time of a retry. Null means that origin is
   * unavailable; it does not start a new warning or grace window. Results are mapping evidence, not
   * permissions.
   */
  public Decision evaluate(
      StripeHostingSubscriptionObserver.Result result,
      Instant evaluatedAt,
      Instant retainedPastDueSince) {
    return evaluate(result, evaluatedAt, retainedPastDueSince, false);
  }

  private Decision evaluate(
      StripeHostingSubscriptionObserver.Result result,
      Instant evaluatedAt,
      Instant retainedPastDueSince,
      boolean allowIncompleteExpiredFirstInvoiceCancellation) {
    Objects.requireNonNull(result, "Actual SDK read result is required");
    Objects.requireNonNull(evaluatedAt, "Account evaluation time is required");
    if (!(result instanceof ObservedSubscription observed)) {
      return unavailable(Reason.PROVIDER_UNRESOLVED);
    }
    if (observed.readStartedAt().isAfter(observed.observedAt())
        || observed.observedAt().isAfter(evaluatedAt)) {
      return unavailable(Reason.UNSAFE_READ_INTERVAL);
    }
    // Read-start, rather than response-arrival, bounds the age of provider knowledge. A slow
    // response cannot refresh stale input merely because it completed recently.
    Instant observationDeadline =
        plus(
            observed.readStartedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
            maximumObservationAge);
    if (!evaluatedAt.isBefore(observationDeadline)) {
      return unavailable(Reason.OBSERVATION_EXPIRED);
    }
    if (observed.providerStatusIsCanceled()
        || (allowIncompleteExpiredFirstInvoiceCancellation
            && observed.providerStatusIsIncompleteExpired())) {
      return terminalCancellation(observed, evaluatedAt, observationDeadline);
    }
    // Stripe's incomplete_expired is a terminal outcome only for the dedicated unpaid
    // first-invoice cancellation owner. It must not enter generic lifecycle mapping, including
    // through a coincident effective cancel_at value.
    if (observed.providerStatusIsIncompleteExpired()) {
      return unavailable(Reason.UNMAPPED_PROVIDER_STATUS);
    }

    Instant start = observed.currentPeriodStart().orElse(null);
    Instant end = observed.currentPeriodEnd().orElse(null);
    if (start == null || end == null || !start.isBefore(end) || evaluatedAt.isBefore(start)) {
      return unavailable(Reason.PERIOD_UNRESOLVED);
    }
    if (observed.endedAt().isPresent()) {
      return unavailable(Reason.CONTRADICTORY_PROVIDER_FACTS);
    }
    Instant cancellation = observed.cancelAt().orElse(null);
    if (observed.cancelAtPeriodEnd()) {
      cancellation = earlier(cancellation, end);
    }
    if (cancellation != null && !evaluatedAt.isBefore(cancellation)) {
      return new Decision(
          Status.CANCELED,
          Reason.EFFECTIVE_CANCELLATION,
          observationDeadline,
          observationDeadline,
          cancellation);
    }
    if (!evaluatedAt.isBefore(end)) {
      // An expired period without a confirmed end schedule is unknown renewal state, not an
      // invented cancellation and not permission to extend the last positive period.
      return unavailable(Reason.PERIOD_UNRESOLVED);
    }
    Instant receiptValidUntil = earlier(end, cancellation);
    Instant validUntil = earlier(observationDeadline, receiptValidUntil);
    return switch (observed.providerStatus()) {
      case "active" ->
          new Decision(
              Status.ACTIVE, Reason.PROVIDER_CONFIRMED, validUntil, receiptValidUntil, null);
      case "trialing" ->
          new Decision(
              Status.TRIALING, Reason.PROVIDER_CONFIRMED, validUntil, receiptValidUntil, null);
      case "past_due" ->
          pastDue(evaluatedAt, retainedPastDueSince, receiptValidUntil, observationDeadline);
      // These provider values cannot establish an active/trialing hosting agreement. Their
      // resolution remains explicit rather than inventing a grace policy for an incomplete or
      // paused subscription. Automatic suspension is driven by the configured past_due episode.
      default -> unavailable(Reason.UNMAPPED_PROVIDER_STATUS);
    };
  }

  /**
   * Maps only exact Stripe incomplete_expired evidence for the dedicated unpaid first-invoice
   * cancellation path. Generic lifecycle evaluation deliberately leaves that raw status unmapped.
   */
  public Decision evaluateIncompleteExpiredFirstInvoiceCancellation(
      ObservedSubscription observed, Instant evaluatedAt) {
    Objects.requireNonNull(observed, "Actual SDK read result is required");
    Objects.requireNonNull(evaluatedAt, "Account evaluation time is required");
    if (!observed.providerStatusIsIncompleteExpired()) {
      return unavailable(Reason.UNMAPPED_PROVIDER_STATUS);
    }
    return evaluate(observed, evaluatedAt, null, true);
  }

  private Decision terminalCancellation(
      ObservedSubscription observed, Instant evaluatedAt, Instant observationDeadline) {
    // canceled_at is the cancellation-request time for period-end cancellation, NOT paid-through.
    Instant endedAt =
        observed
            .endedAt()
            .orElse(observed.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    if (endedAt.isAfter(evaluatedAt)) {
      return unavailable(Reason.CONTRADICTORY_PROVIDER_FACTS);
    }
    return new Decision(
        Status.CANCELED,
        Reason.PROVIDER_CONFIRMED,
        observationDeadline,
        observationDeadline,
        endedAt);
  }

  private Decision pastDue(
      Instant now, Instant origin, Instant receiptValidUntil, Instant observationDeadline) {
    if (origin == null || origin.isAfter(now)) {
      return unavailable(Reason.RECOVERY_ORIGIN_UNRESOLVED);
    }
    Instant graceAt = plus(origin, recovery.pastDueWarning());
    Instant suspendAt = plus(graceAt, recovery.grace());
    if (!now.isBefore(suspendAt)) {
      return new Decision(
          Status.SUSPENDED,
          Reason.PAYMENT_RECOVERY_EXHAUSTED,
          earlier(observationDeadline, receiptValidUntil),
          receiptValidUntil,
          suspendAt);
    }
    if (!now.isBefore(graceAt)) {
      return new Decision(
          Status.GRACE,
          Reason.PAYMENT_RECOVERY,
          earlier(observationDeadline, earlier(receiptValidUntil, suspendAt)),
          earlier(receiptValidUntil, suspendAt),
          null);
    }
    return new Decision(
        Status.PAST_DUE,
        Reason.PAYMENT_RECOVERY,
        earlier(observationDeadline, earlier(receiptValidUntil, graceAt)),
        earlier(receiptValidUntil, graceAt),
        null);
  }

  private static Decision unavailable(Reason reason) {
    return new Decision(null, reason, null, null, null);
  }

  private static Instant earlier(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    return right == null || left.isBefore(right) ? left : right;
  }

  private static Instant plus(Instant instant, Duration duration) {
    try {
      return instant.plus(duration);
    } catch (DateTimeException | ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "Configured lifecycle timing overflows the UTC time domain", overflow);
    }
  }

  private static Duration positive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Explicit positive " + name + " is required");
    }
    // Validate the configuration, not only whichever branch first happens to consume it.
    plus(Instant.EPOCH, duration);
    return duration;
  }

  public record RecoveryTiming(Duration pastDueWarning, Duration grace) {
    public RecoveryTiming {
      pastDueWarning = positive(pastDueWarning, "past-due warning duration");
      grace = positive(grace, "grace duration");
      try {
        plus(Instant.EPOCH, pastDueWarning.plus(grace));
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException("Combined recovery timing overflows", overflow);
      }
    }
  }

  public enum Status {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    GRACE,
    SUSPENDED,
    CANCELED;

    public String storageValue() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public enum Reason {
    PROVIDER_CONFIRMED,
    EFFECTIVE_CANCELLATION,
    PAYMENT_RECOVERY,
    PAYMENT_RECOVERY_EXHAUSTED,
    PROVIDER_UNRESOLVED,
    UNSAFE_READ_INTERVAL,
    OBSERVATION_EXPIRED,
    PERIOD_UNRESOLVED,
    CONTRADICTORY_PROVIDER_FACTS,
    RECOVERY_ORIGIN_UNRESOLVED,
    UNMAPPED_PROVIDER_STATUS
  }

  /**
   * Null status denotes unavailable mapping, not a known suspended/canceled entitlement. The fresh
   * read's validUntil includes observation age; receiptValidUntil retains the provider/policy fact
   * boundary so a later independently fresh read does not require another lifecycle mutation.
   */
  public record Decision(
      Status status,
      Reason reason,
      Instant validUntil,
      Instant receiptValidUntil,
      Instant cutoffAt) {
    public boolean resolved() {
      return status != null;
    }
  }
}
