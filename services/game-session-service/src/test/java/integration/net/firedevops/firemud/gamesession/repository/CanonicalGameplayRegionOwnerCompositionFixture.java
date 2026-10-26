package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.jooq.DSLContext;

/**
 * Test-only wrapper for the package-private Game Session region owners. It starts from an actual
 * current World/Entity source and the actual SQL transition repository; only the upstream Account
 * lease carrier, issuer reservation, and stable Redis incarnation are controlled test evidence.
 */
public final class CanonicalGameplayRegionOwnerCompositionFixture {
  private static final String REDIS_PRINCIPAL = "gamesession_region_owner_app";
  private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
  private static final String REGION_ATTESTATION = "controlled-region-owner-composition";

  private CanonicalGameplayRegionOwnerCompositionFixture() {}

  /**
   * Creates one real first-NEW provisional candidate from the exact placement selector, installs
   * the first region timeline and SQL fence, then retries the same binding bridge as a lost reply.
   */
  public static Proof installAndBridge(
      DSLContext dsl,
      String redisHost,
      int redisPort,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalPlayableTarget currentTarget,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient)
      throws Exception {
    UUID transitionId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    var resolved = worldRegionResolver.resolve(worldRequest);
    requireExactCurrentTarget(worldRequest, currentTarget);
    var identity =
        new CanonicalGameplayBindingIdentity(
            UUID.fromString(worldRequest.getCanonicalAccountUuid()),
            currentTarget.canonicalTenantId(),
            currentTarget.playableStateNamespaceId(),
            UUID.fromString(worldRequest.getCharacterUuid()),
            currentTarget.playableStateScope(),
            currentTarget.canonicalGameInstanceId(),
            currentTarget.gameInstanceId(),
            sessionId.toString(),
            resolved.operationalRegionId(),
            BigInteger.ONE,
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.valueOf(64));

    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    establishActualLegacyDisposition(dsl);
    var transitionRequest =
        new CanonicalGameplayBindingTransitionRequest(
            transitionId,
            UUID.randomUUID(),
            identity,
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.ONE),
            null,
            null);
    var prepared = inventory.prepare(transitionRequest);
    AccountGameplayAdmissionLeaseEvidence lease =
        controlledNewBindingLease(identity, currentTarget, prepared.bindingGeneration());
    var decision =
        new CanonicalGameplayAdmissionDecision(
            transitionId, lease, identity, prepared.bindingGeneration(), null, currentTarget);
    inventory.markProvisional(inventory.readProvisionalCasEvidence(transitionId), decision);

    try (var redis = RegionRedis.open(redisHost, redisPort)) {
      var scriptCatalog =
          RedisScriptCatalog.fromContributions(
              List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
      var stableSource = new ControlledStableIncarnationSource();
      var runtimeIdentity =
          new RuntimeIdentity(
              "game-session-service",
              "region-owner-composition",
              "localhost",
              Instant.now(),
              "test",
              "test",
              "controlled-test-image");
      var bridgeOwner =
          CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
              dsl,
              redis.ownerClient(),
              scriptCatalog,
              stableSource,
              worldRegionResolver,
              entityReferenceClient,
              runtimeIdentity,
              Duration.ofSeconds(30),
              Clock.systemUTC());
      var installation = bridgeOwner.installForTransition(transitionId, worldRequest);
      var firstAcknowledgement = bridgeOwner.bridge(installation);
      var lostReplyRetry = bridgeOwner.bridge(installation);
      var acknowledged = firstAcknowledgement.obligation();
      var replayed = lostReplyRetry.obligation();
      if (!acknowledged.equals(replayed)
          || acknowledged.status()
              != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED) {
        throw new IllegalStateException("Exact first-NEW region bridge retry changed its SQL ACK");
      }
      var status =
          new CanonicalGameplayRegionRuntimeStatusRepository(dsl)
              .find(installation.source)
              .orElseThrow();
      if (status.paused()
          || status.regionEpoch() != 1L
          || status.lastCommittedTickId() != -1L
          || !"game-session-service".equals(status.ownerService())) {
        throw new IllegalStateException("Initial region SQL fence is not the expected live owner");
      }
      byte[] currentTickState =
          redis
              .admin()
              .hget(
                  ascii(
                      CanonicalGameplayRegionRedisKeys.meta(
                          identity.tenantId(),
                          identity.gameInstanceId(),
                          resolved.canonicalRegionInstanceId())),
                  ascii("current_tick_state"));
      if (!Arrays.equals(currentTickState, ascii("APPLIED"))) {
        throw new IllegalStateException("Canonical Redis initial timeline metadata is absent");
      }
      return new Proof(
          transitionId,
          sessionId,
          identity.regionId(),
          resolved.canonicalRegionInstanceId(),
          status.lifecycleEpoch(),
          status.lifecycleRowVersion(),
          status.regionEpoch(),
          status.executorFence(),
          acknowledged.bridgeOperationId(),
          acknowledged.runtimeEntityId(),
          acknowledged.entityProjectionGeneration(),
          acknowledged.entityProjectionFence(),
          acknowledged.entityReferenceDigest(),
          acknowledged.worldSourceDigest(),
          acknowledged.redisReadbackDigest(),
          acknowledged.inventoryRevision(),
          acknowledged.acknowledgedAt(),
          acknowledged.equals(replayed));
    }
  }

  private static void requireExactCurrentTarget(
      CanonicalInitialPlayerLocationRequest worldRequest, CanonicalPlayableTarget target) {
    var expected = worldRequest.getExpectedTarget();
    if (!target.targetNamespace().equals("firemud")
        || !target.canonicalTenantId().toString().equals(expected.getCanonicalTenantUuid())
        || !target.realmId().toString().equals(expected.getRealmUuid())
        || !target.worldSlug().equals(expected.getWorldSlug())
        || !target.realmSlug().equals(expected.getRealmSlug())
        || !target.canonicalGameInstanceId().toString().equals(expected.getGameInstanceId())
        || target.catalogRevision() != expected.getCatalogRevision()
        || !target.canonicalVersionId().toString().equals(expected.getCanonicalVersionUuid())
        || !target
            .playableStateNamespaceId()
            .toString()
            .equals(expected.getPlayableStateNamespaceUuid())
        || !target
            .playableStateScope()
            .equals(
                expected.getPlayableStateScope()
                        == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                            .PLAYABLE_STATE_SCOPE_SHARED
                    ? "SHARED"
                    : expected.getPlayableStateScope()
                            == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED
                        ? "ISOLATED"
                        : "UNSPECIFIED")) {
      throw new IllegalArgumentException("World placement is not the exact current GS target");
    }
  }

  private static AccountGameplayAdmissionLeaseEvidence controlledNewBindingLease(
      CanonicalGameplayBindingIdentity identity,
      CanonicalPlayableTarget target,
      BigInteger bindingGeneration) {
    Instant now = Instant.now();
    long nowMillis = now.toEpochMilli();
    long nowSeconds = now.getEpochSecond();
    UUID requestId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    String account = identity.accountId().toString();
    String tenant = identity.tenantId().toString();

    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put(
        "callerWorkload",
        "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service");
    carrier.put("requestId", requestId.toString());
    carrier.put("leaseId", leaseId.toString());
    carrier.put("leaseFence", "1");
    carrier.put("leaseKind", "NEW_BINDING");

    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("realmId", target.realmId().toString());
    scope.put("worldSlug", target.worldSlug());
    scope.put("realmSlug", target.realmSlug());
    scope.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    scope.put("playableStateScope", target.playableStateScope());
    scope.put("gameInstanceId", target.canonicalGameInstanceId().toString());
    scope.put("characterId", identity.characterId().toString());
    scope.put("sessionId", identity.sessionId());
    scope.put("bindingGeneration", bindingGeneration.toString());
    scope.put("catalogRevision", Long.toString(target.catalogRevision()));
    scope.put("pointerVersion", Long.toString(target.pointerVersion()));
    scope.put("regionId", identity.regionId().toString());
    scope.put("regionEpoch", identity.regionEpoch().toString());
    carrier.put("bindingScope", scope);

    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(tenant, "1"),
            "membershipAuthorityGeneration", Map.of(tenant, "1"),
            "privateRealmGrantVersions", List.of()));
    carrier.put("issuanceFence", "1");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState", "ACTIVE",
            "membershipVersion", Map.of(tenant, "1"),
            "membershipAuthorityGeneration", "1"));

    String prefix = "account:auth-authority:v1:";
    List<Map<String, String>> checkpoints = new ArrayList<>();
    for (var coordinate :
        List.of(
            Map.entry(prefix + "account/" + account, "0"),
            Map.entry(prefix + "issuer/" + GameSessionAccountDelegationProfile.ISSUER, "0"),
            Map.entry(prefix + "membership/" + account + "/" + tenant, "1"),
            Map.entry(prefix + "tenant/" + tenant, "1"))) {
      checkpoints.add(
          Map.of("outboxStreamKey", coordinate.getKey(), "outboxSequence", coordinate.getValue()));
    }
    checkpoints.sort(Comparator.comparing(value -> value.get("outboxStreamKey")));
    carrier.put("outboxCheckpoints", checkpoints);

    carrier.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", account),
            Map.entry("operationId", UUID.randomUUID().toString()),
            Map.entry("issuanceRequestId", UUID.randomUUID().toString()),
            Map.entry("tokenJti", UUID.randomUUID().toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
            Map.entry("issuedAt", Long.toString(nowSeconds)),
            Map.entry("notBefore", Long.toString(nowSeconds)),
            Map.entry("expiresAt", Long.toString(nowSeconds + 30))));
    carrier.put("evaluatedAt", Long.toString(nowMillis));
    carrier.put("expiresAt", Long.toString(nowMillis + 10_000L));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  /** Actual migration state machine over the selected controlled empty legacy cohort. */
  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled source storage identity changed");
            }
          }
        };
    var migrated =
        new CanonicalGameplayLegacyMigrationOwner(
                migration,
                inventory,
                () -> fencedCohort,
                ignored ->
                    new CanonicalGameplayLegacyMigrationSourceSnapshot(
                        EnumSet.of(
                            Family.TENANT_SESSION_CONTEXT,
                            Family.SESSION_ALIAS_CONTEXT,
                            Family.GAMEPLAY_IDENTITY_CONTEXT,
                            Family.GAMEPLAY_NAME_CONTEXT,
                            Family.MOVEMENT_EFFECT,
                            Family.DURABLE_EFFECT),
                        List.of()),
                new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
                  @Override
                  public void rebuildExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                      CanonicalGameplayLegacyMigrationSourceSnapshot source,
                      net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventorySnapshot
                          canonical) {
                    requireEmptyCanonical(canonical);
                  }

                  @Override
                  public CanonicalGameplayLegacyMigrationReadback readBackExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                      net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventorySnapshot
                          canonical) {
                    requireEmptyCanonical(canonical);
                    return new CanonicalGameplayLegacyMigrationReadback(
                        List.of(), List.of(), List.of(), List.of());
                  }
                })
            .migrate();
    if (migrated.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw new IllegalStateException("Actual legacy migration owner did not publish VERIFIED");
    }
  }

  private static void requireEmptyCanonical(
      net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot
          canonical) {
    if (!canonical.bindings().isEmpty()
        || !canonical.reservations().isEmpty()
        || !canonical.transitions().isEmpty()
        || !canonical.accountIndexObligations().isEmpty()
        || !canonical.issuerIndexObligations().isEmpty()
        || !canonical.regionBridgeObligations().isEmpty()) {
      throw new IllegalStateException("Controlled empty-cohort collaborator found GS state");
    }
  }

  private static CanonicalGameplayRegionIncarnationSource.StableEvidence
      controlledStableIncarnation(CanonicalGameplayRegionIncarnationSource.RegionScope scope) {
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-region-deployment",
            "deployment-generation-1",
            List.of("node-a"),
            "redis-build-1",
            "script-registry-v1",
            "sha256:" + "a".repeat(64),
            "READY",
            "boot-1",
            "startup-1",
            "storage-generation-1",
            "aof-1");
    return CanonicalGameplayRegionIncarnationSource.StableEvidence.fromCurrentSource(
        scope,
        REGION_ATTESTATION,
        "b".repeat(64),
        deployment,
        "c".repeat(64),
        "d".repeat(64),
        true,
        "controlled-non-reset-profile-v1",
        "e".repeat(64),
        true,
        "f".repeat(64),
        true,
        "1".repeat(64));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  public record Proof(
      UUID transitionId,
      UUID sessionId,
      UUID operationalRegionId,
      UUID canonicalRegionId,
      long lifecycleEpoch,
      long lifecycleRowVersion,
      long regionEpoch,
      BigInteger executorFence,
      UUID bridgeOperationId,
      Long runtimeEntityId,
      Long entityProjectionGeneration,
      Long entityProjectionFence,
      String entityReferenceDigest,
      String worldSourceDigest,
      String redisReadbackDigest,
      BigInteger acknowledgedInventoryRevision,
      Instant acknowledgedAt,
      boolean exactLostReplyRetry) {}

  private static final class ControlledStableIncarnationSource
      implements CanonicalGameplayRegionIncarnationSource {
    @Override
    public StableEvidence readCurrent(RegionScope scope) {
      return controlledStableIncarnation(scope);
    }
  }

  private static final class RegionRedis implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;

    private RegionRedis(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
    }

    static RegionRedis open(String host, int port) {
      var baseUri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(5)).build();
      RedisClient administrator = RedisClient.create(baseUri);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      admin.aclSetuser(
          REDIS_PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(REDIS_PASSWORD)
              .keyPattern("tick-executor-lease:*")
              .keyPattern("tick:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.HSET)
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRE));
      RedisURI ownerUri =
          RedisURI.Builder.redis(host, port)
              .withTimeout(Duration.ofSeconds(5))
              .withAuthentication(REDIS_PRINCIPAL, REDIS_PASSWORD)
              .build();
      return new RegionRedis(administrator, RedisClient.create(ownerUri), adminConnection, admin);
    }

    RedisClient ownerClient() {
      return ownerClient;
    }

    RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
