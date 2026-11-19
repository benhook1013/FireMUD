package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Deadline;
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
import org.junit.jupiter.params.provider.ValueSource;

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
  void blankEndpointIsUnsetAndExplicitAccountEndpointIsPreserved() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var factory = new GrpcChannelFactory();
    try (var client =
        new AccountTenantCapacityClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("client")),
            factory,
            "prod",
            Clock.fixed(CapacityAdmissionTestFixture.NOW, ZoneOffset.UTC))) {
      assertThat(client.defaultTarget()).isEqualTo("account-service:6565");
      for (String blank : new String[] {null, "", " \t "}) {
        endpoints.setAccountService(blank);
        assertThat(client.configuredTarget(endpoints)).isNull();
      }
      endpoints.setAccountService("account.internal:7443");
      assertThat(client.configuredTarget(endpoints)).isEqualTo("account.internal:7443");
    }
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
              refusal -> {
                assertThat(refusal.code())
                    .isEqualTo(TenantCapacityAdmissionGrpcCodec.Failure.TENANT_BILLING_BLOCKED);
                assertThat(refusal.durableDenialId()).isNull();
              });
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void quotaRefusalExposesAReceiptOnlyWhenTheAuthenticatedAccountSuppliesOne(boolean retained)
      throws Exception {
    var request = CapacityAdmissionTestFixture.playerRequest();
    UUID decisionId = retained ? UUID.randomUUID() : null;
    var response =
        TenantCapacityAdmissionGrpcCodec.toRefusal(
            request, TenantCapacityAdmissionGrpcCodec.Failure.CAPACITY_QUOTA_EXCEEDED, decisionId);
    Server server =
        startServer(
            "account",
            new AccountServiceGrpc.AccountServiceImplBase() {
              @Override
              public void commitTenantCapacityAdmission(
                  CommitTenantCapacityAdmissionRequest wire,
                  StreamObserver<CommitTenantCapacityAdmissionResponse> observer) {
                assertThat(TenantCapacityAdmissionGrpcCodec.parseRequest(wire)).isEqualTo(request);
                observer.onNext(response);
                observer.onCompleted();
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.commit(request, deadline()))
          .isInstanceOfSatisfying(
              AccountTenantCapacityClient.CapacityRefusedException.class,
              refusal -> {
                assertThat(refusal.code())
                    .isEqualTo(TenantCapacityAdmissionGrpcCodec.Failure.CAPACITY_QUOTA_EXCEEDED);
                assertThat(refusal.durableDenialId()).isEqualTo(decisionId);
              });
    } finally {
      stop(server);
    }
  }

  @Test
  void localDenialReplayRequiresADurableDecisionId() {
    var denialId = UUID.randomUUID();
    var replay = AccountTenantCapacityClient.CapacityRefusedException.retainedQuotaDenial(denialId);
    assertThat(replay.code())
        .isEqualTo(TenantCapacityAdmissionGrpcCodec.Failure.CAPACITY_QUOTA_EXCEEDED);
    assertThat(replay.durableDenialId()).isEqualTo(denialId);
    assertThatThrownBy(() ->
            assertThat(
                    AccountTenantCapacityClient.CapacityRefusedException.retainedQuotaDenial(null))
                .isNotNull())
        .isInstanceOf(IllegalArgumentException.class);
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
              failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN))
          .satisfies(failure ->
              assertThat(Status.fromThrowable(failure).getDescription())
                  .isEqualTo("Account capacity commitment response was inexact"));
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
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure -> {
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                assertThat(failure.getStatus().getDescription())
                    .isEqualTo("Request requires the exact authenticated server workload identity");
              });
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
          .satisfies(failure ->
              assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNKNOWN))
          .satisfies(failure ->
              assertThat(Status.fromThrowable(failure).getDescription())
                  .isEqualTo("Account capacity commitment response was inexact"));
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
    // Warm the channel, then reset the sample before the measured RPC.
    Server server = server("account", calls, false, observedNanos, new AtomicBoolean(true));
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      client.commit(request, deadline());
      observedNanos.set(0);
      try (var context =
          Context.current().withDeadlineAfter(incomingMillis, TimeUnit.MILLISECONDS, scheduler)) {
        var result =
            context.call(() ->
                client.commit(
                    request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + explicitMillis));
        assertThat(result.identity()).isEqualTo(request.identity());
      }
      assertThat(calls).hasValue(2);
      // Delivery can consume any portion of the budget; the controlled ticker asserts selection.
      assertThat(observedNanos.get())
          .isPositive()
          .isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(expectedMaximumMillis));
    } finally {
      scheduler.shutdownNow();
      stop(server);
    }
  }

  @ParameterizedTest
  @CsvSource({"3000, 60000, 3000", "60000, 3000, 3000", "60000, 60000, 5000"})
  void deadlineSelectionKeepsTheWholeChosenBudget(
      long explicitMillis, long incomingMillis, long expectedMillis) {
    var nanos = new AtomicLong();
    var ticker =
        new Deadline.Ticker() {
          @Override
          public long nanoTime() {
            return nanos.get();
          }
        };
    var maximum = Deadline.after(5, TimeUnit.SECONDS, ticker);
    var incoming = Deadline.after(incomingMillis, TimeUnit.MILLISECONDS, ticker);
    var sample = Deadline.after(0, TimeUnit.NANOSECONDS, ticker);
    var selected =
        AccountTenantCapacityClient.selectDeadline(
            maximum, sample, incoming, explicitMillis, java.time.Instant.EPOCH);
    assertThat(selected.timeRemaining(TimeUnit.MILLISECONDS)).isEqualTo(expectedMillis);
  }

  @ParameterizedTest
  @CsvSource({"-9223372036854775808, 1, 0", "9223372036854775807, -1, 5000", "0, 0, 0"})
  void extremeWallClockDeadlinesRemainExpiredOrCapped(
      long absoluteMillis, long sampledMillis, long expectedMillis) {
    var ticker =
        new Deadline.Ticker() {
          @Override
          public long nanoTime() {
            return 0;
          }
        };
    var sample = Deadline.after(0, TimeUnit.NANOSECONDS, ticker);
    var maximum = Deadline.after(5, TimeUnit.SECONDS, ticker);
    assertThat(
            AccountTenantCapacityClient.selectDeadline(
                    maximum,
                    sample,
                    null,
                    absoluteMillis,
                    java.time.Instant.ofEpochMilli(sampledMillis))
                .timeRemaining(TimeUnit.MILLISECONDS))
        .isEqualTo(expectedMillis);
  }

  @Test
  void fractionalWallClockSampleDoesNotExtendTheCallerBudget() {
    var ticker =
        new Deadline.Ticker() {
          @Override
          public long nanoTime() {
            return 0;
          }
        };
    var sample = Deadline.after(0, TimeUnit.NANOSECONDS, ticker);
    var maximum = Deadline.after(5, TimeUnit.SECONDS, ticker);
    var selected =
        AccountTenantCapacityClient.selectDeadline(
            maximum, sample, null, 1001, java.time.Instant.ofEpochMilli(1000).plusNanos(900_000));
    assertThat(selected.timeRemaining(TimeUnit.NANOSECONDS)).isEqualTo(100_000);
  }

  @Test
  void setupDelayIsNotDeductedTwiceAndOriginalInvocationCapStillApplies() {
    var nanos = new AtomicLong();
    var ticker =
        new Deadline.Ticker() {
          @Override
          public long nanoTime() {
            return nanos.get();
          }
        };
    var maximum = Deadline.after(5, TimeUnit.SECONDS, ticker);
    nanos.set(TimeUnit.SECONDS.toNanos(1)); // Pause before the paired conversion samples.
    var sample = Deadline.after(0, TimeUnit.NANOSECONDS, ticker);
    assertThat(
            AccountTenantCapacityClient.selectDeadline(
                    maximum, sample, null, 3000, java.time.Instant.ofEpochMilli(1000))
                .timeRemaining(TimeUnit.MILLISECONDS))
        .isEqualTo(2000);
    assertThat(
            AccountTenantCapacityClient.selectDeadline(
                    maximum, sample, null, 60000, java.time.Instant.ofEpochMilli(1000))
                .timeRemaining(TimeUnit.MILLISECONDS))
        .isEqualTo(4000);
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(200)); // Delay after sampling consumes budget.
    assertThat(
            AccountTenantCapacityClient.selectDeadline(
                    maximum, sample, null, 3000, java.time.Instant.ofEpochMilli(1000))
                .timeRemaining(TimeUnit.MILLISECONDS))
        .isEqualTo(1800);
  }

  @Test
  void explicitRemainingBudgetBoundsAStalledAccountCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean respond = new AtomicBoolean(true);
    AtomicLong observedNanos = new AtomicLong();
    Server server = server("account", calls, false, observedNanos, respond);
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      client.commit(request, deadline());
      respond.set(false);
      assertThatThrownBy(() ->
              client.commit(request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + 1_000L))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(failure ->
              assertThat(Status.fromThrowable(failure).getCode())
                  .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(calls).hasValue(2); // The timed-out invocation reached the nonresponding handler.
      assertThat(observedNanos.get()).isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
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
              executor.submit(() ->
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
    AtomicLong observedNanos = new AtomicLong();
    Server server = server("account", calls, false, observedNanos, respond);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (var client = client(server)) {
      client.init();
      var request = CapacityAdmissionTestFixture.playerRequest();
      client.commit(request, CapacityAdmissionTestFixture.NOW.toEpochMilli() + 60_000L);
      respond.set(false);
      assertThatThrownBy(() ->
              Context.current()
                  .withDeadlineAfter(1_000, TimeUnit.MILLISECONDS, scheduler)
                  .call(() -> client.commit(request, deadline())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(failure ->
              assertThat(Status.fromThrowable(failure).getCode())
                  .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(calls).hasValue(2); // The incoming deadline bounded an actual in-flight call.
      assertThat(observedNanos.get()).isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
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
      assertThatThrownBy(() ->
              client.commit(
                  CapacityAdmissionTestFixture.playerRequest(),
                  CapacityAdmissionTestFixture.NOW.toEpochMilli() - 1L))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(failure ->
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
            observedNanos.set(
                Objects.requireNonNull(Context.current().getDeadline())
                    .timeRemaining(TimeUnit.NANOSECONDS));
            calls.incrementAndGet();
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
    return startServer(identity, service);
  }

  private static Server startServer(
      String identity, AccountServiceGrpc.AccountServiceImplBase service) throws Exception {
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
          releaseReload.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new SSLException("Test reload wait interrupted", interrupted);
        }
      }
      return super.buildChannel(target, defaultPort, properties, keepAlive);
    }
  }
}
