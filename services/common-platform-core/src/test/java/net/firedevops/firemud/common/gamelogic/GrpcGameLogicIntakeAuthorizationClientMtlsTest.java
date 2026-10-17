package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Metadata;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationServiceGrpc;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual loopback mTLS and 24 MiB wire proof using synthetic authoring evidence. */
class GrpcGameLogicIntakeAuthorizationClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GD_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String CREDENTIAL = "unchanged.creator.credential";

  @TempDir static Path tempDirectory;
  private static IntakeTestPki pki;
  private Server server;
  private GrpcGameLogicIntakeAuthorizationClient client;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> callerPeer = new AtomicReference<>();
  private final AtomicReference<String> observedCredential = new AtomicReference<>();
  private final AtomicReference<String> observedMethod = new AtomicReference<>();
  private volatile GameLogicIntakeAuthorizationResponse authorizeResponseOverride;
  private volatile boolean stall;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        IntakeTestPki.create(
            tempDirectory,
            "terminal",
            "FireMUD terminal-read test CA",
            IntakeTestPki.IdentitySpec.server("account-server", ACCOUNT_URI),
            IntakeTestPki.IdentitySpec.server(
                "wrong-server", "spiffe://firemud/ns/test/sa/game-logic-service"),
            IntakeTestPki.IdentitySpec.client("game-design-client", GD_URI));
  }

  @AfterEach
  void closeTransport() throws Exception {
    TransactionSynchronizationManager.clear();
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void callsAuthorizeRecoverAndAbortWithExactPeerAndProtectedCredential() throws Exception {
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    var request = request(selected(1));
    var authorized = client.authorize(request, CREDENTIAL);
    assertThat(authorized.outcome())
        .isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED);
    assertThat(authorized.authorization().orElseThrow().intakeRequestId())
        .isEqualTo(request.intakeRequestId());
    var recovered = client.recover(request, CREDENTIAL);
    assertThat(recovered.outcome())
        .isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED);
    var aborted = client.abort(request, CREDENTIAL);
    assertThat(aborted.outcome()).isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED);
    assertThat(calls).hasValue(3);
    assertThat(callerPeer).hasValue(GD_URI);
    assertThat(observedCredential).hasValue(CREDENTIAL);
    assertThat(observedMethod.get()).contains("AbortIntake");
  }

  @Test
  void pinsAccountServerAndRejectsWrongServerAndWrongAuthenticatedCaller() throws Exception {
    start(pki.wrongServer());
    client = newClient(pki.client());
    client.init();
    assertThatThrownBy(() -> client.authorize(request(selected(1)), CREDENTIAL))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(calls).hasValue(0);
    closeTransport();

    start(pki.server());
    var wrongClient =
        IntakeTestPki.issueIdentity(
            tempDirectory,
            pki.caKeyStore(),
            pki.caCertificateFile(),
            "wrong-game-design-client",
            ACCOUNT_URI,
            false);
    client = newClient(wrongClient);
    client.init();
    assertThatThrownBy(() -> client.authorize(request(selected(1)), CREDENTIAL))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThat(calls).hasValue(0);
  }

  @Test
  void rejectsWrongNamespaceAmbientSqlAndUnavailableDeadline() throws Exception {
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    var original = request(selected(1));
    var wrongNamespace =
        new GameLogicIntakeAuthorizationEvidence.Request(
            1, "other", UUID.randomUUID(), UUID.randomUUID(), selected(1).canonicalBytes());
    assertThatThrownBy(() -> client.authorize(wrongNamespace, CREDENTIAL))
        .isInstanceOf(IllegalArgumentException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.authorize(original, CREDENTIAL))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertThat(calls).hasValue(0);
    closeTransport();

    stall = true;
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    assertThatThrownBy(() -> client.authorize(original, CREDENTIAL))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
  }

  @Test
  void refusesPlaintextAndClasspathOrMissingTlsMaterial() {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeAuthorizationClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeAuthorizationClient(
                    endpoints,
                    new CommonGrpcClientProperties(),
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    var classpathMaterial = new CommonGrpcClientProperties();
    classpathMaterial.setCertChain("classpath:account.crt");
    classpathMaterial.setPrivateKey("classpath:account.key");
    classpathMaterial.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeAuthorizationClient(
                    endpoints, classpathMaterial, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sendsLargeSelectedAuthorizationEchoWithinTwentyFourMiBInsteadOfDefaultLimit()
      throws Exception {
    var largeRequest = request(selected(4_500));
    var response = response(largeRequest, "AuthorizeIntake");
    assertThat(response.getSerializedSize()).isGreaterThan(4 * 1024 * 1024);
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES);
    authorizeResponseOverride = response;
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    var actual = client.authorize(largeRequest, CREDENTIAL);
    assertThat(actual.authorization().orElseThrow().canonicalBytes())
        .containsExactly(
            GameLogicIntakeAuthorizationGrpcCodec.fromResponse(largeRequest, response)
                .authorization()
                .orElseThrow()
                .canonicalBytes());
  }

  @Test
  void rejectsMalformedCreatorCredentialsBeforeRpcAndSanitizesMalformedEvidence() throws Exception {
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    var request = request(selected(1));
    for (String malformed : List.of(" ", "x".repeat(16385))) {
      assertThatThrownBy(() -> client.authorize(request, malformed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid Game Logic intake credential metadata");
    }
    assertThat(calls).hasValue(0);

    String sensitive = "sensitive-malformed-account-evidence";
    authorizeResponseOverride =
        response(request, "AuthorizeIntake").toBuilder()
            .setFinalizedIntakeAuthorization(ByteString.copyFromUtf8(sensitive))
            .build();
    assertThatThrownBy(() -> client.authorize(request, CREDENTIAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account returned invalid Game Logic intake authorization evidence")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getMessage()).doesNotContain(sensitive);
            });
    assertThat(calls).hasValue(1);
  }

  private void start(IntakeTestPki.Identity identity) throws Exception {
    var service =
        new AccountGameLogicIntakeAuthorizationServiceGrpc
            .AccountGameLogicIntakeAuthorizationServiceImplBase() {
          @Override
          public void authorizeIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            respond(request, observer, "AuthorizeIntake");
          }

          @Override
          public void recoverIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            respond(request, observer, "RecoverIntake");
          }

          @Override
          public void abortIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            respond(request, observer, "AbortIntake");
          }

          private void respond(
              GameLogicIntakeAuthorizationRequest wire,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer,
              String method) {
            calls.incrementAndGet();
            observedMethod.set(method);
            callerPeer.set(GrpcPeerIdentity.current().uri());
            observedCredential.set(
                AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY.get().value());
            assertThat(Context.current().getDeadline()).isNotNull();
            if (stall) return;
            var request = GameLogicIntakeAuthorizationGrpcCodec.fromRequest(wire);
            var override = "AuthorizeIntake".equals(method) ? authorizeResponseOverride : null;
            if (override != null) {
              assertThat(override.getRequest()).isEqualTo(wire);
              observer.onNext(override);
            } else {
              observer.onNext(response(request, method));
            }
            observer.onCompleted();
          }
        };
    var credentialInterceptor =
        new io.grpc.ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call,
              Metadata headers,
              io.grpc.ServerCallHandler<ReqT, RespT> next) {
            if (!GD_URI.equals(GrpcPeerIdentity.current().uri())) {
              call.close(Status.PERMISSION_DENIED, new Metadata());
              return new io.grpc.ServerCall.Listener<>() {};
            }
            try {
              var credential =
                  AccountGameLogicIntakeAuthorizationCredentials.readAuthenticated(
                      headers, call.getMethodDescriptor().getFullMethodName());
              return io.grpc.Contexts.interceptCall(
                  Context.current()
                      .withValue(
                          AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY, credential),
                  call,
                  headers,
                  next);
            } catch (IllegalArgumentException malformed) {
              call.close(Status.UNAUTHENTICATED, new Metadata());
              return new io.grpc.ServerCall.Listener<>() {};
            }
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, credentialInterceptor, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcGameLogicIntakeAuthorizationClient newClient(IntakeTestPki.Identity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new GrpcGameLogicIntakeAuthorizationClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static GameLogicIntakeAuthorizationEvidence.Request request(DraftCommitBinding selected) {
    return GameLogicIntakeAuthorizationEvidence.Request.create(
        NAMESPACE, UUID.randomUUID(), selected);
  }

  private static GameLogicIntakeAuthorizationResponse response(
      GameLogicIntakeAuthorizationEvidence.Request request, String method) {
    return GameLogicIntakeAuthorizationGrpcCodec.toResponse(result(request, method));
  }

  private static GameLogicIntakeAuthorizationEvidence.Result result(
      GameLogicIntakeAuthorizationEvidence.Request request, String method) {
    if ("AuthorizeIntake".equals(method))
      return GameLogicIntakeAuthorizationEvidence.Result.authorized(
          request, authorization(request));
    var scope = scope(request);
    return new GameLogicIntakeAuthorizationEvidence.Result(
        request,
        "AbortIntake".equals(method)
            ? GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED
            : GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED,
        java.util.Optional.of(scope),
        java.util.Optional.empty());
  }

  private static GameLogicIntakeAuthorizationBinding authorization(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    UUID actor = UUID.randomUUID();
    var selected = request.selected();
    var source = selectedSource(selected);
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        request.intakeRequestId(),
        actor,
        source,
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private static GameLogicIntakeSourceReadScope scope(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    return new GameLogicIntakeSourceReadScope(
        request.targetNamespace(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        request.intakeRequestId(),
        UUID.randomUUID(),
        request.selected(),
        "spiffe://firemud/ns/" + request.targetNamespace() + "/sa/account-service",
        GameLogicIntakeSourceReadScope.PURPOSE);
  }

  private static DraftCommitBinding selected(int revisionCount) {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>(revisionCount);
    for (int index = 0; index < revisionCount; index++)
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              GameplayRuleSourceRevision.upsertPayload(definition)));
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-1",
        revisions,
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                version.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleSelectedSource selectedSource(DraftCommitBinding binding) {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    var families =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
    var revision = binding.revisions().getLast();
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                binding.canonicalJson(),
                "bindingDigest",
                binding.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                List.of(
                    Map.of(
                        "family",
                        definition.family().name(),
                        "definitionJson",
                        GameplayRuleManifest.canonical(definition),
                        "sourceBindingJson",
                        binding.canonicalJson(),
                        "sourceBindingDigest",
                        binding.digest(),
                        "revisionOrder",
                        revision.revisionOrder(),
                        "revisionId",
                        revision.revisionId().toString())))));
  }
}
