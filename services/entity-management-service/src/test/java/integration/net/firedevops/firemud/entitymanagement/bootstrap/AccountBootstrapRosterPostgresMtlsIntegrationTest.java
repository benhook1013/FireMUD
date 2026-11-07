package net.firedevops.firemud.entitymanagement.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual Entity/Saga migrations, assignment writer, read-only repository and two socket mTLS hops.
 * Upstream COMMITTED target/published-policy bytes and assignment eligibility are controlled input;
 * this does not execute Game Session/World/publication owner stores or prove production custody.
 */
@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES)
@SuppressWarnings("resource")
class AccountBootstrapRosterPostgresMtlsIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Account bootstrap roster test CA",
            new IdentitySpec(
                "entity-server", "spiffe://firemud/ns/test/sa/entity-management-service", true),
            new IdentitySpec("gs-server", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new IdentitySpec(
                "entity-client", "spiffe://firemud/ns/test/sa/entity-management-service", false),
            new IdentitySpec("account", "spiffe://firemud/ns/test/sa/account-service", false),
            new IdentitySpec(
                "foreign-account", "spiffe://firemud/ns/other/sa/account-service", false),
            new IdentitySpec(
                "gs-client", "spiffe://firemud/ns/test/sa/game-session-service", false));
  }

  @Test
  void persistedZeroOneAndAccountNamespaceIsolationHaveNoDiscoveryWrites() throws Exception {
    try (var f = fixture()) {
      var request = f.source.request();
      var before = f.rows();
      assertThat(f.read("account", request).getActorsList()).isEmpty();
      assertThat(f.rows()).isEqualTo(before); // valid empty does not enroll the namespace
      UUID account = UUID.fromString(request.getCanonicalAccountUuid());
      UUID first = f.assign(account, f.source.target(), 101L, "Persisted Ship");
      f.dsl.execute("UPDATE characters SET level=7 WHERE character_uuid=?", first);
      var one = f.read("account", request);
      assertThat(one.getRequest()).isEqualTo(request);
      assertThat(one.getActorsCount()).isEqualTo(1);
      assertThat(one.getActors(0).getCharacterUuid()).isEqualTo(first.toString());
      assertThat(one.getActors(0).getDisplayName()).isEqualTo("Persisted Ship");
      assertThat(one.getActors(0).hasLevel()).isTrue();
      assertThat(one.getActors(0).getLevel()).isEqualTo(7);
      // The current assignment writer/partial unique index permits one OWNER_RESOLVED actor per
      // account namespace. This read does not widen that writer invariant to fabricate a many
      // fixture.
      UUID otherAccount = UUID.randomUUID();
      UUID zeroLevelActor = f.assign(otherAccount, f.source.target(), 102L, "Other Account");
      var otherNamespace =
          request.getExpectedTarget().toBuilder()
              .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
              .build();
      f.assign(
          account, CanonicalGameplayRosterTarget.fromWire(otherNamespace), 101L, "Other Namespace");
      f.dsl.execute(
          "INSERT INTO characters (account_id,account_uuid,name,tenant_id,playable_state_key) VALUES (101,?,'Quarantined',1,'shared-live')",
          account);
      before = f.rows();
      var isolated = f.read("account", request);
      assertThat(isolated.getActorsList().stream().map(actor -> actor.getCharacterUuid()).toList())
          .containsExactly(first.toString());
      assertThat(isolated.getActors(0).getLevel()).isEqualTo(7);
      var storedZero =
          f.read(
              "account",
              request.toBuilder().setCanonicalAccountUuid(otherAccount.toString()).build());
      assertThat(storedZero.getActors(0).getCharacterUuid()).isEqualTo(zeroLevelActor.toString());
      assertThat(storedZero.getActors(0).hasLevel()).isTrue();
      assertThat(storedZero.getActors(0).getLevel()).isZero();
      assertThat(
              f.read(
                      "account",
                      request.toBuilder()
                          .setCanonicalAccountUuid(UUID.randomUUID().toString())
                          .build())
                  .getActorsList())
          .isEmpty();
      assertThat(f.rows()).isEqualTo(before);
      assertThat(f.ownerReads.get()).isEqualTo(10);
    }
  }

  @Test
  void workloadGuardPrecedesMalformedDecodeAndUnsupportedScopeOrNamespaceNeverQueriesSource()
      throws Exception {
    try (var f = fixture()) {
      var malformed =
          f.source.request().toBuilder()
              .setUnknownFields(
                  UnknownFieldSet.newBuilder()
                      .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                      .build())
              .build();
      f.refuses("foreign-account", malformed, Status.Code.PERMISSION_DENIED);
      f.refuses("gs-client", malformed, Status.Code.PERMISSION_DENIED);
      f.refuses("account", malformed, Status.Code.INVALID_ARGUMENT);
      f.refuses(
          "account",
          f.source.request().toBuilder().setTargetNamespace("other").build(),
          Status.Code.PERMISSION_DENIED);
      f.refuses(
          "account",
          f.source.request().toBuilder()
              .setExpectedTarget(
                  f.source.request().getExpectedTarget().toBuilder()
                      .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
              .build(),
          Status.Code.FAILED_PRECONDITION);
      f.refuses(
          "account",
          f.source.request().toBuilder()
              .setExpectedTarget(
                  f.source.request().getExpectedTarget().toBuilder()
                      .setEntryPolicy(
                          net.firedevops.firemud.entitymanagement.v1
                              .CanonicalGameplayRosterEntryPolicy
                              .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED))
              .build(),
          Status.Code.FAILED_PRECONDITION);
      assertThat(f.ownerReads.get()).isZero();
    }
  }

  @Test
  void unavailableChangedDigestAndSourceMovementAcrossSqlRefuseWithoutEmptySuccess()
      throws Exception {
    try (var f = fixture()) {
      var before = f.rows();
      f.unavailable = true;
      f.refuses("account", f.source.request(), Status.Code.UNAVAILABLE);
      f.unavailable = false;
      f.pointerDigest.set("f".repeat(64));
      f.refuses("account", f.source.request(), Status.Code.FAILED_PRECONDITION);
      f.pointerDigest.set(f.source.target().admissionPointerSnapshotDigest());
      f.changeAfterNextSourceRead = true;
      f.refuses("account", f.source.request(), Status.Code.FAILED_PRECONDITION);
      assertThat(f.rows()).isEqualTo(before);
    }
  }

  private static Fixture fixture() throws Exception {
    String schema = "entity_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    ds.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(ds)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_entity_management_service")
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", schema));
    migration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    migration.load().migrate();
    return new Fixture(
        schema,
        DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(ds));
  }

  private static final class Fixture implements AutoCloseable {
    final String schema;
    final DSLContext dsl;
    final DataSourceTransactionManager manager;
    final CharacterRepository characters;
    final AccountBootstrapRosterFixture.Fixture source;
    final AtomicInteger ownerReads = new AtomicInteger();
    final AtomicReference<String> pointerDigest;
    volatile boolean unavailable;
    volatile boolean changeAfterNextSourceRead;
    final Server sourceServer;
    final Server entityServer;
    final GameSessionCanonicalGameplayRosterOwnerReadClient client;
    final List<ManagedChannel> channels = new ArrayList<>();

    Fixture(String schema, DSLContext dsl, DataSourceTransactionManager manager) throws Exception {
      this.schema = schema;
      this.dsl = dsl;
      this.manager = manager;
      characters = new CharacterRepository(dsl);
      source = AccountBootstrapRosterFixture.create();
      pointerDigest = new AtomicReference<>(source.target().admissionPointerSnapshotDigest());
      sourceServer =
          server(
              "gs-server",
              new CanonicalGameplayRosterOwnerReadServiceGrpc
                  .CanonicalGameplayRosterOwnerReadServiceImplBase() {
                @Override
                public void getCanonicalGameplayRosterOwnerRead(
                    GetCanonicalGameplayRosterOwnerReadRequest wire,
                    StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> observer) {
                  var peer = GrpcPeerIdentity.current();
                  if (peer == null
                      || !peer.isService("entity-management-service")
                      || !peer.isInNamespace("test")) {
                    observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
                    return;
                  }
                  ownerReads.incrementAndGet();
                  if (unavailable) {
                    observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    return;
                  }
                  var request = CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(wire);
                  var base = source.evidence().request();
                  var rebound =
                      new CanonicalGameplayRosterOwnerReadEvidence.Request(
                          request.requestUuid(),
                          request.canonicalAccountUuid(),
                          base.targetNamespace(),
                          base.canonicalTenantUuid(),
                          base.worldSlug(),
                          base.realmUuid(),
                          base.realmSlug(),
                          base.playableStateNamespaceUuid(),
                          base.playableStateScope(),
                          base.canonicalGameInstanceUuid(),
                          base.canonicalVersionUuid(),
                          base.expectedCatalogRevision(),
                          base.expectedPointerVersion(),
                          base.expectedActiveWorldEpoch());
                  if (!request.equals(rebound)) {
                    observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
                    return;
                  }
                  var evidence =
                      new CanonicalGameplayRosterOwnerReadEvidence(
                          request,
                          pointerDigest.get(),
                          source.evidence().gameSessionOwnerProof(),
                          source.evidence().publishedPolicySetEvidence());
                  if (changeAfterNextSourceRead) {
                    pointerDigest.set("f".repeat(64));
                    changeAfterNextSourceRead = false;
                  }
                  observer.onNext(
                      CanonicalGameplayRosterOwnerReadGrpcCodec.toResponse(request, evidence));
                  observer.onCompleted();
                }
              },
              new GrpcPeerIdentityInterceptor());
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("127.0.0.1:" + sourceServer.getPort());
      client =
          new GameSessionCanonicalGameplayRosterOwnerReadClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("entity-client")),
              new GrpcChannelFactory(),
              "test");
      client.init();
      var service =
          new AccountBootstrapRosterReadService(
              new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, "test"),
              characters,
              manager);
      entityServer =
          server(
              "entity-server",
              new AccountBootstrapRosterReadGrpcService(service, "test"),
              new AccountBootstrapRosterPeerInterceptor("test"));
    }

    AccountBootstrapRosterReadResponse read(String alias, AccountBootstrapRosterReadRequest request)
        throws Exception {
      var identity = pki.identity(alias);
      var channel =
          NettyChannelBuilder.forAddress("127.0.0.1", entityServer.getPort())
              .sslContext(
                  GrpcSslContexts.forClient()
                      .trustManager(pki.caCertificate())
                      .keyManager(
                          identity.privateKey(), identity.certificate(), pki.caCertificate())
                      .build())
              .intercept(
                  new GrpcServerPeerIdentityClientInterceptor(
                      "spiffe://firemud/ns/test/sa/entity-management-service"))
              .maxInboundMessageSize(AccountBootstrapRosterReadContract.MAX_RESPONSE_BYTES)
              .build();
      channels.add(channel);
      return AccountBootstrapRosterReadServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .readAccountBootstrapRoster(request);
    }

    void refuses(String alias, AccountBootstrapRosterReadRequest request, Status.Code expected) {
      assertThatThrownBy(() -> read(alias, request))
          .satisfies(
              failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
    }

    UUID assign(UUID account, CanonicalGameplayRosterTarget target, long rowId, String name) {
      UUID operation = UUID.randomUUID();
      var evidence =
          new PreseededActorAssignmentOwnerEvidence(
              operation,
              account,
              PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
              Instant.parse("2026-10-04T00:00:00Z"),
              4L,
              "1".repeat(64),
              target.tenantUuid(),
              target.realmUuid(),
              target.worldSlug(),
              target.realmSlug(),
              target.gameInstanceUuid().toString(),
              target.catalogRevision(),
              target.canonicalVersionUuid(),
              target.publishedPolicyDigest(),
              target.playableStateNamespaceId(),
              target.playableStateScope(),
              PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
              PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
              PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
              "2".repeat(64),
              target.publishedOwnerProofDigest(),
              target.publishedReleaseBundleRef());
      var request =
          new PreseededActorAssignmentRequest(
              operation,
              account,
              new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, name),
              new PreseededActorAssignmentExpectedTarget(
                  target.tenantUuid(),
                  target.realmUuid(),
                  target.worldSlug(),
                  target.realmSlug(),
                  target.gameInstanceUuid().toString(),
                  target.catalogRevision(),
                  target.canonicalVersionUuid(),
                  target.publishedPolicyDigest(),
                  target.playableStateNamespaceId(),
                  target.publishedReleaseBundleRef(),
                  target.playableStateScope()));
      var result =
          new TransactionTemplate(manager)
              .execute(
                  ignored ->
                      characters.assignPreseededActor(
                          request,
                          evidence,
                          new RuntimeAccountIdentityEvidence(
                              1, "test", operation, account, rowId, "ACCOUNT_DATABASE_INSERT"),
                          request.mutationIntentDigest()));
      assertThat(result.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
      return result.characterUuid();
    }

    Map<String, List<String>> rows() {
      var rows = new LinkedHashMap<String, List<String>>();
      for (String table :
          List.of(
              "characters",
              "entity_playable_state_namespace_scopes",
              "entity_preseeded_actor_assignment_operations",
              "entity_canonical_gameplay_roster_snapshots",
              "entity_canonical_gameplay_roster_snapshot_actors",
              "entity_canonical_roster_original_selections",
              "entity_canonical_roster_admissions",
              "entity_canonical_roster_retention_references")) {
        rows.put(
            table,
            dsl.fetch(
                    "SELECT to_jsonb(t)::text||':'||xmin::text AS value FROM "
                        + table
                        + " t ORDER BY value")
                .getValues(0, String.class));
      }
      return rows;
    }

    @Override
    public void close() throws Exception {
      for (var channel : channels) {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      entityServer.shutdownNow();
      assertThat(entityServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      client.close();
      sourceServer.shutdownNow();
      assertThat(sourceServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static Server server(
      String alias, io.grpc.BindableService service, io.grpc.ServerInterceptor interceptor)
      throws Exception {
    var identity = pki.identity(alias);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .maxInboundMessageSize(AccountBootstrapRosterReadContract.MAX_REQUEST_BYTES)
        .addService(ServerInterceptors.intercept(service, interceptor))
        .build()
        .start();
  }
}
