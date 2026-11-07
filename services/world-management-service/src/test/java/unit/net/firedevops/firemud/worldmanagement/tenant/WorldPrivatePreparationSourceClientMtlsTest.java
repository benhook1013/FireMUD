package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationBuildGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Preparation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Selection;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Source;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Target;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec.Request;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.v1.PrivateWorldPreparationReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Controlled complete GS source replies and test PKI; no installed authority is stipulated. */
@Timeout(90)
class WorldPrivatePreparationSourceClientMtlsTest {
  static final String NS = "private-world-test";
  static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  @TempDir Path directory;

  @Test
  void selectedBuildUsesExactPeerAndCannotInventMissingWorldGraph() throws Exception {
    var world = net.firedevops.firemud.common.testing.AuthoringFixtures.startLocationEvidence();
    var scope =
        new Scope(
            world.request().targetNamespace(),
            world.request().canonicalTenantId(),
            "world",
            "private");
    var facts = snapshot(scope, world.request().canonicalVersionId());
    try (var h =
        new Harness(
            directory,
            "gs",
            CLOCK,
            scope.targetNamespace(),
            null,
            (request, index) ->
                PrivateWorldPreparationBuildGrpcCodec.toResponse(
                    request,
                    new PrivateWorldPreparationBuildGrpcCodec.Build(
                        new PrivateWorldPreparationReadEvidence.Evidence(request, facts),
                        world)))) {
      var build =
          h.client.readBuild(
              scope, facts.preparation().requestId(), Deadline.after(5, TimeUnit.SECONDS));
      assertThat(build.evidence().selectedWorld()).isEqualTo(world);
      assertThat(build.admissionAllowed()).isFalse();
      var topology = org.mockito.Mockito.mock(WorldCanonicalFrozenTopologyRepository.class);
      org.mockito.Mockito.when(
              topology.readCommitted(WorldPrivatePreparationBuildAssembly.capture(world.request())))
          .thenReturn(java.util.Optional.empty());
      assertThatThrownBy(
              () ->
                  new WorldPrivatePreparationBuildAssembly(h.client, topology, CLOCK)
                      .assemble(scope, facts.preparation().requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("frozen graph is absent");
      org.mockito.Mockito.verify(topology)
          .readCommitted(WorldPrivatePreparationBuildAssembly.capture(world.request()));
      assertThat(h.requests).hasSize(2);
    }
  }

  @Test
  void exactPeerReturnsCompleteCorrelatedNonAdmittingFacts() throws Exception {
    var facts = snapshot();
    try (var f =
        new Harness(
            directory,
            "gs",
            CLOCK,
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      var evidence = f.client.read(facts.scope(), facts.preparation().requestId()).evidence();
      assertThat(evidence.snapshot()).isEqualTo(facts);
      assertThat(evidence.snapshot().admissionAllowed()).isFalse();
      assertThat(f.requests).hasSize(1);
      assertThat(f.peers)
          .containsExactly("spiffe://firemud/ns/" + NS + "/sa/world-management-service");
    }
  }

  @Test
  void wrongServiceAndNamespaceCannotReceiveSourceRequest() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      try (var f =
          new Harness(
              directory.resolve(identity),
              identity,
              CLOCK,
              (request, index) ->
                  PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot()))) {
        var facts = snapshot();
        assertThatThrownBy(() -> f.client.read(facts.scope(), facts.preparation().requestId()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error ->
                    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(f.requests).isEmpty();
      }
    }
  }

  @Test
  void changedEchoAndExpiredObservationAreRefused() throws Exception {
    var facts = snapshot();
    try (var f =
        new Harness(
            directory.resolve("echo"),
            "gs",
            CLOCK,
            (request, index) ->
                PrivateWorldPreparationReadGrpcCodec.toResponse(
                    new Request(
                        UUID.randomUUID(), request.scope(), request.expectedPreparationRequestId()),
                    facts))) {
      assertThatThrownBy(() -> f.client.read(facts.scope(), facts.preparation().requestId()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    try (var f =
        new Harness(
            directory.resolve("expired"),
            "gs",
            Clock.fixed(facts.preparation().forkExpiresAt(), ZoneOffset.UTC),
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      assertThatThrownBy(() -> f.client.read(facts.scope(), facts.preparation().requestId()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void wrongScopeAmbientSqlCancellationAndExpiredBudgetDoNotCallPeer() throws Exception {
    var facts = snapshot();
    try (var f =
        new Harness(
            directory,
            "gs",
            CLOCK,
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      assertThatThrownBy(
              () ->
                  f.client.read(
                      new Scope("other", facts.scope().tenantId(), "world", "private"),
                      facts.preparation().requestId()))
          .isInstanceOf(IllegalArgumentException.class);
      try {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> f.client.read(facts.scope(), facts.preparation().requestId()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      try (var cancelled = Context.current().withCancellation()) {
        cancelled.cancel(null);
        cancelled.run(
            () ->
                assertThatThrownBy(
                        () -> f.client.read(facts.scope(), facts.preparation().requestId()))
                    .isInstanceOf(StatusRuntimeException.class));
      }
      assertThatThrownBy(
              () ->
                  f.client.read(
                      facts.scope(),
                      facts.preparation().requestId(),
                      Deadline.after(-1, TimeUnit.SECONDS)))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(f.requests).isEmpty();
    }
  }

  /** Same-package fixture reuse by the input-assembly proof, always exercising a physical RPC. */
  static final class Harness implements AutoCloseable {
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> peers = new CopyOnWriteArrayList<>();
    final Server server;
    final WorldPrivatePreparationSourceClient client;

    Harness(
        Path directory,
        String identity,
        Clock clock,
        BiFunction<Request, Integer, ReadPrivateWorldPreparationResponse> response)
        throws Exception {
      this(directory, identity, clock, NS, response, null);
    }

    Harness(
        Path directory,
        String identity,
        Clock clock,
        String namespace,
        BiFunction<Request, Integer, ReadPrivateWorldPreparationResponse> response,
        BiFunction<Request, Integer, ReadPrivateWorldPreparationBuildResponse> buildResponse)
        throws Exception {
      Files.createDirectories(directory);
      var pki =
          LoopbackMtlsTestPki.create(
              directory,
              "Private World source test CA",
              new LoopbackMtlsTestPki.IdentitySpec(
                  "world",
                  "spiffe://firemud/ns/" + namespace + "/sa/world-management-service",
                  false),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "gs", "spiffe://firemud/ns/" + namespace + "/sa/game-session-service", true),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong-service",
                  "spiffe://firemud/ns/" + namespace + "/sa/account-service",
                  true),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong-namespace", "spiffe://firemud/ns/other/sa/game-session-service", true));
      var handler =
          new PrivateWorldPreparationReadServiceGrpc.PrivateWorldPreparationReadServiceImplBase() {
            @Override
            public void readPrivateWorldPreparationBuild(
                ReadPrivateWorldPreparationRequest wire,
                StreamObserver<ReadPrivateWorldPreparationBuildResponse> observer) {
              try {
                var request = PrivateWorldPreparationReadGrpcCodec.parseRequest(wire);
                requests.add(request);
                peers.add(GrpcPeerIdentity.current().uri());
                observer.onNext(buildResponse.apply(request, requests.size()));
                observer.onCompleted();
              } catch (RuntimeException invalid) {
                observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
              }
            }

            @Override
            public void readPrivateWorldPreparation(
                ReadPrivateWorldPreparationRequest wire,
                StreamObserver<ReadPrivateWorldPreparationResponse> observer) {
              try {
                var request = PrivateWorldPreparationReadGrpcCodec.parseRequest(wire);
                requests.add(request);
                peers.add(GrpcPeerIdentity.current().uri());
                observer.onNext(response.apply(request, requests.size()));
                observer.onCompleted();
              } catch (RuntimeException invalid) {
                observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
              }
            }
          };
      var leaf = pki.identity(identity);
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(leaf.privateKey(), leaf.certificate()))
                      .trustManager(pki.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor()))
              .build()
              .start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("localhost:" + server.getPort());
      client =
          new WorldPrivatePreparationSourceClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("world")),
              new GrpcChannelFactory(),
              namespace,
              clock);
      try {
        client.init();
      } catch (Exception | Error failure) {
        server.shutdownNow();
        throw failure;
      }
    }

    @Override
    public void close() throws Exception {
      try {
        client.close();
      } finally {
        server.shutdownNow();
        assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  static Snapshot snapshot() {
    return snapshot(new Scope(NS, id("tenant"), "world", "private"), id("version"));
  }

  static Snapshot snapshot(Scope scope, UUID version) {
    var policy =
        RealmEntryPolicy.parse(
            """
        {"schemaVersion":1,"worldSlug":"world","worldDisplayName":"World","realmSlug":"private",
         "realmDisplayName":"Private","visible":true,"publicProduction":false,"stateScope":"ISOLATED","entryPolicy":"PRESEEDED_ONLY"}
        """,
            JsonMapper.builder().build());
    var source =
        new Source(
            id("catalog"),
            id("catalog-request"),
            1,
            DIGEST,
            id("intake"),
            DIGEST,
            id("source"),
            DIGEST,
            version,
            "release:private",
            DIGEST,
            DIGEST,
            DIGEST,
            new RealmPolicyPublishedEvidence.Policy(
                id("policy"),
                new RealmPolicySource.Policy(id("commit"), id("revision"), "policy", policy),
                DIGEST));
    UUID prep = id("preparation"), lifecycle = id("lifecycle"), namespace = id("namespace");
    Instant expiry = NOW.plusSeconds(60);
    byte[] prepBytes =
        framed(
            "game-session-initial-fresh-playtest-request/v1",
            prep.toString(),
            scope.targetNamespace(),
            scope.tenantId().toString(),
            "world",
            "private",
            source.catalogRealmId().toString(),
            source.catalogRequestId().toString(),
            "1",
            source.intakeOperationId().toString(),
            source.canonicalVersionId().toString(),
            source.publishedReleaseBundleRef(),
            DIGEST,
            "FRESH",
            expiry.toString());
    var prepared =
        new Preparation(
            prep,
            lifecycle,
            namespace,
            1,
            "PREPARING",
            "FRESH",
            PrivateWorldPreparationReadEvidence.sha256(prepBytes),
            NOW.minusSeconds(2),
            expiry,
            ByteString.copyFrom(prepBytes));
    byte[] selectedBytes =
        framed(
            "game-session-current-private-playtest-selection/v1",
            id("selection").toString(),
            scope.targetNamespace(),
            scope.tenantId().toString(),
            "world",
            "private",
            prep.toString(),
            lifecycle.toString(),
            "ABSENT");
    var selection =
        new Selection(
            id("selection"),
            null,
            null,
            1,
            PrivateWorldPreparationReadEvidence.sha256(selectedBytes),
            NOW.minusSeconds(1),
            ByteString.copyFrom(selectedBytes));
    var target =
        new Target(
            scope,
            id("realm"),
            "Private",
            false,
            false,
            "ISOLATED",
            namespace,
            "PRESEEDED_ONLY",
            1,
            id("target-request"),
            DIGEST,
            DIGEST,
            prep,
            lifecycle,
            1,
            "PREPARING",
            source.catalogRealmId(),
            source.targetPolicy().policyId(),
            DIGEST,
            source.canonicalVersionId(),
            source.publishedReleaseBundleRef(),
            DIGEST);
    var allocation =
        PrivateWorldPreparationReadEvidence.runtimeAllocation(
            scope,
            selection,
            prepared,
            target,
            id("runtime-allocation-request"),
            id("runtime-game-instance"));
    return new Snapshot(scope, selection, prepared, source, target, allocation, NOW);
  }

  static Snapshot changedReceipt(Snapshot original) {
    var t = original.target();
    var changed =
        new Target(
            t.scope(),
            t.realmId(),
            t.realmDisplayName(),
            t.visible(),
            t.publicProduction(),
            t.stateScope(),
            t.namespaceId(),
            t.characterCreationPolicy(),
            t.catalogRevision(),
            t.creationRequestId(),
            t.requestDigest(),
            "sha256:" + "b".repeat(64),
            t.preparationRequestId(),
            t.lifecycleId(),
            t.generation(),
            t.state(),
            t.sourceCatalogRealmId(),
            t.targetPolicyId(),
            t.targetPolicyDigest(),
            t.canonicalVersionId(),
            t.publishedReleaseBundleRef(),
            t.publishedReleaseBundleDigest());
    var allocation =
        PrivateWorldPreparationReadEvidence.runtimeAllocation(
            original.scope(),
            original.selection(),
            original.preparation(),
            changed,
            original.runtimeAllocation().requestId(),
            original.runtimeAllocation().gameInstanceId());
    return new Snapshot(
        original.scope(),
        original.selection(),
        original.preparation(),
        original.source(),
        changed,
        allocation,
        NOW.plusSeconds(1));
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(("CONTROLLED:" + value).getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] framed(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }
}
