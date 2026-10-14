package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition;
import net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalProofKind;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Physical mTLS request-release proof; Account replies/PKI are controlled and non-admitting. */
class CanonicalGameplayLoginAccountClientTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_URI = "spiffe://firemud/ns/other/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  @TempDir static Path temporaryDirectory;
  private static TestPki pki;

  @BeforeAll
  static void createControlledPki() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @Test
  void realMutualTlsPeerReceivesOriginalRequestAndReturnsTokenFreeDecision() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server = startServer(pki.accountServer(), starts, requests, 0, Status.UNAVAILABLE);
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    try (var client = client(server, Duration.ofSeconds(5), verifier)) {
      client.initialize();
      var original = request();
      assertDecision(client, original, () -> true);
      assertThat(starts.get()).isEqualTo(1);
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().equals(original)).isTrue();
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"workload", "namespace"})
  void wrongAuthenticatedAccountPeerReceivesNeitherHeadersNorCredential(String wrong)
      throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server =
        startServer(
            wrong.equals("workload") ? pki.wrongWorkloadServer() : pki.wrongNamespaceServer(),
            starts,
            requests,
            0,
            Status.UNAVAILABLE);
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    try (var client = client(server, Duration.ofSeconds(5), verifier)) {
      client.initialize();
      assertThatThrownBy(() -> client.authenticate(request(), () -> true))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(starts.get()).isZero();
      assertThat(requests).isEmpty();
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void oneAmbiguousTransportRetryReusesExactRequestAndOverallDeadline(String status)
      throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server =
        startServer(
            pki.accountServer(), starts, requests, 1, Status.fromCode(Status.Code.valueOf(status)));
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    List<Deadline> deadlines = new CopyOnWriteArrayList<>();
    var customizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            return stub.withInterceptors(
                new io.grpc.ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                      io.grpc.MethodDescriptor<ReqT, RespT> method,
                      io.grpc.CallOptions options,
                      io.grpc.Channel next) {
                    deadlines.add(options.getDeadline());
                    return next.newCall(method, options);
                  }
                });
          }
        };
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    try (var client =
        new CanonicalGameplayLoginAccountClient(
            endpoints,
            pki.clientProperties(temporaryDirectory),
            new GrpcChannelFactory(),
            customizer,
            "test",
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            verifier)) {
      client.initialize();
      var original = request();
      assertDecision(client, original, () -> true);
      assertThat(starts.get()).isEqualTo(2);
      assertThat(requests).hasSize(2);
      assertThat(deadlines).hasSize(2);
      assertThat(deadlines.getFirst() == deadlines.get(1)).isTrue();
      assertThat(requests.stream().allMatch(original::equals)).isTrue();
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void custodyLossAfterFirstAmbiguousDispatchPreventsThePermittedRetry() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server = startServer(pki.accountServer(), starts, requests, 1, Status.UNAVAILABLE);
    AtomicBoolean custodyCurrent = new AtomicBoolean(true);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    var customizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            return stub.withInterceptors(
                new io.grpc.ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                      io.grpc.MethodDescriptor<ReqT, RespT> method,
                      io.grpc.CallOptions options,
                      io.grpc.Channel next) {
                    custodyCurrent.set(false);
                    return next.newCall(method, options);
                  }
                });
          }
        };
    try (var client =
        new CanonicalGameplayLoginAccountClient(
            endpoints,
            pki.clientProperties(temporaryDirectory),
            new GrpcChannelFactory(),
            customizer,
            "test",
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      assertThatThrownBy(() -> client.authenticate(request(), custodyCurrent::get))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.CANCELLED));
      assertThat(starts.get()).isEqualTo(1);
      assertThat(requests).hasSize(1);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void terminalTransportOutcomeIsNotRetriedAndDiagnosticsAreBounded() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server = startServer(pki.accountServer(), starts, requests, 5, Status.PERMISSION_DENIED);
    try (var client =
        client(server, Duration.ofSeconds(5), mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      assertThatThrownBy(() -> client.authenticate(request(), () -> true))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> {
                assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                assertThat(error.getMessage()).doesNotContain("private-secret");
              });
      assertThat(starts.get()).isEqualTo(1);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void secondAmbiguousOutcomeEndsInvocationWithoutNewRetryAuthority() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server = startServer(pki.accountServer(), starts, requests, 5, Status.UNAVAILABLE);
    try (var client =
        client(server, Duration.ofSeconds(5), mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      assertThatThrownBy(() -> client.authenticate(request(), () -> true))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(starts.get()).isEqualTo(2);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void expiredParentDeadlineAndInvalidSourceDenyBeforeTransportCall() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    Server server = startServer(pki.accountServer(), starts, requests, 0, Status.UNAVAILABLE);
    try (var client =
        client(server, Duration.ofSeconds(5), mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
      try (var expired =
          io.grpc.Context.current()
              .withDeadline(Deadline.after(-1, TimeUnit.NANOSECONDS), scheduler)) {
        expired.run(
            () ->
                assertThatThrownBy(() -> client.authenticate(request(), () -> true))
                    .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error ->
                            assertThat(error.getStatus().getCode())
                                .isEqualTo(Status.Code.DEADLINE_EXCEEDED)));
      } finally {
        scheduler.shutdownNow();
      }
      var invalid =
          request().toBuilder()
              .setSourceContext(
                  request().getSourceContext().toBuilder()
                      .setTransportClass(
                          GameplayLoginTransportClass
                              .GAMEPLAY_LOGIN_TRANSPORT_CLASS_FIRST_PARTY_WEB))
              .build();
      assertThatThrownBy(() -> client.authenticate(invalid, () -> true))
          .isInstanceOf(CanonicalGameplayLoginResult.InvalidResultException.class);
      assertThat(starts.get()).isZero();
      assertThat(requests).isEmpty();
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void constructorRequiresMutualTlsAndExplicitInitialization() throws Exception {
    var tls = pki.clientProperties(temporaryDirectory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayLoginAccountClient(
                    new ServiceEndpointsProperties(),
                    tls,
                    new GrpcChannelFactory(),
                    BlockingGrpcStubCustomizer.noop(),
                    "test",
                    Clock.systemUTC(),
                    Duration.ofSeconds(5),
                    mock(AccountAsymmetricJwtVerifier.class)))
        .isInstanceOf(IllegalArgumentException.class);
    var factory = mock(GrpcChannelFactory.class);
    try (var uninitialized =
        new CanonicalGameplayLoginAccountClient(
            new ServiceEndpointsProperties(),
            pki.clientProperties(temporaryDirectory),
            factory,
            BlockingGrpcStubCustomizer.noop(),
            "test",
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            mock(AccountAsymmetricJwtVerifier.class))) {
      assertThatThrownBy(() -> uninitialized.authenticate(request(), () -> true))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      verifyNoInteractions(factory);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "RETIRED", "NO_TOKEN"})
  void disposalUsesOriginalNonsecretIdentityWithoutAuthenticateOrActiveToken(String disposition)
      throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<RetireInitialGameplayLoginRequest> requests = new CopyOnWriteArrayList<>();
    var response =
        disposalResponse().toBuilder()
            .setDisposition(
                InitialGameplayLoginDisposalDisposition.valueOf(
                    "INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_" + disposition))
            .setProofKind(
                switch (disposition) {
                  case "PENDING" ->
                      InitialGameplayLoginDisposalProofKind
                          .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_UNSPECIFIED;
                  case "RETIRED" ->
                      InitialGameplayLoginDisposalProofKind
                          .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_PHYSICAL_TOKEN_DENIAL;
                  case "NO_TOKEN" ->
                      InitialGameplayLoginDisposalProofKind
                          .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_NO_ISSUANCE_BINDING;
                  default -> throw new AssertionError("Unexpected test disposition");
                })
            .build();
    Server server =
        startDisposalServer(pki.accountServer(), starts, requests, 0, Status.UNAVAILABLE, response);
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    try (var client = client(server, Duration.ofSeconds(5), verifier)) {
      client.initialize();
      var original = disposalRequest();
      assertThat(client.retireInitialGameplayLogin(original).disposition().name())
          .isEqualTo(disposition);
      assertThat(starts.get()).isEqualTo(1);
      assertThat(requests).containsExactly(original);
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"workload", "namespace"})
  void disposalRejectsWrongAccountPeerBeforeRequestRelease(String wrong) throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<RetireInitialGameplayLoginRequest> requests = new CopyOnWriteArrayList<>();
    Server server =
        startDisposalServer(
            wrong.equals("workload") ? pki.wrongWorkloadServer() : pki.wrongNamespaceServer(),
            starts,
            requests,
            0,
            Status.UNAVAILABLE,
            disposalResponse());
    try (var client =
        client(server, Duration.ofSeconds(5), mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      assertThatThrownBy(() -> client.retireInitialGameplayLogin(disposalRequest()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(starts.get()).isZero();
      assertThat(requests).isEmpty();
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void disposalRetryKeepsIdenticalRequestAndOneTotalDeadline(String code) throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<RetireInitialGameplayLoginRequest> requests = new CopyOnWriteArrayList<>();
    Server server =
        startDisposalServer(
            pki.accountServer(),
            starts,
            requests,
            1,
            Status.fromCode(Status.Code.valueOf(code)),
            disposalResponse());
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    List<Deadline> deadlines = new CopyOnWriteArrayList<>();
    var customizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            return stub.withInterceptors(
                new io.grpc.ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                      io.grpc.MethodDescriptor<ReqT, RespT> method,
                      io.grpc.CallOptions options,
                      io.grpc.Channel next) {
                    deadlines.add(options.getDeadline());
                    return next.newCall(method, options);
                  }
                });
          }
        };
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    try (var client =
        new CanonicalGameplayLoginAccountClient(
            endpoints,
            pki.clientProperties(temporaryDirectory),
            new GrpcChannelFactory(),
            customizer,
            "test",
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            verifier)) {
      client.initialize();
      var original = disposalRequest();
      client.retireInitialGameplayLogin(original);
      assertThat(starts.get()).isEqualTo(2);
      assertThat(requests).containsExactly(original, original);
      assertThat(deadlines).hasSize(2);
      assertThat(deadlines.getFirst() == deadlines.get(1)).isTrue();
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "PERMISSION_DENIED", "MALFORMED"})
  void disposalNeverRetriesBeyondBoundOrAcceptsMalformedReply(String outcome) throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<RetireInitialGameplayLoginRequest> requests = new CopyOnWriteArrayList<>();
    boolean malformed = outcome.equals("MALFORMED");
    Server server =
        startDisposalServer(
            pki.accountServer(),
            starts,
            requests,
            malformed ? 0 : 5,
            malformed ? Status.UNAVAILABLE : Status.fromCode(Status.Code.valueOf(outcome)),
            malformed
                ? disposalResponse().toBuilder()
                    .setCallerContextId(disposalRequest().getRequestId())
                    .build()
                : disposalResponse());
    try (var client =
        client(server, Duration.ofSeconds(5), mock(AccountAsymmetricJwtVerifier.class))) {
      client.initialize();
      if (malformed)
        assertThatThrownBy(() -> client.retireInitialGameplayLogin(disposalRequest()))
            .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
      else
        assertThatThrownBy(() -> client.retireInitialGameplayLogin(disposalRequest()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error -> {
                  assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.valueOf(outcome));
                  assertThat(error.getMessage()).doesNotContain("private-secret");
                });
      assertThat(starts.get()).isEqualTo(outcome.equals("UNAVAILABLE") ? 2 : 1);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void disposalInvalidRequestAndExpiredOrCancelledParentNeverReachTransport() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    List<RetireInitialGameplayLoginRequest> requests = new CopyOnWriteArrayList<>();
    Server server =
        startDisposalServer(
            pki.accountServer(), starts, requests, 0, Status.UNAVAILABLE, disposalResponse());
    var verifier = mock(AccountAsymmetricJwtVerifier.class);
    try (var client = client(server, Duration.ofSeconds(5), verifier)) {
      client.initialize();
      assertThatThrownBy(
              () ->
                  client.retireInitialGameplayLogin(
                      disposalRequest().toBuilder().clearCallerContextId().build()))
          .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
      var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
      try (var expired =
          io.grpc.Context.current()
              .withDeadline(Deadline.after(-1, TimeUnit.NANOSECONDS), scheduler)) {
        expired.run(
            () ->
                assertThatThrownBy(() -> client.retireInitialGameplayLogin(disposalRequest()))
                    .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error ->
                            assertThat(error.getStatus().getCode())
                                .isEqualTo(Status.Code.DEADLINE_EXCEEDED)));
      } finally {
        scheduler.shutdownNow();
      }
      try (var cancelled = io.grpc.Context.current().withCancellation()) {
        cancelled.cancel(null);
        cancelled.run(
            () ->
                assertThatThrownBy(() -> client.retireInitialGameplayLogin(disposalRequest()))
                    .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error ->
                            assertThat(error.getStatus().getCode())
                                .isEqualTo(Status.Code.CANCELLED)));
      }
      assertThat(starts.get()).isZero();
      assertThat(requests).isEmpty();
      verifyNoInteractions(verifier);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static RetireInitialGameplayLoginRequest disposalRequest() {
    return RetireInitialGameplayLoginRequest.newBuilder()
        .setRequestId(request().getRequestId())
        .setCallerContextId(request().getSourceContext().getCallerContextId())
        .build();
  }

  private static RetireInitialGameplayLoginResponse disposalResponse() {
    return RetireInitialGameplayLoginResponse.newBuilder()
        .setRequestId(disposalRequest().getRequestId())
        .setCallerContextId(disposalRequest().getCallerContextId())
        .setDisposalOperationId("abc56b29-78aa-4c18-b684-9763d665e4c8")
        .setDisposition(
            InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING)
        .build();
  }

  private static Server startDisposalServer(
      TestIdentity identity,
      AtomicInteger starts,
      List<RetireInitialGameplayLoginRequest> requests,
      int failures,
      Status status,
      RetireInitialGameplayLoginResponse response)
      throws Exception {
    var service =
        new AccountServiceGrpc.AccountServiceImplBase() {
          @Override
          public void retireInitialGameplayLogin(
              RetireInitialGameplayLoginRequest request,
              StreamObserver<RetireInitialGameplayLoginResponse> observer) {
            requests.add(request);
            if (!GAME_SESSION_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (requests.size() <= failures) {
              observer.onError(
                  status
                      .withDescription("private-secret-not-for-diagnostics")
                      .asRuntimeException());
              return;
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    var count =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            starts.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, count, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void assertDecision(
      CanonicalGameplayLoginAccountClient client,
      AuthenticateRequest request,
      java.util.function.BooleanSupplier custodyCurrent) {
    assertThatThrownBy(() -> client.authenticate(request, custodyCurrent))
        .isInstanceOfSatisfying(
            CanonicalGameplayLoginResult.CredentialDecisionException.class,
            decision ->
                assertThat(decision.code())
                    .isEqualTo(AuthenticationErrorCodes.INVALID_CREDENTIALS));
  }

  private static CanonicalGameplayLoginAccountClient client(
      Server server, Duration budget, AccountAsymmetricJwtVerifier verifier) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new CanonicalGameplayLoginAccountClient(
        endpoints,
        pki.clientProperties(temporaryDirectory),
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        "test",
        Clock.systemUTC(),
        budget,
        verifier);
  }

  private static Server startServer(
      TestIdentity identity,
      AtomicInteger starts,
      List<AuthenticateRequest> requests,
      int failures,
      Status status)
      throws Exception {
    var service =
        new AccountServiceGrpc.AccountServiceImplBase() {
          @Override
          public void authenticate(
              AuthenticateRequest request, StreamObserver<AuthenticateResponse> observer) {
            requests.add(request);
            if (!GAME_SESSION_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (requests.size() <= failures) {
              observer.onError(
                  status
                      .withDescription("private-secret-not-for-diagnostics")
                      .asRuntimeException());
              return;
            }
            observer.onNext(
                AuthenticateResponse.newBuilder()
                    .setError(
                        ErrorDetail.newBuilder()
                            .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                            .setMessage("Invalid credentials"))
                    .build());
            observer.onCompleted();
          }
        };
    var count =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            starts.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, count, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static AuthenticateRequest request() {
    return AuthenticateRequest.newBuilder()
        .setRequestId("1ee95a1e-83f2-4a63-a7ba-6288e246ac76")
        .setEmail("player@example.test")
        .setPassword("secret")
        .setSourceContext(
            GameplayLoginSourceContext.newBuilder()
                .setCallerContextId("91d13625-0e03-4e46-b82e-18f69f091436")
                .setCanonicalClientAddress("203.0.113.10")
                .setTransportClass(
                    GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
        .build();
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity accountServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSessionClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("canonical-login-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD canonical LOGIN test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("canonical-login-test-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-workload-account-server",
              WRONG_WORKLOAD_URI,
              true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-namespace-account-server",
              WRONG_NAMESPACE_URI,
              true),
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
      Path trustCa =
          writePem(
              directory.resolve("canonical-login-client-trust-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(trustCa.toString());
      properties.setPlaintext(false);
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      return new TestIdentity(
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      List<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool timed out");
      }
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }
  }
}
