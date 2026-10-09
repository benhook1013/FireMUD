package net.firedevops.firemud.loggingadmin.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionOperatorActionCodec;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StartSessionPreAuthorizationReservationTupleTest {
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void createsCanonicalHumanOnlyTupleFromCurrentTenantAdminAndPreservesAbsence() {
    setTenantAdminContext(TENANT_ID, ACTOR_ID);
    StartSessionOperatorAction action = action(StartSessionOperatorAction.ClientIp.absent());

    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
            "start-session-request-01", action);
    String canonicalJson = tuple.canonicalJson();

    assertThat(tuple.actor().accountId()).isEqualTo(ACTOR_ID);
    assertThat(tuple.isAuthorityDerived()).isTrue();
    assertThat(tuple.automationPolicy())
        .isEqualTo(StartSessionPreAuthorizationReservationTuple.NoAutomationPolicy.ABSENT);
    assertThat(tuple.targetOwner()).isEqualTo(StartSessionOperatorAction.OWNER_SERVICE);
    assertThat(tuple.mutationDigest())
        .isEqualTo(StartSessionOperatorActionCodec.mutationDigest(action));
    assertThat(canonicalJson)
        .contains("\"requestIdentityKind\":\"controlPlaneRequestId\"")
        .contains("\"targetOwner\":{\"ownerService\":\"game-session-service\"}")
        .contains("\"expectedVersion\":{\"presence\":\"ABSENT\"}")
        .contains("\"clientIp\":{\"presence\":\"ABSENT\"}")
        .contains("\"automationPolicy\":{\"presence\":\"ABSENT\"}")
        .doesNotContain("authorityDerived");
    StartSessionPreAuthorizationReservationTuple rehydrated =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(canonicalJson);
    assertThat(rehydrated).isEqualTo(tuple);
    assertThat(rehydrated.isAuthorityDerived()).isFalse();
    assertThat(
            net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
                .fromCanonicalJson(canonicalJson)
                .canonicalJson())
        .isEqualTo(canonicalJson);
  }

  @Test
  void preservesPresentClientIpAsASeparateTypedPresenceValue() {
    setTenantAdminContext(TENANT_ID, ACTOR_ID);
    StartSessionOperatorAction action =
        action(StartSessionOperatorAction.ClientIp.of("203.0.113.8"));

    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
            "start-session-request-02", action);

    assertThat(tuple.canonicalJson())
        .contains("\"clientIp\":{\"presence\":\"PRESENT\",\"value\":\"203.0.113.8\"}");
    assertThat(
            StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(tuple.canonicalJson()))
        .isEqualTo(tuple);
  }

  @Test
  void rejectsMissingHumanAuthorityAndDoesNotAcceptOtherRoleAsTenantAdmin() {
    StartSessionOperatorAction action = action(StartSessionOperatorAction.ClientIp.absent());
    SessionContext.setContext(ACTOR_ID.toString(), List.of(), Map.of(), false, null, null);

    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                    "start-session-request-03", action))
        .isInstanceOf(IllegalArgumentException.class);

    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("moderator")),
        false,
        null,
        null);
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                    "start-session-request-03", action))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInternalOrWrongTenantAuthority() {
    StartSessionOperatorAction action = action(StartSessionOperatorAction.ClientIp.absent());
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        true,
        "logging-admin-service",
        "instance-1");
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                    "start-session-request-04", action))
        .isInstanceOf(IllegalArgumentException.class);

    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(UUID.randomUUID().toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                    "start-session-request-04", action))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonCanonicalAndUnsupportedPersistedTupleShapes() {
    setTenantAdminContext(TENANT_ID, ACTOR_ID);
    String canonicalJson =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                "start-session-request-05", action(StartSessionOperatorAction.ClientIp.absent()))
            .canonicalJson();

    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(" " + canonicalJson))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                    canonicalJson.replace("\"presence\":\"ABSENT\"", "\"presence\":\"NULL\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void setTenantAdminContext(UUID tenantId, UUID actorId) {
    SessionContext.setContext(
        actorId.toString(),
        List.of(),
        Map.of(tenantId.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
  }

  private static StartSessionOperatorAction action(StartSessionOperatorAction.ClientIp clientIp) {
    return new StartSessionOperatorAction(
        StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
        StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
        new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
        new StartSessionOperatorAction.Target(
            91L, UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
        StartSessionOperatorAction.ExpectedVersion.ABSENT,
        new StartSessionOperatorAction.Mutation(clientIp),
        "approved StartSession prerequisite");
  }
}
