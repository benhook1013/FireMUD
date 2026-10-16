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
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual loopback mTLS proof for the unregistered Account HELD authorization read. */
class GameLogicIntakeAuthorizationReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_LOGIC_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final int MAX_BINDING_BYTES = 4 * 1024 * 1024;

  @TempDir static Path tempDirectory;
  private static GameLogicIntakeSourceReadTransportTest.TestPki pki;
  private static GameLogicIntakeSourceReadTransportTest.TestIdentity gameLogicClientIdentity;
  private Server server;
  private GameLogicIntakeAuthorizationReadClient client;
  private GameLogicIntakeAuthorizationReadEvidence.Request expectedRequest;
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeAll
  static void certificates() throws Exception {
    pki = GameLogicIntakeSourceReadTransportTest.TestPki.create(tempDirectory);
    gameLogicClientIdentity = issueIdentity("game-logic-client", GAME_LOGIC_URI);
  }

  @AfterEach
  void closeTransport() throws Exception {
    close();
  }

  @Test
  void readsLargeExactHeldBindingOverPinnedLoopbackMtls() throws Exception {
    var base = authorizationBinding();
    int padding = MAX_BINDING_BYTES - base.canonicalBytes().length;
    var large =
        new GameLogicIntakeAuthorizationBinding(
            base.operationId(),
            base.fenceId(),
            base.intakeRequestId(),
            base.actorAccountId(),
            base.source(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    base.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[padding])));
    assertThat(large.canonicalBytes()).hasSize(MAX_BINDING_BYTES - 1);
    expectedRequest = GameLogicIntakeAuthorizationReadEvidence.Request.create(NAMESPACE, large);
    var wireRequest = GameLogicIntakeAuthorizationReadGrpcCodec.toRequest(expectedRequest);
    var wireResponse = GameLogicIntakeAuthorizationReadGrpcCodec.toHeldResponse(expectedRequest);
    assertThat(wireRequest.getSerializedSize()).isGreaterThan(4 * 1024 * 1024);
    assertThat(wireResponse.getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES);

    start(pki.gameLogicServer());
    client = newClient(gameLogicClient());
    client.init();
    var actual = client.read(expectedRequest);
    assertThat(actual.request()).isEqualTo(expectedRequest);
    assertThat(actual.request().binding().canonicalBytes()).containsExactly(large.canonicalBytes());
    assertThat(calls).hasValue(1);
  }

  @Test
  void pinsAccountServerAndRejectsOtherAuthenticatedCaller() throws Exception {
    start(pki.wrongServer());
    client = newClient(gameLogicClient());
    client.init();
    var request =
        GameLogicIntakeAuthorizationReadEvidence.Request.create(NAMESPACE, authorizationBinding());
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(StatusRuntimeException.class);
    assertThat(calls).hasValue(0);
    close();

    start(pki.gameLogicServer());
    client = newClient(otherCaller());
    client.init();
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThat(calls).hasValue(0);
  }

  private void start(GameLogicIntakeSourceReadTransportTest.TestIdentity identity)
      throws Exception {
    var service =
        new AccountGameLogicIntakeAuthorizationReadServiceGrpc
            .AccountGameLogicIntakeAuthorizationReadServiceImplBase() {
          @Override
          public void readHeldGameLogicIntakeAuthorization(
              ReadHeldGameLogicIntakeAuthorizationRequest wire,
              StreamObserver<ReadHeldGameLogicIntakeAuthorizationResponse> observer) {
            if (!GAME_LOGIC_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            calls.incrementAndGet();
            var decoded = GameLogicIntakeAuthorizationReadGrpcCodec.fromRequest(wire);
            assertThat(decoded).isEqualTo(expectedRequest);
            observer.onNext(GameLogicIntakeAuthorizationReadGrpcCodec.toHeldResponse(decoded));
            observer.onCompleted();
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
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameLogicIntakeAuthorizationReadClient newClient(
      GameLogicIntakeSourceReadTransportTest.TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new GameLogicIntakeAuthorizationReadClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private GameLogicIntakeSourceReadTransportTest.TestIdentity gameLogicClient() throws Exception {
    return gameLogicClientIdentity;
  }

  private static GameLogicIntakeSourceReadTransportTest.TestIdentity issueIdentity(
      String alias, String workloadUri) throws Exception {
    return GameLogicIntakeSourceReadTransportTest.TestPki.issueIdentity(
        tempDirectory,
        tempDirectory.resolve("terminal-test-ca.p12"),
        tempDirectory.resolve("terminal-test-ca.crt"),
        alias,
        workloadUri,
        false);
  }

  private GameLogicIntakeSourceReadTransportTest.TestIdentity otherCaller() throws Exception {
    return GameLogicIntakeSourceReadTransportTest.TestPki.issueIdentity(
        tempDirectory,
        tempDirectory.resolve("terminal-test-ca.p12"),
        tempDirectory.resolve("terminal-test-ca.crt"),
        "other-client",
        "spiffe://firemud/ns/test/sa/game-design-service",
        false);
  }

  private static GameLogicIntakeAuthorizationBinding authorizationBinding() {
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-1",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSourceRevision.upsertPayload(definition))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    version.toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var families =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
    var revision = binding.revisions().getFirst();
    var source =
        new GameplayRuleSelectedSource(
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
    UUID actor = UUID.randomUUID();
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
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

  private void close() throws Exception {
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
}
