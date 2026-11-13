package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.ManagedChannel;
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
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real loopback mTLS with a controlled Account result; not Account sources or final admission. */
@Timeout(90)
class AccountTenantCapacityClientMtlsTest {
  private static final String TLS_RELOAD_PROPERTY = "firemud.grpc.tls-reload.enabled";
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Capacity client test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", "spiffe://firemud/ns/prod/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/prod/sa/account-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong", "spiffe://firemud/ns/prod/sa/world-management-service", true));
  }

  @Test
  void exactAccountPeerReceivesCompleteRequestAndExactRetryKeepsReservation() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Server server = server("account", calls, false);
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      var first = client.commit(request, deadline());
      assertThat(client.commit(request, deadline())).isEqualTo(first);
      assertThat(first.identity()).isEqualTo(request.identity());
      assertThat(calls).hasValue(2);
    } finally {
      stop(server);
    }
  }

  @Test
  void nonCreatingEvidenceReachesControlledAccountAndReturnsStructuredPolicyDenial()
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Server server = server("account", calls, false);
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest(false);
      assertThatThrownBy(() -> client.commit(request, deadline()))
          .isInstanceOfSatisfying(
              AccountTenantCapacityClient.CapacityRefusedException.class,
              refusal ->
                  assertThat(refusal.code())
                      .isEqualTo(TenantCapacityAdmissionGrpcCodec.Failure.TENANT_BILLING_BLOCKED));
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
  }

  @Test
  void mismatchedTypedRefusalDoesNotBecomeAnAccountDecision() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    Server server = server("account", calls, true, serverFailure);
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest(false);
      assertThatThrownBy(() -> client.commit(request, deadline()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN));
      assertThat(calls).hasValue(1);
      assertThat(serverFailure.get()).isNull();
    } finally {
      stop(server);
    }
  }

  @Test
  void wrongAccountPeerIsRejectedBeforeRequestBodyRelease() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Server server = server("wrong", calls, false);
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.commit(CapacityAdmissionTestFixture.playerRequest(), deadline()))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(calls).hasValue(0);
    } finally {
      stop(server);
    }
  }

  @Test
  void changedResponseFenceLeavesTheOriginalCommitmentIndeterminate() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    Server server = server("account", calls, true, serverFailure);
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.commit(CapacityAdmissionTestFixture.playerRequest(), deadline()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNKNOWN));
      assertThat(calls).hasValue(1);
      assertThat(serverFailure.get()).isNull();
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @CsvSource({"3000, 60000, 3000", "60000, 3000, 3000", "60000, 60000, 5000"})
  void successfulRpcPropagatesMinimumOfExplicitIncomingAndMaximumDeadline(
      long explicitMillis, long incomingMillis, long expectedMaximumMillis) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicLong observedNanos = new AtomicLong();
    // Each case owns one server and one successful RPC, so no other call can supply its sample.
    Server server = server("account", calls, false, observedNanos, new AtomicBoolean(true));
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      try (var context =
          Context.current().withDeadlineAfter(incomingMillis, TimeUnit.MILLISECONDS, scheduler)) {
        var result =
            context.call(
                () ->
                    client.commit(
                        request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + explicitMillis));
        assertThat(result.identity()).isEqualTo(request.identity());
      }
      assertThat(calls).hasValue(1);
      assertThat(observedNanos.get())
          .isPositive()
          .isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(expectedMaximumMillis));
    } finally {
      scheduler.shutdownNow();
      stop(server);
    }
  }

  @Test
  void explicitRemainingBudgetBoundsAStalledAccountCallAndExactRetryStillReplays()
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean respond = new AtomicBoolean(true);
    Server server = server("account", calls, false, new AtomicLong(), respond);
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      var first = client.commit(request, deadline());
      respond.set(false);
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> client.commit(request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + 200L))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000L);
      respond.set(true);
      assertThat(client.commit(request, deadline())).isEqualTo(first);
    } finally {
      stop(server);
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void commitReachesItsTransportDeadlineWhileTlsReloadIsBlocked() throws Exception {
    String previousReloadPolicy = System.getProperty(TLS_RELOAD_PROPERTY);
    System.setProperty(TLS_RELOAD_PROPERTY, "true");
    Server server = null;
    try {
      var calls = new AtomicInteger();
      var respond = new AtomicBoolean(true);
      var tls = pki.clientProperties(directory, pki.identity("client"));
      var factory = new BlockingReloadChannelFactory();
      server = server("account", calls, false, new AtomicLong(), respond);
      try (var client = client(server, tls, factory);
          var executor = Executors.newSingleThreadExecutor()) {
        client.init();
        var request = CapacityAdmissionTestFixture.playerRequest();
        client.commit(request, deadline());
        respond.set(false);
        try {
          Files.writeString(Path.of(tls.getCertChain()), "\n", StandardOpenOption.APPEND);
          assertThat(factory.reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
          long explicitDeadline = CapacityAdmissionTestFixture.NOW.toEpochMilli() + 1_000L;
          var timedOut =
              executor.submit(
                  () ->
                      assertThatThrownBy(() -> client.commit(request, explicitDeadline))
                          .isInstanceOfSatisfying(
                              StatusRuntimeException.class,
                              failure ->
                                  assertThat(failure.getStatus().getCode())
                                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED)));
          timedOut.get(3, TimeUnit.SECONDS);
          assertThat(calls).hasValue(2);
        } finally {
          factory.releaseReload.countDown();
        }
      }
    } finally {
      try {
        if (server != null) stop(server);
      } finally {
        if (previousReloadPolicy == null) {
          System.clearProperty(TLS_RELOAD_PROPERTY);
        } else {
          System.setProperty(TLS_RELOAD_PROPERTY, previousReloadPolicy);
        }
      }
    }
  }

  @Test
  void incomingDeadlineBoundsAStalledAccountCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean respond = new AtomicBoolean(true);
    Server server = server("account", calls, false, new AtomicLong(), respond);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      client.commit(request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + 60_000L);
      respond.set(false);
      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  Context.current()
                      .withDeadlineAfter(200, TimeUnit.MILLISECONDS, scheduler)
                      .call(() -> client.commit(request, deadline())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000L);
    } finally {
      scheduler.shutdownNow();
      stop(server);
    }
  }

  @Test
  void expiredExplicitBudgetSendsNoAccountRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Server server = server("account", calls, false);
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.commit(
                      CapacityAdmissionTestFixture.playerRequest(),
                      CapacityAdmissionTestFixture.NOW.toEpochMilli() - 1L))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(calls).hasValue(0);
    } finally {
      stop(server);
    }
  }

  private static long deadline() {
    return CapacityAdmissionTestFixture.NOW.toEpochMilli() + 5_000L;
  }

  private static AccountTenantCapacityClient client(Server server) throws Exception {
    return client(
        server, pki.clientProperties(directory, pki.identity("client")), new GrpcChannelFactory());
  }

  private static AccountTenantCapacityClient client(
      Server server, CommonGrpcClientProperties tls, GrpcChannelFactory factory) {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new AccountTenantCapacityClient(
        endpoints,
        tls,
        factory,
        "prod",
        Clock.fixed(CapacityAdmissionTestFixture.NOW, ZoneOffset.UTC));
  }

  private static Server server(String identity, AtomicInteger calls, boolean wrongFence)
      throws Exception {
    return server(identity, calls, wrongFence, new AtomicLong(), new AtomicBoolean(true));
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      boolean wrongFence,
      AtomicReference<Throwable> serverFailure)
      throws Exception {
    return server(
        identity, calls, wrongFence, new AtomicLong(), new AtomicBoolean(true), serverFailure);
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      boolean wrongFence,
      AtomicLong observedNanos,
      AtomicBoolean respond)
      throws Exception {
    return server(identity, calls, wrongFence, observedNanos, respond, null);
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      boolean wrongFence,
      AtomicLong observedNanos,
      AtomicBoolean respond,
      AtomicReference<Throwable> serverFailure)
      throws Exception {
    UUID reservationId = UUID.randomUUID();
    var service =
        new AccountServiceGrpc.AccountServiceImplBase() {
          @Override
          public void commitTenantCapacityAdmission(
              CommitTenantCapacityAdmissionRequest wire,
              StreamObserver<CommitTenantCapacityAdmissionResponse> observer) {
            calls.incrementAndGet();
            observedNanos.set(
                Objects.requireNonNull(Context.current().getDeadline())
                    .timeRemaining(TimeUnit.NANOSECONDS));
            if (!respond.get()) return;
            assertServerSide(
                serverFailure,
                () ->
                    assertThat(GrpcPeerIdentity.current().uri())
                        .isEqualTo("spiffe://firemud/ns/prod/sa/game-session-service"));
            var request = TenantCapacityAdmissionGrpcCodec.parseRequest(wire);
            assertServerSide(
                serverFailure,
                () ->
                    assertThat(request)
                        .isEqualTo(
                            CapacityAdmissionTestFixture.playerRequest(
                                request.evidence().permitsCreation())));
            if (!request.evidence().permitsCreation()) {
              var response =
                  TenantCapacityAdmissionGrpcCodec.toRefusal(
                      request, TenantCapacityAdmissionGrpcCodec.Failure.TENANT_BILLING_BLOCKED);
              if (wrongFence)
                response =
                    response.toBuilder()
                        .setRefusal(
                            0, response.getRefusal(0).toBuilder().setCapacityRearmFence(0, 2))
                        .build();
              observer.onNext(response);
              observer.onCompleted();
              return;
            }
            var result =
                new TenantCapacityAdmission.Result(
                    request.identity(),
                    request.admissionAttemptId(),
                    request.capacityRearmFence(),
                    request.mutationDigest(),
                    reservationId,
                    1,
                    request.evidence().expiresAt(),
                    TenantCapacityAdmission.State.RESERVED,
                    request.evidence().snapshotIdentity(),
                    request.evidence().evaluationIdentity());
            var response = TenantCapacityAdmissionGrpcCodec.toResponse(request, result);
            if (wrongFence)
              response =
                  response.toBuilder()
                      .setCommitment(
                          0, response.getCommitment(0).toBuilder().setCapacityRearmFence(0, 2))
                      .build();
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    var peer = pki.identity(identity);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(peer.privateKey(), peer.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void assertServerSide(
      AtomicReference<Throwable> serverFailure, Runnable assertion) {
    try {
      assertion.run();
    } catch (AssertionError failure) {
      if (serverFailure != null) serverFailure.compareAndSet(null, failure);
      throw failure;
    }
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static final class BlockingReloadChannelFactory extends GrpcChannelFactory {
    private final AtomicBoolean initialBuild = new AtomicBoolean(true);
    private final CountDownLatch reloadStarted = new CountDownLatch(1);
    private final CountDownLatch releaseReload = new CountDownLatch(1);

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      if (!initialBuild.getAndSet(false)) {
        reloadStarted.countDown();
        try {
          if (!releaseReload.await(10, TimeUnit.SECONDS)) {
            throw new SSLException("Test reload release timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new SSLException("Test reload wait interrupted", interrupted);
        }
      }
      return super.buildChannel(target, defaultPort, properties, keepAlive);
    }
  }
}
