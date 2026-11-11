package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback mTLS with a controlled Account result; not Account sources or final admission. */
@Timeout(90)
class AccountTenantCapacityClientMtlsTest {
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
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> {
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                var status = Objects.requireNonNull(StatusProto.fromThrowable(failure));
                assertThat(status.getDetailsList())
                    .containsExactly(
                        Any.pack(
                            ErrorDetail.newBuilder()
                                .setCode("TENANT_BILLING_BLOCKED")
                                .setMessage("Tenant capacity commitment did not complete")
                                .build()));
              });
      assertThat(calls).hasValue(1);
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
  void changedResponseFenceCannotSubstituteForOriginalCommitment() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Server server = server("account", calls, true);
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.commit(CapacityAdmissionTestFixture.playerRequest(), deadline()))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      stop(server);
    }
  }

  @Test
  void explicitRemainingBudgetBoundsAStalledAccountCallAndExactRetryStillReplays()
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicLong observedNanos = new AtomicLong();
    AtomicBoolean respond = new AtomicBoolean(true);
    Server server = server("account", calls, false, observedNanos, respond);
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
      assertThat(observedNanos.get())
          .isPositive()
          .isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(200));
      respond.set(true);
      assertThat(client.commit(request, deadline())).isEqualTo(first);
      assertThat(calls).hasValue(3);
    } finally {
      stop(server);
    }
  }

  @Test
  void incomingDeadlineAndFiveSecondMaximumAlsoCapTheAccountCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicLong observedNanos = new AtomicLong();
    AtomicBoolean respond = new AtomicBoolean(true);
    Server server = server("account", calls, false, observedNanos, respond);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      client.commit(request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + 60_000L);
      assertThat(observedNanos.get()).isPositive().isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(5));
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
      assertThat(observedNanos.get())
          .isPositive()
          .isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(200));
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
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new AccountTenantCapacityClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
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
      AtomicLong observedNanos,
      AtomicBoolean respond)
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
            assertThat(GrpcPeerIdentity.current().uri())
                .isEqualTo("spiffe://firemud/ns/prod/sa/game-session-service");
            var request = TenantCapacityAdmissionGrpcCodec.parseRequest(wire);
            if (!request.evidence().permitsCreation()) {
              assertThat(request).isEqualTo(CapacityAdmissionTestFixture.playerRequest(false));
              // Controlled transport response only; the real Account owner proves policy authority.
              observer.onError(
                  StatusProto.toStatusRuntimeException(
                      com.google.rpc.Status.newBuilder()
                          .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
                          .setMessage("Tenant capacity commitment did not complete")
                          .addDetails(
                              Any.pack(
                                  ErrorDetail.newBuilder()
                                      .setCode("TENANT_BILLING_BLOCKED")
                                      .setMessage("Tenant capacity commitment did not complete")
                                      .build()))
                          .build()));
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

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }
}
