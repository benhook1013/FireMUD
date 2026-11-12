package net.firedevops.firemud.common.account.capacity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;

/**
 * Controlled content only. Nothing here authenticates a source, initializes usage or admits play.
 */
public final class CapacityAdmissionTestFixture {
  public static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  public static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  public static final UUID REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");
  public static final UUID ADMISSION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  public static final UUID ATTEMPT = UUID.fromString("55555555-5555-4555-8555-555555555555");
  public static final UUID PLAYABLE_STATE_NAMESPACE =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  public static final UUID GAME_INSTANCE = UUID.fromString("77777777-7777-4777-8777-777777777777");
  public static final UUID CHARACTER = UUID.fromString("88888888-8888-4888-8888-888888888888");
  public static final UUID SESSION = UUID.fromString("99999999-9999-4999-8999-999999999999");
  public static final UUID REALM = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  public static final String PEER = "spiffe://firemud/ns/prod/sa/game-session-service";
  public static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final String HASH = "a".repeat(64);

  private CapacityAdmissionTestFixture() {}

  public static RuntimePlayerAdmissionEntitlementEvidence playerEvidence() {
    return capture(target(), facts(), sources(), NOW);
  }

  public static TenantCapacityAdmission.Request playerRequest() {
    return playerRequest(true);
  }

  /** Controlled policy content, never proof of current Account entitlement custody. */
  public static TenantCapacityAdmission.Request playerRequest(boolean permitsCreation) {
    Map<String, Object> policyFacts = facts();
    policyFacts.put("allowNewGameplayBindings", permitsCreation);
    var evidence = capture(target(), policyFacts, sources(), NOW);
    return new TenantCapacityAdmission.Request(
            new TenantCapacityAdmission.Identity(TENANT, REQUEST, ADMISSION),
            ATTEMPT,
            1,
            "prod",
            new TenantCapacityAdmission.PlayerContext(
                ACCOUNT,
                TENANT,
                PLAYABLE_STATE_NAMESPACE,
                GAME_INSTANCE,
                CHARACTER,
                SESSION,
                REALM,
                REQUEST,
                "SHARED"),
            TenantCapacityAdmission.Operation.CAPACITY_CREATING,
            new TenantCapacityAdmission.Shape(0, 0, 0),
            new TenantCapacityAdmission.Shape(1, 0, 0),
            TenantCapacityAdmission.Evidence.player(evidence),
            1L,
            null)
        .withDigest();
  }

  /** Controlled complete content for a native owner component fixture, not Account authority. */
  public static TenantCapacityAdmission.Request playerRequestFor(
      TenantCapacityAdmission.PlayerContext player,
      UUID admission,
      UUID attempt,
      String namespace,
      Map<String, Object> target,
      Instant at) {
    Map<String, Object> source = new LinkedHashMap<>(sources());
    source.put("callerTokenExpiresAt", at.plusSeconds(90).toString());
    var evidence =
        RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
            player.requestId(),
            TenantCapacityAdmission.expectedWorkload(
                namespace, TenantCapacityAdmission.Owner.GAME_SESSION),
            "controlled-scope",
            membership(player.accountId(), player.tenantId(), player.requestId()),
            target,
            facts(),
            source,
            at);
    return new TenantCapacityAdmission.Request(
            new TenantCapacityAdmission.Identity(player.tenantId(), player.requestId(), admission),
            attempt,
            1,
            namespace,
            player,
            TenantCapacityAdmission.Operation.CAPACITY_CREATING,
            new TenantCapacityAdmission.Shape(0, 0, 0),
            new TenantCapacityAdmission.Shape(1, 0, 0),
            TenantCapacityAdmission.Evidence.player(evidence),
            1L,
            null)
        .withDigest();
  }

  public static TenantCapacityAdmission.Request worldRequest(Long delta) {
    String tenant = TENANT.toString();
    String stream = "account:auth-authority:v1:tenant/" + tenant;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-auth-evidence-bundle/v1");
    fields.put("variant", "TENANT_OPERATION");
    fields.put("bundleVersion", "1");
    fields.put("authorityAvailability", "AVAILABLE");
    fields.put("tenantId", tenant);
    fields.put("requestId", REQUEST.toString());
    fields.put("callerWorkload", "spiffe://firemud/ns/prod/sa/world-management-service");
    fields.put("sourceKind", "PAID_HOSTING");
    fields.put("subscriptionStatus", "active");
    fields.put("provisioningStatus", null);
    fields.put("gameplayAvailable", true);
    fields.put("allowPublicJoin", true);
    fields.put("allowNewGameplayBindings", true);
    fields.put("allowNewInstanceStarts", true);
    fields.put(
        "quotas",
        Map.of(
            "maxActiveSessions",
            "100",
            "maxConcurrentGameInstances",
            Long.toString(Long.MAX_VALUE),
            "maxStorageBytes",
            "1048576"));
    fields.put("entitlementVersion", "7");
    fields.put("tenantBillingSequence", "11");
    fields.put("tenantAuthorityGeneration", "5");
    fields.put("evaluatedAt", NOW.toString());
    fields.put("snapshotIdentity", "sha256:" + "a".repeat(64));
    fields.put("evaluationIdentity", "sha256:" + "b".repeat(64));
    fields.put(
        "authorityTuple",
        Map.of(
            "tenantAuthorityGeneration",
            Map.of(tenant, "5"),
            "tenantBillingCutoff",
            Map.of(
                tenant,
                Map.of(
                    "tenantAuthorityGeneration",
                    "5",
                    "tenantBillingSequence",
                    "11",
                    "outboxStreamKey",
                    stream,
                    "outboxSequence",
                    "3"))));
    fields.put(
        "outboxCheckpoints",
        List.of(
            Map.of(
                "outboxStreamKey",
                stream,
                "outboxSequence",
                "3",
                "sourceEventId",
                ACCOUNT.toString(),
                "sourceEventDigest",
                "sha256:" + "a".repeat(64))));
    return new TenantCapacityAdmission.Request(
            new TenantCapacityAdmission.Identity(TENANT, REQUEST, ADMISSION),
            ATTEMPT,
            1,
            "prod",
            new TenantCapacityAdmission.WorldContext(
                TENANT, REQUEST, ADMISSION, ACCOUNT, ATTEMPT, 7),
            TenantCapacityAdmission.Operation.CAPACITY_CREATING,
            new TenantCapacityAdmission.Shape(0, 0, 0),
            new TenantCapacityAdmission.Shape(0, Long.MAX_VALUE, 0),
            TenantCapacityAdmission.Evidence.world(
                RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields)),
            delta,
            null)
        .withDigest();
  }

  private static RuntimePlayerAdmissionEntitlementEvidence capture(
      Map<String, Object> target,
      Map<String, Object> facts,
      Map<String, Object> source,
      Instant at) {
    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        REQUEST, PEER, "controlled-scope", membership(), target, facts, source, at);
  }

  private static Map<String, Object> target() {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetClass", "PUBLIC_PRODUCTION");
    target.put("characterCreationPolicy", "PRESEEDED_ONLY");
    target.put("targetNamespace", "prod");
    target.put("tenantId", TENANT.toString());
    target.put("tenantSlug", "tenant");
    target.put("worldSlug", "world");
    target.put("realmId", REALM.toString());
    target.put("realmSlug", "production");
    target.put("playableStateNamespaceId", PLAYABLE_STATE_NAMESPACE.toString());
    target.put("playableStateScope", "SHARED");
    target.put("gameInstanceId", GAME_INSTANCE.toString());
    target.put("canonicalVersionId", REQUEST.toString());
    target.put("catalogRevision", "7");
    target.put("pointerVersion", "3");
    target.put("activeWorldEpoch", "5");
    target.put("evaluatedAt", NOW.minusSeconds(20).toString());
    target.put("connectScopeExpiresAt", NOW.plusSeconds(120).toString());
    return target;
  }

  private static Map<String, Object> facts() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("sourceKind", "NON_PAID_DEMO");
    facts.put("subscriptionStatus", null);
    facts.put("provisioningStatus", null);
    for (String flag :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts")) facts.put(flag, true);
    facts.put(
        "quotas",
        Map.of(
            "maxActiveSessions",
            "3",
            "maxConcurrentGameInstances",
            "2",
            "maxStorageBytes",
            "4096"));
    facts.put("entitlementVersion", "1");
    facts.put("tenantBillingSequence", "1");
    facts.put("tenantAuthorityGeneration", "2");
    return facts;
  }

  private static Map<String, Object> sources() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("callerTokenExpiresAt", NOW.plusSeconds(90).toString());
    source.put("paidReceiptValidUntil", null);
    for (String key :
        List.of("callerTokenJti", "callerContextId", "callerOperationId", "worldCaptureId"))
      source.put(key, REQUEST.toString());
    for (String key :
        List.of(
            "callerTokenSha256",
            "callerRegistryRecordSha256",
            "callerCommitProofSha256",
            "admissionPointerSnapshotDigest")) source.put(key, HASH);
    for (String key :
        List.of(
            "scopeDigest",
            "entitlementSourceDigest",
            "gameSessionOwnerProofDigest",
            "publishedPolicySetDigest",
            "worldLifecycleDigest")) source.put(key, "sha256:" + HASH);
    source.put("worldRowVersion", "0");
    return source;
  }

  private static Snapshot membership() {
    return membership(ACCOUNT, TENANT, REQUEST);
  }

  private static Snapshot membership(UUID account, UUID tenantId, UUID requestId) {
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", requestId, "world", "World", null);
    var source =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            requestId,
            requestId,
            requestDigest,
            tenantId,
            17,
            "world",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod",
                requestId,
                requestId,
                requestDigest,
                tenantId,
                17,
                "world",
                "NEW_GAME_ROW"));
    var request =
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            requestId,
            "sha256:" + HASH,
            tenantId,
            source.creationRequestId(),
            source.requestDigest());
    var billing =
        new TenantAuthorityEventV1Codec.BillingReceipt(
            tenantId,
            requestId,
            "sha256:" + HASH,
            TenantAuthorityEventV1Codec.tenantBillingStreamKey(tenantId),
            1,
            TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
            "sha256:" + HASH);
    var event = TenantAuthorityEventV1Codec.seal(request, source, 2, 2, 1, billing);
    String prefix = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
    var tuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenantId.toString(), "2"),
            Map.of(tenantId.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.of(
                Map.of(
                    tenantId.toString(),
                    new TenantBillingCutoff("2", "1", event.outboxStreamKey(), "1"))));
    return new Snapshot(
        "firemud-account-service",
        account.toString(),
        tenantId.toString(),
        false,
        "MISSING",
        false,
        Map.of(tenantId.toString(), "1"),
        "1",
        List.of(),
        tuple,
        "1",
        List.of(
            new Checkpoint(prefix + "account/" + account, "0"),
            new Checkpoint(prefix + "issuer/firemud-account-service", "0"),
            new Checkpoint(prefix + "membership/" + account + "/" + tenantId, "0"),
            new Checkpoint(event.outboxStreamKey(), "1")),
        List.of(
            new SourceEvidence(
                event.outboxStreamKey(),
                "1",
                event.eventId().toString(),
                event.eventDigest(),
                new String(event.payload(), StandardCharsets.UTF_8))));
  }
}
