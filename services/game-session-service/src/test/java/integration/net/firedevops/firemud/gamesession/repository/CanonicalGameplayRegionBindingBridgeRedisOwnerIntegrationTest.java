package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionBindingBridgeRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Controlled Redis and test-PKI proof; the source peer is not a production Entity placement claim.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
@SuppressWarnings("resource")
class CanonicalGameplayRegionBindingBridgeRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String NAMESPACE = "game-session-test";
  private static final String CLIENT_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String ENTITY_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service";
  private static final int PORT = 6379;
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_REGION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REGION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID NAMESPACE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CAPTURE_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID SESSION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String GRAPH_DIGEST = "a".repeat(64);
  private static final String PREPARATION_DIGEST = "sha256:" + "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Region binding bridge test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", CLIENT_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec("entity-server", ENTITY_PEER, true));
  }

  @Test
  void sameGrantAppliesAndReadsBackExactTypedEntityBindingThenPreservesForeignState()
      throws Exception {
    try (RedisFixture redis = RedisFixture.open()) {
      var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      var source = source();
      RuntimeIdentity runtimeIdentity = runtimeIdentity("instance-a");
      seedMeta(redis.admin(), source, "1");
      var grant = owner.acquire(source, 1L, Duration.ofSeconds(30), runtimeIdentity);
      var status = status(source, grant, runtimeIdentity);
      var actor = readActualEntityReference();
      String key =
          CanonicalGameplayRegionRedisKeys.sessionBinding(
              TENANT_ID, GAME_INSTANCE_ID, REGION_ID, actor.runtimeEntityId().value());
      String meta = CanonicalGameplayRegionRedisKeys.meta(TENANT_ID, GAME_INSTANCE_ID, REGION_ID);
      var absent =
          owner.probeSessionBinding(
              grant,
              status,
              actor.runtimeEntityId(),
              SESSION_ID.toString(),
              java.math.BigInteger.ONE);
      assertThat(absent.state())
          .isEqualTo(CanonicalGameplayRegionLeaseRedisOwner.PreimageState.ABSENT);
      owner.applySessionBinding(
          grant, status, actor.runtimeEntityId(), SESSION_ID.toString(), java.math.BigInteger.ONE);
      var exact =
          owner.probeSessionBinding(
              grant,
              status,
              actor.runtimeEntityId(),
              SESSION_ID.toString(),
              java.math.BigInteger.ONE);
      assertThat(exact.state())
          .isEqualTo(CanonicalGameplayRegionLeaseRedisOwner.PreimageState.EXACT);
      var readback =
          owner.readBackSessionBinding(
              grant,
              status,
              actor.runtimeEntityId(),
              SESSION_ID.toString(),
              java.math.BigInteger.ONE);
      assertThat(readback.runtimeEntityId()).isEqualTo(actor.runtimeEntityId());
      assertThat(readback.sessionId()).isEqualTo(SESSION_ID.toString());
      assertThat(readback.bindingGeneration()).isEqualTo(java.math.BigInteger.ONE);
      assertThat(redis.admin().hget(ascii(key), ascii("session_id")))
          .containsExactly(ascii(SESSION_ID.toString()));
      assertThat(redis.admin().hget(ascii(key), ascii("binding_generation")))
          .containsExactly(ascii("1"));

      byte[] foreignSession = ascii("88888888-8888-4888-8888-888888888888");
      redis
          .admin()
          .hset(
              ascii(key),
              java.util.Map.of(
                  ascii("session_id"), foreignSession, ascii("binding_generation"), ascii("2")));
      assertThatThrownBy(
              () ->
                  owner.probeSessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  owner.applySessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().hget(ascii(key), ascii("session_id")))
          .containsExactly(foreignSession);
      assertThat(redis.admin().hget(ascii(key), ascii("binding_generation")))
          .containsExactly(ascii("2"));
    }
  }

  @Test
  void wrongOrMissingMetaAndExpiredLeaseFailWithoutCreatingBinding() throws Exception {
    try (RedisFixture redis = RedisFixture.open()) {
      var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      var source = source();
      RuntimeIdentity runtimeIdentity = runtimeIdentity("instance-a");
      seedMeta(redis.admin(), source, "1");
      var grant = owner.acquire(source, 1L, Duration.ofSeconds(30), runtimeIdentity);
      var status = status(source, grant, runtimeIdentity);
      var actor = readActualEntityReference();
      String key =
          CanonicalGameplayRegionRedisKeys.sessionBinding(
              TENANT_ID, GAME_INSTANCE_ID, REGION_ID, actor.runtimeEntityId().value());
      String meta = CanonicalGameplayRegionRedisKeys.meta(TENANT_ID, GAME_INSTANCE_ID, REGION_ID);
      redis.admin().del(ascii(meta));

      assertThatThrownBy(
              () ->
                  owner.probeSessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(ascii(key))).isEqualTo("none");

      redis.admin().hset(ascii(meta), ascii("region_epoch"), ascii("2"));
      assertThatThrownBy(
              () ->
                  owner.probeSessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(ascii(key))).isEqualTo("none");

      redis.admin().hset(ascii(meta), ascii("region_epoch"), ascii("1"));
      redis.admin().pexpire(ascii(grant.key()), 0L);
      assertThatThrownBy(
              () ->
                  owner.probeSessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().type(ascii(key))).isEqualTo("none");
    }
  }

  @Test
  void bridgesTickZeroButRejectsExpiringMetaBeforeReadback() throws Exception {
    try (RedisFixture redis = RedisFixture.open()) {
      var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      var source = source();
      RuntimeIdentity identity = runtimeIdentity("tick-zero");
      seedMeta(redis.admin(), source, "1");
      String meta = CanonicalGameplayRegionRedisKeys.meta(TENANT_ID, GAME_INSTANCE_ID, REGION_ID);
      redis
          .admin()
          .hset(
              ascii(meta),
              java.util.Map.of(
                  ascii("current_tick_id"), ascii("0"),
                  ascii("current_tick_state"), ascii("STAGED")));
      var grant = owner.acquire(source, 1L, Duration.ofSeconds(30), identity);
      var status = status(source, grant, identity);
      var actor = readActualEntityReference();

      owner.applySessionBinding(
          grant, status, actor.runtimeEntityId(), SESSION_ID.toString(), java.math.BigInteger.ONE);
      assertThat(
              redis
                  .admin()
                  .hget(
                      ascii(
                          CanonicalGameplayRegionRedisKeys.sessionBinding(
                              TENANT_ID,
                              GAME_INSTANCE_ID,
                              REGION_ID,
                              actor.runtimeEntityId().value())),
                      ascii("session_id")))
          .containsExactly(ascii(SESSION_ID.toString()));

      assertThat(redis.admin().pexpire(ascii(meta), 60_000L)).isTrue();
      assertThatThrownBy(
              () ->
                  owner.readBackSessionBinding(
                      grant,
                      status,
                      actor.runtimeEntityId(),
                      SESSION_ID.toString(),
                      java.math.BigInteger.ONE))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference
      readActualEntityReference() throws Exception {
    var request = entityRequest();
    var service =
        new CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceImplBase() {
          @Override
          public void readPreseededActorRuntimeReference(
              ReadPreseededActorRuntimeReferenceRequest request,
              StreamObserver<ReadPreseededActorRuntimeReferenceResponse> observer) {
            var peer = GrpcPeerIdentity.current();
            if (peer == null || !CLIENT_PEER.equals(peer.uri())) {
              observer.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            observer.onNext(entityResponse(request));
            observer.onCompleted();
          }
        };
    Server server = server(service);
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + server.getPort());
    try (var client =
        new CanonicalPreseededActorRuntimeReferenceClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("client")),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      client.init();
      return client.read(request);
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Server server(
      CanonicalPreseededActorAssignmentServiceGrpc.CanonicalPreseededActorAssignmentServiceImplBase
          service)
      throws Exception {
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(pki.identity("entity-server")),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static ReadPreseededActorRuntimeReferenceRequest entityRequest() {
    return ReadPreseededActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid("99999999-9999-4999-8999-999999999999")
        .setCanonicalAccountUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid(TENANT_ID.toString())
                .setRealmUuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId(GAME_INSTANCE_ID.toString())
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
                .setFrozenPolicyDigest("d".repeat(64))
                .setPlayableStateNamespaceUuid(NAMESPACE_ID.toString())
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .setCharacterUuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        .setIntentDigest("e".repeat(64))
        .build();
  }

  private static ReadPreseededActorRuntimeReferenceResponse entityResponse(
      ReadPreseededActorRuntimeReferenceRequest request) {
    return ReadPreseededActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getIntentDigest())
        .setExpectedTarget(request.getExpectedTarget())
        .setPublishedOwnerProofDigest("f".repeat(64))
        .setRuntimeEntityId(7301L)
        .setProjectionGeneration(1)
        .setProjectionFence(1)
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Pilot One"))
        .build();
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source() {
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.fromString("10101010-1010-4010-8010-101010101010"),
            NAMESPACE,
            TENANT_ID,
            "test-world",
            GAME_INSTANCE_ID,
            NAMESPACE_ID,
            "SHARED",
            true,
            "bridge-test-request",
            UUID.fromString("20202020-2020-4020-8020-202020202020"),
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        TENANT_ID,
        GAME_INSTANCE_ID,
        CANONICAL_REGION_ID,
        REGION_ID,
        4L,
        9L,
        lifecycleRequest,
        CAPTURE_ID,
        GRAPH_DIGEST,
        PREPARATION_DIGEST);
  }

  private static CanonicalGameplayRegionRuntimeStatus status(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant,
      RuntimeIdentity identity) {
    return new CanonicalGameplayRegionRuntimeStatus(
        source.tenantId(),
        source.gameInstanceId(),
        source.canonicalRegionId(),
        source.regionId(),
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            source.tenantId(), source.gameInstanceId(), source.regionId()),
        source.lifecycleEpoch(),
        source.lifecycleRowVersion(),
        source.lifecycleRequest(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        1L,
        java.math.BigInteger.ONE,
        grant.tokenCorrelation(),
        identity.service(),
        identity.serviceInstanceId(),
        false,
        null,
        -1L,
        NOW);
  }

  private static RuntimeIdentity runtimeIdentity(String instanceId) {
    return new RuntimeIdentity(
        "game-session-service", instanceId, "localhost", NOW, "test", "test-sha", "test-image");
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static void seedMeta(
      RedisCommands<byte[], byte[]> admin,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      String epoch) {
    admin.hset(
        ascii(
            CanonicalGameplayRegionRedisKeys.meta(
                source.tenantId(), source.gameInstanceId(), source.regionId())),
        java.util.Map.of(
            ascii("region_epoch"), ascii(epoch),
            ascii("current_tick_id"), ascii("-1"),
            ascii("current_tick_state"), ascii("APPLIED")));
  }

  private static final class RedisFixture implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;
    private final RedisScriptCatalog catalog;

    private RedisFixture(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin,
        RedisScriptCatalog catalog) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
      this.catalog = catalog;
    }

    static RedisFixture open() {
      RedisClient administrator = createClient(null, null);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      admin.flushdb();
      admin.aclSetuser(
          PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(PASSWORD)
              .keyPattern("tick-executor-lease:*")
              .keyPattern("tick:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRE)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.HSET));
      RedisClient ownerClient = createClient(PRINCIPAL, PASSWORD);
      RedisScriptCatalog catalog =
          RedisScriptCatalog.fromContributions(
              List.of(
                  new CanonicalGameplayRegionLeaseRedisScriptContribution(),
                  new CanonicalGameplayRegionBindingBridgeRedisScriptContribution()));
      return new RedisFixture(administrator, ownerClient, adminConnection, admin, catalog);
    }

    private static RedisClient createClient(String username, String password) {
      RedisURI.Builder builder =
          RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
              .withTimeout(Duration.ofSeconds(3));
      if (username != null) builder.withAuthentication(username, password);
      RedisClient client = RedisClient.create(builder.build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      return client;
    }

    RedisClient ownerClient() {
      return ownerClient;
    }

    RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    RedisScriptCatalog catalog() {
      return catalog;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
