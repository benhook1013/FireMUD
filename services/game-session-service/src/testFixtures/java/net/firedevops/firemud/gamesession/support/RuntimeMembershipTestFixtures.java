package net.firedevops.firemud.gamesession.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.RuntimeAuthorityTuple;
import net.firedevops.firemud.account.v1.RuntimeMembershipBaseline;
import net.firedevops.firemud.account.v1.RuntimeOutboxCheckpoint;
import net.firedevops.firemud.account.v1.RuntimeOutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Complete or intentionally incomplete fixtures for the denied runtime membership carrier. */
public final class RuntimeMembershipTestFixtures {
  private static final String STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String ISSUER = "firemud-account-service";
  private static final String EVENT_ID = "00000000-0000-0000-0000-000000000099";

  private RuntimeMembershipTestFixtures() {}

  public static GetTenantMembershipForRuntimeResponse active(
      long accountId, long tenantId, String membershipVersion) {
    return complete(accountId, tenantId, true, "ACTIVE", true, membershipVersion);
  }

  public static GetTenantMembershipForRuntimeResponse missing(long accountId, long tenantId) {
    return complete(accountId, tenantId, false, "MISSING", false, "1");
  }

  public static GetTenantMembershipForRuntimeResponse inactive(
      long accountId, long tenantId, String membershipVersion) {
    return complete(accountId, tenantId, true, "INACTIVE", false, membershipVersion);
  }

  public static GetTenantMembershipForRuntimeResponse echoRequestId(
      GetTenantMembershipForRuntimeResponse response, PlayerExecutionContext request) {
    return response.toBuilder().setRequestId(request.getRequestId()).build();
  }

  public static GetTenantMembershipForRuntimeResponse incomplete(
      long accountId,
      long tenantId,
      boolean membershipExists,
      String lifecycle,
      boolean gameplayAdmissionAllowed,
      String membershipVersion,
      String evaluatedAt) {
    String accountUuid = uuid(accountId);
    String tenantUuid = uuid(tenantId);
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(tenantUuid)
            .setRequestAccountId(Long.toString(accountId))
            .setRequestTenantId(Long.toString(tenantId))
            .setMembershipExists(membershipExists)
            .setMembershipLifecycleState(lifecycle)
            .setGameplayAdmissionAllowed(gameplayAdmissionAllowed)
            .setEvaluatedAt(evaluatedAt);
    if (membershipVersion != null) {
      response.putMembershipVersion(tenantUuid, membershipVersion);
    }
    return response.build();
  }

  public static GetTenantMembershipForRuntimeResponse advancedTenantWithZeroCheckpoint(
      long accountId, long tenantId) {
    GetTenantMembershipForRuntimeResponse response = active(accountId, tenantId, "1");
    return response.toBuilder()
        .setAuthorityTuple(
            response.getAuthorityTuple().toBuilder()
                .putTenantAuthorityGeneration(uuid(tenantId), "2"))
        .build();
  }

  private static GetTenantMembershipForRuntimeResponse complete(
      long accountId,
      long tenantId,
      boolean exists,
      String lifecycle,
      boolean admitted,
      String membershipVersion) {
    String accountUuid = uuid(accountId);
    String tenantUuid = uuid(tenantId);
    String membershipStream = STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
    List<RuntimeOutboxCheckpoint> checkpoints =
        new ArrayList<>(
            List.of(
                checkpoint(STREAM_PREFIX + "account/" + accountUuid, "0"),
                checkpoint(STREAM_PREFIX + "issuer/" + ISSUER, "0"),
                checkpoint(membershipStream, exists ? "1" : "0"),
                checkpoint(STREAM_PREFIX + "tenant/" + tenantUuid, "0")));
    checkpoints.sort(
        (first, second) -> first.getOutboxStreamKey().compareTo(second.getOutboxStreamKey()));
    RuntimeAuthorityTuple tuple =
        RuntimeAuthorityTuple.newBuilder()
            .setIssuerAuthGeneration("1")
            .setAccountAuthorityGeneration("1")
            .putTenantAuthorityGeneration(tenantUuid, "1")
            .putMembershipAuthorityGeneration(tenantUuid, "1")
            .build();
    RuntimeMembershipBaseline baseline =
        RuntimeMembershipBaseline.newBuilder()
            .setMembershipLifecycleState(lifecycle)
            .putMembershipVersion(tenantUuid, membershipVersion)
            .setMembershipAuthorityGeneration("1")
            .build();
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(tenantUuid)
            .setRequestAccountId(Long.toString(accountId))
            .setRequestTenantId(Long.toString(tenantId))
            .setMembershipExists(exists)
            .setMembershipLifecycleState(lifecycle)
            .setGameplayAdmissionAllowed(admitted)
            .putMembershipVersion(tenantUuid, membershipVersion)
            .setMembershipAuthorityGeneration("1")
            .setMembershipBaseline(baseline)
            .setAuthorityTuple(tuple)
            .setIssuanceFence("1")
            .addAllOutboxCheckpoints(checkpoints)
            .setEvaluatedAt(Instant.now().toString());
    if (admitted) {
      response.addRoles("player");
    }
    if (exists) {
      MembershipAuthorityEventV1Codec.MembershipEvent event =
          MembershipAuthorityEventV1Codec.seal(
              Map.ofEntries(
                  Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                  Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                  Map.entry("eventId", EVENT_ID),
                  Map.entry("requestId", "runtime-membership-request"),
                  Map.entry("outboxStreamKey", membershipStream),
                  Map.entry("outboxSequence", "1"),
                  Map.entry("sourceScope", "membership/" + accountUuid + "/" + tenantUuid),
                  Map.entry("accountId", accountUuid),
                  Map.entry("tenantId", tenantUuid),
                  Map.entry("membershipExists", true),
                  Map.entry("membershipLifecycleState", lifecycle),
                  Map.entry("membershipVersion", Map.of(tenantUuid, membershipVersion)),
                  Map.entry("membershipAuthorityGeneration", "1"),
                  Map.entry(
                      "authorityTuple",
                      Map.of(
                          "issuerAuthGeneration", "1",
                          "accountAuthorityGeneration", "1",
                          "tenantAuthorityGeneration", Map.of(tenantUuid, "1"),
                          "membershipAuthorityGeneration", Map.of(tenantUuid, "1"),
                          "privateRealmGrantVersions", List.of())),
                  Map.entry("issuanceFence", "1"),
                  Map.entry("roles", admitted ? List.of("player") : List.of()),
                  Map.entry("gameplayAdmissionAllowed", admitted),
                  Map.entry("callerBoundAuthorityInvalidated", false)));
      response.addOutboxSourceEvidence(
          RuntimeOutboxSourceEvidence.newBuilder()
              .setOutboxStreamKey(membershipStream)
              .setOutboxSequence("1")
              .setEventId(event.eventId())
              .setEventDigest(event.eventDigest())
              .setCanonicalEventJson(event.canonicalJson()));
    }
    return response.build();
  }

  private static RuntimeOutboxCheckpoint checkpoint(String streamKey, String sequence) {
    return RuntimeOutboxCheckpoint.newBuilder()
        .setOutboxStreamKey(streamKey)
        .setOutboxSequence(sequence)
        .build();
  }

  private static String uuid(long id) {
    if (id <= 0L) {
      throw new IllegalArgumentException("test selector IDs must be positive");
    }
    return "00000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", id);
  }
}
