package net.firedevops.firemud.common.account.capacity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;

/** Account-owned existing recovery policy, not execution, release, or rearm authority. */
public final class AccountCapacityOriginContinuation {
  public static final int MAX_OBSERVATIONS = 12;
  public static final int RECONCILE_SECONDS = 60;
  public static final int OBSERVATION_SECONDS = 5;

  private AccountCapacityOriginContinuation() {}

  public record Policy(Instant deadline, int maxObservations) {
    public Policy {
      Objects.requireNonNull(deadline);
      if (maxObservations != MAX_OBSERVATIONS)
        throw new IllegalArgumentException("Account observation policy required");
    }
  }

  public record Admission(int ordinal, Instant deadline) {
    public Admission {
      if (ordinal < 1 || ordinal > MAX_OBSERVATIONS)
        throw new IllegalArgumentException("Invalid observation ordinal");
      Objects.requireNonNull(deadline);
    }
  }

  /**
   * Game Session must retain this observation and its current custody fence before returning it.
   */
  public record Permit(
      String requestDigest,
      String originDigest,
      String custodyOwner,
      long custodyFence,
      int ordinal,
      Instant deadline) {
    public Permit {
      if (requestDigest == null
          || !requestDigest.matches("[0-9a-f]{64}")
          || originDigest == null
          || !originDigest.matches("sha256:[0-9a-f]{64}")
          || custodyFence < 1) throw new IllegalArgumentException("Exact custody required");
      if (custodyOwner == null || !custodyOwner.matches("[A-Za-z0-9._:-]{1,120}"))
        throw new IllegalArgumentException("Exact custody owner required");
      new Admission(ordinal, deadline);
    }

    public void requireRequest(Request request) {
      request.requireStructurallyValid();
      if (!requestDigest.equals(request.mutationDigest()))
        throw new IllegalArgumentException("Observation belongs to another request");
    }
  }

  public static Policy preAccount(Request request) {
    request.requireStructurallyValid();
    return new Policy(reconcileDeadline(request.evidence().expiresAt()), MAX_OBSERVATIONS);
  }

  public static Instant reconcileDeadline(Instant expiresAt) {
    return Objects.requireNonNull(expiresAt)
        .truncatedTo(ChronoUnit.MICROS)
        .plusSeconds(RECONCILE_SECONDS);
  }

  /** The deadline argument must come from the actual retained Account reservation row. */
  public static Policy retained(
      Request request, Result reservation, Instant authoritativeDeadline) {
    request.requireStructurallyValid();
    reservation.requireBinding(request);
    if (!reconcileDeadline(reservation.expiresAt()).equals(authoritativeDeadline))
      throw new IllegalArgumentException("Retained reservation policy mismatch");
    return new Policy(authoritativeDeadline, MAX_OBSERVATIONS);
  }

  /** No clock reset at discovery: use the retained policy and carry all origin observations. */
  public static Optional<Admission> admit(
      Policy policy, int consumed, Instant now, Instant callerDeadline) {
    Objects.requireNonNull(policy);
    Objects.requireNonNull(now);
    Objects.requireNonNull(callerDeadline);
    if (consumed < 0 || consumed > MAX_OBSERVATIONS)
      throw new IllegalArgumentException("Invalid consumed observation count");
    Instant deadline = now.plusSeconds(OBSERVATION_SECONDS);
    if (policy.deadline().isBefore(deadline)) deadline = policy.deadline();
    if (callerDeadline.isBefore(deadline)) deadline = callerDeadline;
    deadline = deadline.truncatedTo(ChronoUnit.MICROS);
    if (consumed == policy.maxObservations() || !now.isBefore(deadline)) return Optional.empty();
    return Optional.of(new Admission(consumed + 1, deadline));
  }
}
