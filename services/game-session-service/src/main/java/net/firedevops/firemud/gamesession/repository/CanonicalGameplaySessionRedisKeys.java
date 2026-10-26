package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;

/** Canonical tenant-tagged gameplay key construction from a persisted repository assignment. */
public final class CanonicalGameplaySessionRedisKeys {
  private CanonicalGameplaySessionRedisKeys() {}

  public static String session(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayBindingRef expectedBindingRef) {
    requireBinding(assignment, expectedBindingRef);
    return "session:game:{"
        + tag(assignment)
        + "}:"
        + assignment.gameInstanceId()
        + ":"
        + assignment.sessionId();
  }

  public static String characterIndex(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayBindingRef expectedBindingRef) {
    requireBinding(assignment, expectedBindingRef);
    return "session:game:index:character:{"
        + tag(assignment)
        + "}:"
        + assignment.playableStateNamespaceId()
        + ":"
        + assignment.characterId();
  }

  public static String accountTenantIndex(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayBindingRef expectedBindingRef) {
    requireBinding(assignment, expectedBindingRef);
    return "session:game:index:account-tenant:{" + tag(assignment) + "}:" + assignment.accountId();
  }

  public static String tenantIndex(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayBindingRef expectedBindingRef) {
    requireBinding(assignment, expectedBindingRef);
    return "session:game:index:tenant:{" + tag(assignment) + "}";
  }

  /** Builds the future shard-local CLOSED barrier key for an exact frozen SQL target. */
  public static String accountAdmissionBarrier(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    Objects.requireNonNull(assignment, "assignment");
    if (assignment.isBindingTransition() || assignment.admissionFenceIntentOperationId() == null) {
      throw new IllegalArgumentException(
          "Account admission barrier keys require a persisted acquisition-intent target");
    }
    return "session:game:account-admission:{"
        + tag(assignment)
        + "}:"
        + assignment.accountId()
        + ":"
        + assignment.playableStateNamespaceId();
  }

  private static void requireBinding(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayBindingRef expectedBindingRef) {
    Objects.requireNonNull(assignment, "assignment");
    Objects.requireNonNull(expectedBindingRef, "expectedBindingRef");
    CanonicalGameplayBindingRef assigned = assignment.bindingRef();
    if (!assignment.isBindingTransition()
        || assigned == null
        || !assigned.equals(expectedBindingRef)
        || !assignment.tenantId().toString().equals(expectedBindingRef.tenantId())) {
      throw new IllegalArgumentException(
          "Canonical gameplay key bindingRef differs from the persisted tenant assignment");
    }
  }

  private static String tag(CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    String tag = assignment.tenantGameplayTag();
    CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(tag);
    return tag;
  }
}
