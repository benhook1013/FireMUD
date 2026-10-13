package net.firedevops.firemud.loggingadmin.operator;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.SessionContext;

/** Logging/Admin authority wrapper around the shared immutable StartSession tuple value. */
public final class StartSessionPreAuthorizationReservationTuple {
  public static final String TUPLE_SCHEMA_ID =
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
          .TUPLE_SCHEMA_ID;
  public static final String TUPLE_SCHEMA_VERSION =
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
          .TUPLE_SCHEMA_VERSION;
  public static final int MAX_CONTROL_PLANE_REQUEST_ID_UTF8_BYTES =
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
          .MAX_CONTROL_PLANE_REQUEST_ID_UTF8_BYTES;
  public static final int MAX_CANONICAL_TUPLE_UTF8_BYTES = 8 * 1_024;

  private final net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      value;
  private final boolean authorityDerived;

  private StartSessionPreAuthorizationReservationTuple(
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple value,
      boolean authorityDerived) {
    this.value = Objects.requireNonNull(value, "tuple value is required");
    this.authorityDerived = authorityDerived;
  }

  /** Builds the tuple from the current authenticated tenant-admin request context. */
  public static StartSessionPreAuthorizationReservationTuple fromCurrentTenantAdmin(
      String controlPlaneRequestId, StartSessionOperatorAction action) {
    Objects.requireNonNull(action, "action is required");
    if (SessionContext.isInternalService() || !SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalArgumentException("a human control-ui identity is required");
    }
    String tenantId = action.scope().tenantId().toString();
    if (!SessionContext.getScopedRoles(tenantId).contains("tenantAdmin")) {
      throw new IllegalArgumentException("tenantAdmin authority is required for the target tenant");
    }
    UUID actorAccountId = requireCanonicalUuid(SessionContext.getAccountId(), "actor accountId");
    return new StartSessionPreAuthorizationReservationTuple(
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .createHuman(controlPlaneRequestId, actorAccountId, action),
        true);
  }

  /** Rehydrates only tuple contents; persisted bytes do not establish request authority. */
  public static StartSessionPreAuthorizationReservationTuple fromCanonicalJson(String json) {
    return new StartSessionPreAuthorizationReservationTuple(
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .fromCanonicalJson(json),
        false);
  }

  public String actionFamily() {
    return value.actionFamily();
  }

  public String controlPlaneRequestId() {
    return value.controlPlaneRequestId();
  }

  public HumanActor actor() {
    return new HumanActor(value.actor().accountId());
  }

  public NoAutomationPolicy automationPolicy() {
    return NoAutomationPolicy.ABSENT;
  }

  public StartSessionOperatorAction action() {
    return value.action();
  }

  public boolean isAuthorityDerived() {
    return authorityDerived;
  }

  /** Validate a stable request key without silently normalizing or changing its identity. */
  public static String requireCanonicalControlPlaneRequestId(String value) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .requireCanonicalControlPlaneRequestId(value);
  }

  public String targetOwner() {
    return value.targetOwner();
  }

  /** Returns ADR 0047's digest over the seven action-schema grammar fields. */
  public String mutationDigest() {
    return value.mutationDigest();
  }

  /** Returns the single shared canonical encoding for ADR 0048's typed tuple. */
  public String canonicalJson() {
    return value.canonicalJson();
  }

  public record HumanActor(UUID accountId) {
    public HumanActor {
      Objects.requireNonNull(accountId, "actor accountId is required");
      if (accountId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("actor accountId must not be nil");
      }
    }
  }

  public enum NoAutomationPolicy {
    ABSENT
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof StartSessionPreAuthorizationReservationTuple that
            && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  private static UUID requireCanonicalUuid(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " is required");
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(fieldName + " must be a canonical UUID", exception);
    }
    if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(fieldName + " must be a canonical non-nil UUID");
    }
    return parsed;
  }
}
