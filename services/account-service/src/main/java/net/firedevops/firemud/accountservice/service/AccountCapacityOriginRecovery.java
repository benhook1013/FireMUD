package net.firedevops.firemud.accountservice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;

/**
 * Manually composed single bounded recovery attempt. No scheduler, release, rearm or registration.
 * Durable attempt budgets/next-attempt custody belong to the retained Game Session origin owner;
 * this adapter never restarts a budget or derives a reservation from an absent Account result.
 */
public final class AccountCapacityOriginRecovery {
  public enum Outcome {
    DELIVERED,
    HELD_UNAVAILABLE
  }

  /** Authenticated Account-to-Game Session mutation, separate from execution readback. */
  public interface RecoveryCommand {
    /** Durably consumes one observation before Account lookup, including unknown/lost outcomes. */
    Permit beginObservation(Request request, Instant callerDeadline);

    /**
     * The receiver validates exact ordinal, deadline, request and current physical custody fence.
     */
    void receiveExact(AccountCapacityOriginResolution resolution, Permit permit);
  }

  private final AccountTenantCapacityOwner account;
  private final RecoveryCommand receiver;
  private final Clock clock;

  public AccountCapacityOriginRecovery(
      AccountTenantCapacityOwner account, RecoveryCommand receiver, Clock clock) {
    this.account = Objects.requireNonNull(account);
    this.receiver = Objects.requireNonNull(receiver);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * One durable observation admission, one Account transaction and at most one completion call. No
   * SQL is held over transport. Even an unknown or lost Account reply consumes the observation. The
   * receiver must enforce the absolute deadline at its actual transport. A lost reply retries the
   * same immutable decision on a later admitted attempt, never Commit for discovery.
   */
  public Outcome attempt(
      Request request, String authenticatedCaller, boolean retireIfUnaccepted, Instant deadline) {
    Objects.requireNonNull(deadline);
    Instant now = clock.instant();
    try {
      request.requireStructurallyValid();
      if (!request.evidence().callerWorkload().equals(authenticatedCaller))
        return Outcome.HELD_UNAVAILABLE;
      var initial =
          AccountCapacityOriginContinuation.admit(
              AccountCapacityOriginContinuation.preAccount(request), 0, now, deadline);
      if (initial.isEmpty()) return Outcome.HELD_UNAVAILABLE;
      Permit permit = receiver.beginObservation(request, initial.get().deadline());
      Objects.requireNonNull(permit).requireRequest(request);
      if (permit.deadline().isAfter(initial.get().deadline())
          || !clock.instant().isBefore(permit.deadline())) return Outcome.HELD_UNAVAILABLE;
      var resolution = account.resolveOrigin(request, authenticatedCaller, retireIfUnaccepted);
      if (!clock.instant().isBefore(permit.deadline()) || resolution.status() == Status.UNKNOWN)
        return Outcome.HELD_UNAVAILABLE;
      receiver.receiveExact(resolution, permit);
      return clock.instant().isBefore(permit.deadline())
          ? Outcome.DELIVERED
          : Outcome.HELD_UNAVAILABLE;
    } catch (RuntimeException unavailableOrLostReply) {
      return Outcome.HELD_UNAVAILABLE;
    }
  }
}
