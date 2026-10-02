package net.firedevops.firemud.gamesession.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
      String accountId, long tenantId, String membershipVersion) {
    return complete(accountId, tenantId, true, "ACTIVE", true, membershipVersion);
  }

  /** Builds a complete membership carrier for an explicitly supplied canonical tenant UUID. */
  public static GetTenantMembershipForRuntimeResponse active(
      String accountId,
      long retainedTenantKey,
      String canonicalTenantId,
      String membershipVersion) {
    requireCanonicalTenantId(canonicalTenantId);
    requirePositiveRetainedTenantKey(retainedTenantKey);
    return completeWithTenant(
        accountId,
        canonicalTenantId,
        canonicalTenantId,
        true,
        "ACTIVE",
        true,
        membershipVersion,
        "1",
        "1",
        "1",
        false,
        List.of("player"));
  }

  /** Builds complete active membership authority with no player role for denial-path tests. */
  public static GetTenantMembershipForRuntimeResponse activeWithoutPlayerRole(
      String accountId,
      long retainedTenantKey,
      String canonicalTenantId,
      String membershipVersion) {
    requireCanonicalTenantId(canonicalTenantId);
    requirePositiveRetainedTenantKey(retainedTenantKey);
    return completeWithTenant(
        accountId,
        canonicalTenantId,
        canonicalTenantId,
        true,
        "ACTIVE",
        true,
        membershipVersion,
        "1",
        "1",
        "1",
        false,
        List.of("designer"));
  }

  public static GetTenantMembershipForRuntimeResponse missing(String accountId, long tenantId) {
    return complete(accountId, tenantId, false, "MISSING", false, "1");
  }

  public static GetTenantMembershipForRuntimeResponse inactive(
      String accountId, long tenantId, String membershipVersion) {
    return complete(accountId, tenantId, true, "INACTIVE", false, membershipVersion);
  }

  public static GetTenantMembershipForRuntimeResponse left(
      String accountId, long tenantId, List<String> retainedRoles) {
    return complete(
        accountId, tenantId, true, "INACTIVE", false, "3", "2", "2", "2", true, retainedRoles);
  }

  public static GetTenantMembershipForRuntimeResponse echoRequestId(
      GetTenantMembershipForRuntimeResponse response, PlayerExecutionContext request) {
    return response.toBuilder().setRequestId(request.getRequestId()).build();
  }

  public static GetTenantMembershipForRuntimeResponse incomplete(
      String accountId,
      long tenantId,
      boolean membershipExists,
      String lifecycle,
      boolean gameplayAdmissionAllowed,
      String membershipVersion,
      String evaluatedAt) {
    String accountUuid = accountId;
    String tenantUuid = uuid(tenantId);
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(tenantUuid)
            .setRequestAccountId(accountId)
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
      String accountId, long tenantId) {
    GetTenantMembershipForRuntimeResponse response = active(accountId, tenantId, "1");
    return response.toBuilder()
        .setAuthorityTuple(
            response.getAuthorityTuple().toBuilder()
                .putTenantAuthorityGeneration(uuid(tenantId), "2"))
        .build();
  }

  private static GetTenantMembershipForRuntimeResponse complete(
      String accountId,
      long tenantId,
      boolean exists,
      String lifecycle,
      boolean admitted,
      String membershipVersion) {
    return complete(
        accountId,
        tenantId,
        exists,
        lifecycle,
        admitted,
        membershipVersion,
        "1",
        "1",
        exists ? "1" : "0",
        false,
        admitted ? List.of("player") : List.of());
  }

  private static GetTenantMembershipForRuntimeResponse complete(
      String accountId,
      long tenantId,
      boolean exists,
      String lifecycle,
      boolean admitted,
      String membershipVersion,
      String membershipAuthorityGeneration,
      String issuanceFence,
      String outboxSequence,
      boolean callerBoundAuthorityInvalidated,
      List<String> roles) {
    String tenantUuid = uuid(tenantId);
    return completeWithTenant(
        accountId,
        tenantUuid,
        Long.toString(tenantId),
        exists,
        lifecycle,
        admitted,
        membershipVersion,
        membershipAuthorityGeneration,
        issuanceFence,
        outboxSequence,
        callerBoundAuthorityInvalidated,
        roles);
  }

  private static GetTenantMembershipForRuntimeResponse completeWithTenant(
      String accountId,
      String tenantUuid,
      String requestTenantId,
      boolean exists,
      String lifecycle,
      boolean admitted,
      String membershipVersion,
      String membershipAuthorityGeneration,
      String issuanceFence,
      String outboxSequence,
      boolean callerBoundAuthorityInvalidated,
      List<String> roles) {
    String accountUuid = accountId;
    String membershipStream = STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
    List<RuntimeOutboxCheckpoint> checkpoints =
        new ArrayList<>(
            List.of(
                checkpoint(STREAM_PREFIX + "account/" + accountUuid, "0"),
                checkpoint(STREAM_PREFIX + "issuer/" + ISSUER, "0"),
                checkpoint(membershipStream, outboxSequence),
                checkpoint(STREAM_PREFIX + "tenant/" + tenantUuid, "0")));
    checkpoints.sort(
        (first, second) -> first.getOutboxStreamKey().compareTo(second.getOutboxStreamKey()));
    RuntimeAuthorityTuple tuple =
        RuntimeAuthorityTuple.newBuilder()
            .setIssuerAuthGeneration("1")
            .setAccountAuthorityGeneration("1")
            .putTenantAuthorityGeneration(tenantUuid, "1")
            .putMembershipAuthorityGeneration(tenantUuid, membershipAuthorityGeneration)
            .build();
    RuntimeMembershipBaseline baseline =
        RuntimeMembershipBaseline.newBuilder()
            .setMembershipLifecycleState(lifecycle)
            .putMembershipVersion(tenantUuid, membershipVersion)
            .setMembershipAuthorityGeneration(membershipAuthorityGeneration)
            .build();
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(tenantUuid)
            .setRequestAccountId(accountId)
            .setRequestTenantId(requestTenantId)
            .setAuthorityAvailability("AVAILABLE")
            .setMembershipExists(exists)
            .setMembershipLifecycleState(lifecycle)
            .setGameplayAdmissionAllowed(admitted)
            .putMembershipVersion(tenantUuid, membershipVersion)
            .setMembershipAuthorityGeneration(membershipAuthorityGeneration)
            .setMembershipBaseline(baseline)
            .setAuthorityTuple(tuple)
            .setIssuanceFence(issuanceFence)
            .addAllOutboxCheckpoints(checkpoints)
            .setEvaluatedAt(Instant.now().toString());
    response.addAllRoles(roles);
    if (exists) {
      MembershipAuthorityEventV1Codec.MembershipEvent event =
          MembershipAuthorityEventV1Codec.seal(
              Map.ofEntries(
                  Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                  Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                  Map.entry("eventId", EVENT_ID),
                  Map.entry("requestId", "runtime-membership-request"),
                  Map.entry("outboxStreamKey", membershipStream),
                  Map.entry("outboxSequence", outboxSequence),
                  Map.entry("sourceScope", "membership/" + accountUuid + "/" + tenantUuid),
                  Map.entry("accountId", accountUuid),
                  Map.entry("tenantId", tenantUuid),
                  Map.entry("membershipExists", true),
                  Map.entry("membershipLifecycleState", lifecycle),
                  Map.entry("membershipVersion", Map.of(tenantUuid, membershipVersion)),
                  Map.entry("membershipAuthorityGeneration", membershipAuthorityGeneration),
                  Map.entry(
                      "authorityTuple",
                      Map.of(
                          "issuerAuthGeneration",
                          "1",
                          "accountAuthorityGeneration",
                          "1",
                          "tenantAuthorityGeneration",
                          Map.of(tenantUuid, "1"),
                          "membershipAuthorityGeneration",
                          Map.of(tenantUuid, membershipAuthorityGeneration),
                          "privateRealmGrantVersions",
                          List.of())),
                  Map.entry("issuanceFence", issuanceFence),
                  Map.entry("roles", roles),
                  Map.entry("gameplayAdmissionAllowed", admitted),
                  Map.entry("callerBoundAuthorityInvalidated", callerBoundAuthorityInvalidated)));
      response.addOutboxSourceEvidence(
          RuntimeOutboxSourceEvidence.newBuilder()
              .setOutboxStreamKey(membershipStream)
              .setOutboxSequence(outboxSequence)
              .setEventId(event.eventId())
              .setEventDigest(event.eventDigest())
              .setCanonicalEventJson(event.canonicalJson()));
    }
    return response.build();
  }

  private static void requireCanonicalTenantId(String tenantId) {
    if (tenantId == null
        || tenantId.isBlank()
        || !UUID.fromString(tenantId).toString().equals(tenantId)
        || new UUID(0L, 0L).toString().equals(tenantId)) {
      throw new IllegalArgumentException("test canonical tenant ID must be a non-nil UUID");
    }
  }

  private static void requirePositiveRetainedTenantKey(long retainedTenantKey) {
    if (retainedTenantKey <= 0L) {
      throw new IllegalArgumentException("test retained tenant key must be positive");
    }
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
