package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Held;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.v1.AcquirePrivatePreparationProtectionRequest;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionResponse;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePreparationProtectionRequest;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Socket peer proof with controlled retained hold responses, not physical GS storage qualification.
 */
@Timeout(90)
class WorldPrivatePreparationProtectionClientMtlsTest {
  private static final String NS = WorldPrivatePreparationSourceClientMtlsTest.NS;
  @TempDir Path directory;

  @Test
  void exactPeerRetainsHoldAndSourceAcrossIndependentCurrentRead() throws Exception {
    try (var h = new Harness(directory, "gs", WorldPrivatePreparationSourceClientMtlsTest.CLOCK)) {
      var held = h.client.acquire(UUID.randomUUID(), source());
      assertThat(held.currentRead()).isFalse();
      assertThat(held.admissionAllowed()).isFalse();
      var current = h.client.readCurrent(held);
      assertThat(current.evidence()).isEqualTo(held.evidence());
      assertThat(current.currentRead()).isTrue();
      assertThat(current.admissionAllowed()).isFalse();
      assertThat(h.calls.get()).isEqualTo(2);
      assertThat(h.peer.get())
          .isEqualTo("spiffe://firemud/ns/" + NS + "/sa/world-management-service");
    }
  }

  @Test
  void wrongServerIdentityNeverReceivesProtectionRequest() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      try (var h =
          new Harness(
              directory.resolve(identity),
              identity,
              WorldPrivatePreparationSourceClientMtlsTest.CLOCK)) {
        assertThatThrownBy(() -> h.client.acquire(UUID.randomUUID(), source()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error ->
                    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(h.calls.get()).isZero();
      }
    }
  }

  @Test
  void historicalAcquisitionCannotBecomeCurrentAfterExpiry() throws Exception {
    var source = source();
    try (var h =
        new Harness(
            directory,
            "gs",
            Clock.fixed(source.snapshot().preparation().forkExpiresAt(), ZoneOffset.UTC))) {
      var held = h.client.acquire(UUID.randomUUID(), source);
      assertThat(held.currentRead()).isFalse();
      assertThatThrownBy(() -> h.client.readCurrent(held))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(h.calls.get()).isEqualTo(2);
    }
  }

  @Test
  void ambientSqlAndCancellationDoNotAcquireProtection() throws Exception {
    try (var h = new Harness(directory, "gs", WorldPrivatePreparationSourceClientMtlsTest.CLOCK)) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> h.client.acquire(UUID.randomUUID(), source()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      try (var cancelled = Context.current().withCancellation()) {
        cancelled.cancel(null);
        cancelled.run(
            () ->
                assertThatThrownBy(() -> h.client.acquire(UUID.randomUUID(), source()))
                    .isInstanceOf(StatusRuntimeException.class));
      }
      assertThat(h.calls.get()).isZero();
    }
  }

  private static PrivateWorldPreparationReadEvidence.Evidence source() {
    var snapshot = WorldPrivatePreparationSourceClientMtlsTest.snapshot();
    return new PrivateWorldPreparationReadEvidence.Evidence(
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(), snapshot.scope(), snapshot.preparation().requestId()),
        snapshot);
  }

  private static final class Harness implements AutoCloseable {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> peer = new AtomicReference<>();
    final AtomicReference<Held> retained = new AtomicReference<>();
    final WorldPrivatePreparationProtectionClient client;
    final Server server;

    Harness(Path directory, String identity, Clock clock) throws Exception {
      Files.createDirectories(directory);
      var pki =
          LoopbackMtlsTestPki.create(
              directory,
              "Private protection test CA",
              new LoopbackMtlsTestPki.IdentitySpec(
                  "world", "spiffe://firemud/ns/" + NS + "/sa/world-management-service", false),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "gs", "spiffe://firemud/ns/" + NS + "/sa/game-session-service", true),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong-service", "spiffe://firemud/ns/" + NS + "/sa/account-service", true),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong-namespace", "spiffe://firemud/ns/other/sa/game-session-service", true));
      var handler =
          new PrivatePreparationProtectionServiceGrpc
              .PrivatePreparationProtectionServiceImplBase() {
            @Override
            public void acquirePrivatePreparationProtection(
                AcquirePrivatePreparationProtectionRequest wire,
                StreamObserver<PrivatePreparationProtectionResponse> observer) {
              calls.incrementAndGet();
              peer.set(GrpcPeerIdentity.current().uri());
              var request = PrivatePreparationProtectionGrpcCodec.parse(wire);
              retained.compareAndSet(
                  null,
                  new Held(request.requestId(), UUID.randomUUID(), 1, request.expectedSource()));
              observer.onNext(
                  PrivatePreparationProtectionGrpcCodec.response(request, retained.get()));
              observer.onCompleted();
            }

            @Override
            public void readCurrentPrivatePreparationProtection(
                ReadCurrentPrivatePreparationProtectionRequest wire,
                StreamObserver<PrivatePreparationProtectionResponse> observer) {
              calls.incrementAndGet();
              peer.set(GrpcPeerIdentity.current().uri());
              var request = PrivatePreparationProtectionGrpcCodec.parse(wire);
              observer.onNext(
                  PrivatePreparationProtectionGrpcCodec.response(request, retained.get()));
              observer.onCompleted();
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
          new WorldPrivatePreparationProtectionClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("world")),
              new GrpcChannelFactory(),
              NS,
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
}
