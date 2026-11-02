package net.firedevops.firemud.accountservice.testsupport;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.client.GameSessionCurrentPrivatePlaytestLifecycleReadClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.ReadState;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.v1.CurrentPrivatePlaytestLifecycleReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleRequest;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;

/**
 * Controlled Game Session transport data behind real loopback mTLS and the actual Account client.
 * It supplies no actual Game Session SQL producer, private policy or workflow authority.
 */
public final class CurrentPrivatePlaytestReadTransportFixture implements AutoCloseable {
  public static final String NAMESPACE = "private-read-proof";
  private static final String ACCOUNT = "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service";
  private static final String GS = "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private final Server server;
  private final GameSessionCurrentPrivatePlaytestLifecycleReadClient client;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicBoolean finiteDeadline = new AtomicBoolean();
  private final AtomicReference<Function<Request, ReadCurrentPrivatePlaytestLifecycleResponse>>
      responder =
          new AtomicReference<>(
              request ->
                  CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(
                      request, Optional.empty()));

  public static LoopbackMtlsTestPki.Pki createPki(Path directory) throws Exception {
    return LoopbackMtlsTestPki.create(
        directory,
        "Controlled private lifecycle source CA",
        new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
        new LoopbackMtlsTestPki.IdentitySpec(
            "wrong-workload",
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
            true),
        new LoopbackMtlsTestPki.IdentitySpec(
            "wrong-namespace", "spiffe://firemud/ns/other-private/sa/game-session-service", true),
        new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false));
  }

  public CurrentPrivatePlaytestReadTransportFixture(
      LoopbackMtlsTestPki.Pki pki, Path directory, String serverIdentity) throws Exception {
    var identity = pki.identity(serverIdentity);
    var service =
        new CurrentPrivatePlaytestLifecycleReadServiceGrpc
            .CurrentPrivatePlaytestLifecycleReadServiceImplBase() {
          @Override
          public void readCurrentPrivatePlaytestLifecycle(
              ReadCurrentPrivatePlaytestLifecycleRequest wire,
              StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer) {
            calls.incrementAndGet();
            try {
              var peer = GrpcPeerIdentity.current();
              if (peer == null || !ACCOUNT.equals(peer.uri()))
                throw Status.PERMISSION_DENIED.asRuntimeException();
              var deadline = Context.current().getDeadline();
              finiteDeadline.set(
                  deadline != null
                      && deadline.timeRemaining(TimeUnit.MILLISECONDS) > 0
                      && deadline.timeRemaining(TimeUnit.MILLISECONDS) <= 5000);
              var request = CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(wire);
              observer.onNext(responder.get().apply(request));
              observer.onCompleted();
            } catch (RuntimeException failure) {
              observer.onError(failure);
            }
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    client =
        new GameSessionCurrentPrivatePlaytestLifecycleReadClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("account-client")),
            new GrpcChannelFactory(),
            NAMESPACE);
    client.init();
  }

  public GameSessionCurrentPrivatePlaytestLifecycleReadClient client() {
    return client;
  }

  public int calls() {
    return calls.get();
  }

  public boolean finiteDeadlineObserved() {
    return finiteDeadline.get();
  }

  public void respond(Function<Request, ReadCurrentPrivatePlaytestLifecycleResponse> response) {
    responder.set(response);
  }

  public void current(Optional<Snapshot> snapshot) {
    respond(request -> CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(request, snapshot));
  }

  /** Explicitly controlled closed transport snapshot, never a qualified GS SQL producer. */
  public static Snapshot snapshot(
      Scope scope, UUID lifecycle, Instant forkExpiry, Instant observedAt) {
    return snapshot(scope, lifecycle, forkExpiry, observedAt, 1L);
  }

  public static Snapshot snapshot(
      Scope scope, UUID lifecycle, Instant forkExpiry, Instant observedAt, long headRevision) {
    return new Snapshot(
        scope,
        UUID.randomUUID(),
        headRevision,
        "sha256:" + "a".repeat(64),
        observedAt.minusSeconds(2),
        UUID.randomUUID(),
        "sha256:" + "b".repeat(64),
        lifecycle,
        UUID.randomUUID(),
        1L,
        forkExpiry,
        "PREPARING",
        observedAt.isBefore(forkExpiry) ? ReadState.INCOMPLETE : ReadState.EXPIRED,
        observedAt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        "controlled-release-bundle",
        "sha256:" + "c".repeat(64),
        UUID.randomUUID(),
        "sha256:" + "d".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "controlled-policy-revision",
        "sha256:" + "e".repeat(64));
  }

  @Override
  public void close() throws IOException {
    try {
      client.close();
    } finally {
      server.shutdownNow();
      try {
        if (!server.awaitTermination(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Controlled GS transport did not terminate");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
  }
}
