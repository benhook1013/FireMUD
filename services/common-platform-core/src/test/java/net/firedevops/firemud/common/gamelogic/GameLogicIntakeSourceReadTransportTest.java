package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual loopback mTLS transport fixtures; these bytes do not establish creator provenance. */
class GameLogicIntakeSourceReadTransportTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_SERVER_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final String GD_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  @TempDir static Path tempDirectory;
  private static IntakeTestPki pki;
  private Server server;
  private GrpcGameLogicIntakeSourceReadClient client;
  private GameplayRuleSourceReadClient sourceClient;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile boolean stall;
  private volatile boolean substitute;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        IntakeTestPki.create(
            tempDirectory,
            "terminal",
            "FireMUD terminal-read test CA",
            IntakeTestPki.IdentitySpec.server("account-server", ACCOUNT_URI),
            IntakeTestPki.IdentitySpec.server("wrong-server", WRONG_SERVER_URI),
            IntakeTestPki.IdentitySpec.client("game-design-client", GD_URI));
  }

  @AfterEach
  void close() throws Exception {
    if (sourceClient != null) {
      sourceClient.close();
      sourceClient = null;
    }
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
  void roundTripsBothClosedProofsAndRejectsChangedEchoAndUnknownOrAbsentProof() {
    for (var request : requests()) {
      var wire = GameLogicIntakeSourceReadProtoCodec.toRequest(request);
      assertThat(GameLogicIntakeSourceReadProtoCodec.fromRequest(wire)).isEqualTo(request);
      var response = GameLogicIntakeSourceReadProtoCodec.toResponse(request);
      assertThat(GameLogicIntakeSourceReadProtoCodec.fromResponse(request, response).request())
          .isEqualTo(request);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromResponse(
                      request,
                      response.toBuilder()
                          .setRequest(
                              wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromResponse(
                      request, response.toBuilder().setPermitted(false).build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder().clearAuthorizationProof().build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder()
                          .setAuthorizationProofDigest("sha256:" + "0".repeat(64))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder().setPurpose("PUBLICATION").build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder()
                          .setUnknownFields(
                              com.google.protobuf.UnknownFieldSet.newBuilder()
                                  .addField(
                                      99,
                                      com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                          .addVarint(1)
                                          .build())
                                  .build())
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void pinsAccountServerAndAuthenticatedGdClientForBothMethods() throws Exception {
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    for (var request : requests()) assertThat(client.read(request).request()).isEqualTo(request);
    assertThat(calls).hasValue(2);
    close();
    start(pki.wrongServer());
    client = newClient(pki.client());
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(calls).hasValue(2);
  }

  @Test
  void gameplayRuleSourceClientReportsUnavailableBeforeInitializationAndAfterClose()
      throws Exception {
    start(pki.server());
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    sourceClient =
        new GameplayRuleSourceReadClient(
            endpoints,
            pki.clientProperties(tempDirectory, pki.client()),
            new GrpcChannelFactory(),
            NAMESPACE);
    var authorization =
        ((GameplayRuleSourceReadEvidence.Finalized) requests().getLast().proof()).authorization();
    var request =
        GameplayRuleSourceReadEvidence.Request.forFinalizedIntake(NAMESPACE, authorization);

    assertUnavailable(() -> sourceClient.read(request));
    sourceClient.init();
    sourceClient.close();
    assertUnavailable(() -> sourceClient.read(request));
    assertThat(calls).hasValue(0);
  }

  @Test
  void rejectsNamespaceAmbientSqlMissingTlsAndSubstitutedResponse() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeSourceReadClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeSourceReadClient(
                    endpoints,
                    new CommonGrpcClientProperties(),
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    var original = requests().getFirst();
    var otherNamespace =
        new GameLogicIntakeSourceReadEvidence.Request(
            1,
            "other",
            UUID.randomUUID(),
            "spiffe://firemud/ns/other/sa/game-logic-service",
            original.purpose(),
            requests().getLast().proof());
    assertThatThrownBy(() -> client.read(otherNamespace))
        .isInstanceOf(IllegalArgumentException.class);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.read(original)).isInstanceOf(IllegalStateException.class);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
    assertThat(calls).hasValue(0);
    substitute = true;
    assertThatThrownBy(() -> client.read(original)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void boundsUnavailableReadByDeadline() throws Exception {
    stall = true;
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
  }

  @Test
  void rejectsOtherAuthenticatedClientWorkload() throws Exception {
    start(pki.server());
    var wrongClient =
        IntakeTestPki.issueIdentity(
            tempDirectory,
            pki.caKeyStore(),
            pki.caCertificateFile(),
            "wrong-source-client",
            ACCOUNT_URI,
            false);
    client = newClient(wrongClient);
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThat(calls).hasValue(0);
  }

  @Test
  void transportsResponsesOverFourMiBWithinBothExplicitWireBudgets() throws Exception {
    // Deliberately synthetic owner evidence exercises envelope overhead, not creator authority.
    var original =
        ((GameplayRuleSourceReadEvidence.Finalized) requests().getLast().proof()).authorization();
    int padding = 4 * 1024 * 1024 - 16 - original.canonicalBytes().length + 1;
    var large =
        new GameLogicIntakeAuthorizationBinding(
            original.operationId(),
            original.fenceId(),
            original.intakeRequestId(),
            original.actorAccountId(),
            original.source(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    original.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[padding])));
    assertThat(large.canonicalBytes().length).isEqualTo(4 * 1024 * 1024 - 16);
    var permission =
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            "spiffe://firemud/ns/test/sa/game-logic-service",
            GameLogicIntakeSourceReadScope.PURPOSE,
            new GameplayRuleSourceReadEvidence.Finalized(large));
    assertThat(GameLogicIntakeSourceReadProtoCodec.toResponse(permission).getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES);
    start(pki.server());
    client = newClient(pki.client());
    client.init();
    assertThat(client.read(permission).request().proof().canonicalBytes())
        .containsExactly(large.canonicalBytes());
    close();

    var sourceRequest = GameplayRuleSourceReadEvidence.Request.forFinalizedIntake(NAMESPACE, large);
    var sourceEvidence = new GameplayRuleSourceReadEvidence(sourceRequest, large.source());
    assertThat(GameplayRuleSourceReadGrpcCodec.toResponse(sourceEvidence).getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES);
    var gdServer =
        IntakeTestPki.issueIdentity(
            tempDirectory,
            pki.caKeyStore(),
            pki.caCertificateFile(),
            "source-gd-server",
            GD_URI,
            true);
    var glClient =
        IntakeTestPki.issueIdentity(
            tempDirectory,
            pki.caKeyStore(),
            pki.caCertificateFile(),
            "source-gl-client",
            "spiffe://firemud/ns/test/sa/game-logic-service",
            false);
    var sourceService =
        new net.firedevops.firemud.gamedesign.v1.GameDesignGameplayRuleSourceReadServiceGrpc
            .GameDesignGameplayRuleSourceReadServiceImplBase() {
          @Override
          public void readSelectedGameplayRuleSource(
              net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest wire,
              StreamObserver<
                      net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse>
                  observer) {
            assertThat(GrpcPeerIdentity.current().uri())
                .isEqualTo("spiffe://firemud/ns/test/sa/game-logic-service");
            var request = GameplayRuleSourceReadGrpcCodec.fromRequest(wire);
            observer.onNext(
                GameplayRuleSourceReadGrpcCodec.toResponse(
                    new GameplayRuleSourceReadEvidence(request, large.source())));
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(gdServer.privateKey(), gdServer.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(sourceService, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    try (var sourceClient =
        new GameplayRuleSourceReadClient(
            endpoints,
            pki.clientProperties(tempDirectory, glClient),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      sourceClient.init();
      var result = sourceClient.read(sourceRequest);
      assertThat(result.request().proof().canonicalBytes()).containsExactly(large.canonicalBytes());
      assertThat(result.source().canonicalBytes()).containsExactly(large.source().canonicalBytes());
    }
  }

  @Test
  void rejectsEnvelopesOverExplicitWireBudgetBeforeCanonicalDecode() {
    var request = requests().getFirst();
    var oversizedAccount =
        GameLogicIntakeSourceReadProtoCodec.toRequest(request).toBuilder()
            .setPurpose("x".repeat(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES))
            .build();
    assertThatThrownBy(() -> GameLogicIntakeSourceReadProtoCodec.fromRequest(oversizedAccount))
        .isInstanceOf(IllegalArgumentException.class);
    var sourceRequest =
        GameplayRuleSourceReadEvidence.Request.forAccountSourceScope(
            NAMESPACE, ((GameplayRuleSourceReadEvidence.Preliminary) request.proof()).scope());
    var oversizedGd =
        GameplayRuleSourceReadGrpcCodec.toRequest(sourceRequest).toBuilder()
            .setPurpose("x".repeat(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES))
            .build();
    assertThatThrownBy(() -> GameplayRuleSourceReadGrpcCodec.fromRequest(oversizedGd))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void start(IntakeTestPki.Identity identity) throws Exception {
    var service =
        new AccountGameLogicIntakeSourceReadServiceGrpc
            .AccountGameLogicIntakeSourceReadServiceImplBase() {
          @Override
          public void readSourceScope(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
            respond(wire, observer, true);
          }

          @Override
          public void readFinalizedIntake(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
            respond(wire, observer, false);
          }

          private void respond(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer,
              boolean preliminary) {
            if (!GD_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            calls.incrementAndGet();
            assertThat(io.grpc.Context.current().getDeadline()).isNotNull();
            var request = GameLogicIntakeSourceReadProtoCodec.fromRequest(wire);
            assertThat(request.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary)
                .isEqualTo(preliminary);
            if (stall) return;
            var response = GameLogicIntakeSourceReadProtoCodec.toResponse(request);
            if (substitute)
              response =
                  response.toBuilder()
                      .setRequest(wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()))
                      .build();
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcGameLogicIntakeSourceReadClient newClient(IntakeTestPki.Identity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new GrpcGameLogicIntakeSourceReadClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static List<GameLogicIntakeSourceReadEvidence.Request> requests() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSourceRevision.upsertPayload(
                        new GameplayRuleManifest.AdmissionTag("gameplay")))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source = selectedSource(binding);
    var actor = UUID.randomUUID();
    var scope =
        new GameLogicIntakeSourceReadScope(
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            binding,
            ACCOUNT_URI,
            GameLogicIntakeSourceReadScope.PURPOSE);
    var auth =
        new GameLogicIntakeAuthorizationBinding(
            scope.operationId(),
            scope.fenceId(),
            scope.intakeRequestId(),
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
    return List.of(
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            ACCOUNT_URI,
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Preliminary(scope)),
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            "spiffe://firemud/ns/test/sa/game-logic-service",
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Finalized(auth)));
  }

  private static GameplayRuleSelectedSource selectedSource(DraftCommitBinding binding) {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    var families =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
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
                        "0",
                        "revisionId",
                        binding.revisions().getFirst().revisionId().toString())))));
  }

  private static void assertUnavailable(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> {
              assertThat(Status.fromThrowable(failure).getCode())
                  .isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(Status.fromThrowable(failure).getDescription()).isNull();
              assertThat(failure.getCause()).isNull();
            });
  }
}
