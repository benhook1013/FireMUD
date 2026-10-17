package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeRequest;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeResponse;
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

/** Structural and actual loopback-mTLS proof for the closed Account settlement transport. */
class AccountGameLogicIntakeSettlementReadGrpcTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_SERVER_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_SERVER_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final String GAME_DESIGN_CLIENT_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final int LARGE_SOURCE_ENTRIES = 1800;

  @TempDir static Path tempDirectory;
  private static IntakeTestPki pki;
  private Server server;
  private GrpcAccountGameLogicIntakeSettlementReadClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;

  @BeforeAll
  static void createTrustedCertificates() throws Exception {
    pki =
        IntakeTestPki.create(
            tempDirectory,
            "terminal",
            "FireMUD terminal-read test CA",
            IntakeTestPki.IdentitySpec.server("account-server", ACCOUNT_SERVER_URI),
            IntakeTestPki.IdentitySpec.server("wrong-server", WRONG_SERVER_URI),
            IntakeTestPki.IdentitySpec.client("game-design-client", GAME_DESIGN_CLIENT_URI));
  }

  @AfterEach
  void stopTransport() throws Exception {
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
  void roundTripsExactReceiptsAndNewCorrelationRetries() {
    for (var outcome : Outcome.values()) {
      var evidence = evidence("test", 1, outcome);
      var originalBytes = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(
          originalBytes, "account-game-logic-intake-settlement/v1");
      DraftAuthorizationFenceBinding.frame(
          originalBytes, evidence.receipt().terminal().canonicalBytes());
      assertThat(evidence.receipt().canonicalBytes()).isEqualTo(originalBytes.toByteArray());
      var request =
          AccountGameLogicIntakeSettlementProtoCodec.fromRequest(
              AccountGameLogicIntakeSettlementProtoCodec.toRequest(evidence.request()));
      assertThat(request).isEqualTo(evidence.request());
      assertThat(request.hashCode()).isEqualTo(evidence.request().hashCode());
      var wire = AccountGameLogicIntakeSettlementProtoCodec.toResponse(evidence);
      assertThat(
              AccountGameLogicIntakeSettlementProtoCodec.fromResponse(request, wire)
                  .receipt()
                  .canonicalBytes())
          .isEqualTo(evidence.receipt().canonicalBytes());
      var retry =
          AccountGameLogicIntakeSettlementReadEvidence.Request.create("test", request.binding());
      var replay = new AccountGameLogicIntakeSettlementReadEvidence(retry, evidence.receipt());
      assertThat(
              AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                      retry, AccountGameLogicIntakeSettlementProtoCodec.toResponse(replay))
                  .receipt()
                  .digest())
          .isEqualTo(evidence.receipt().digest());
      assertThatThrownBy(() -> AccountGameLogicIntakeSettlementProtoCodec.fromResponse(retry, wire))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsChangedEvidenceOversizeTrailingAndNoncanonicalReceipts() {
    var evidence = evidence("test", 1, Outcome.RETAINED);
    var wire = AccountGameLogicIntakeSettlementProtoCodec.toResponse(evidence);
    var changed = evidence("test", 1, Outcome.RETAINED);
    assertThatThrownBy(
            () ->
                new AccountGameLogicIntakeSettlementReadEvidence(
                    evidence.request(), changed.receipt()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameLogicIntakeSettlementReadEvidence(
                    AccountGameLogicIntakeSettlementReadEvidence.Request.create(
                        "other", evidence.request().binding()),
                    evidence.receipt()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    wire.toBuilder()
                        .setSettlementReceiptDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromRequest(
                    wire.getRequest().toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    wire.toBuilder()
                        .setRequest(wire.getRequest().toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    wire.toBuilder()
                        .setRequest(wire.getRequest().toBuilder().setTargetNamespace("other"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    wire.toBuilder()
                        .setOriginalSettlementReceipt(
                            ByteString.copyFrom(changed.receipt().canonicalBytes()))
                        .setSettlementReceiptDigest(changed.receipt().digest())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(), wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromRequest(
                    wire.getRequest().toBuilder().setIntakeAuthorizationDigest("changed").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromRequest(
                    wire.getRequest().toBuilder()
                        .setOriginalIntakeAuthorization(ByteString.copyFrom(new byte[4194305]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] trailing =
        java.util.Arrays.copyOf(
            evidence.receipt().canonicalBytes(), evidence.receipt().canonicalBytes().length + 1);
    assertThatThrownBy(() -> AccountGameLogicIntakeSettlementEvidence.fromStored(trailing))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] wrongSchema = evidence.receipt().canonicalBytes();
    wrongSchema[Integer.BYTES] ^= 1;
    assertThatThrownBy(() -> AccountGameLogicIntakeSettlementEvidence.fromStored(wrongSchema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    wire.toBuilder()
                        .setOriginalSettlementReceipt(
                            ByteString.copyFrom(
                                new byte
                                    [AccountGameLogicIntakeSettlementEvidence.MAX_CANONICAL_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeSettlementProtoCodec.fromResponse(
                    evidence.request(),
                    net.firedevops.firemud.account.v1.SettleGameLogicIntakeResponse
                        .getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresFileBackedMtlsConfiguredNamespaceAndNoAmbientSql() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcAccountGameLogicIntakeSettlementReadClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    client =
        new GrpcAccountGameLogicIntakeSettlementReadClient(
            endpoints,
            pki.clientProperties(tempDirectory, pki.client()),
            new GrpcChannelFactory(),
            NAMESPACE);
    var evidence = evidence("test", 1, Outcome.ABORTED);
    assertThatThrownBy(() -> client.read(evidence.request()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                client.read(
                    AccountGameLogicIntakeSettlementReadEvidence.Request.create(
                        "other", evidence.request().binding())))
        .isInstanceOf(IllegalArgumentException.class);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.read(evidence.request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside SQL");
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> client.read(evidence.request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside SQL");
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    client.close();
    assertThatThrownBy(() -> client.init()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void actualMtlsCarriesReceiptAboveFourMiBAndRejectsSubstitutedServerBeforeDispatch()
      throws Exception {
    var large = evidence("test", LARGE_SOURCE_ENTRIES, Outcome.RETAINED);
    assertThat(large.receipt().canonicalBytes().length).isGreaterThan(4 * 1024 * 1024);
    startServer(pki.server(), large);
    client = newClient(server, pki.client());
    client.init();
    var actual = client.read(large.request());
    assertThat(actual.receipt().canonicalBytes()).isEqualTo(large.receipt().canonicalBytes());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
    stopTransport();
    startServer(pki.wrongServer(), large);
    client = newClient(server, pki.client());
    client.init();
    assertThatThrownBy(() -> client.read(large.request()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  @Test
  void uninitializedAndClosedLifecycleFailuresAreSanitizedUnavailableWithoutRpc() throws Exception {
    var evidence = evidence(NAMESPACE, 1, Outcome.RETAINED);
    startServer(pki.server(), evidence);
    client = newClient(server, pki.client());

    assertSanitizedUnavailable(() -> client.read(evidence.request()));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);

    client.init();
    var closedClient = client;
    client.close();
    client = null;
    assertSanitizedUnavailable(() -> closedClient.read(evidence.request()));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  private enum Outcome {
    RETAINED,
    ABORTED
  }

  private void startServer(
      IntakeTestPki.Identity identity, AccountGameLogicIntakeSettlementReadEvidence evidence)
      throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    var service =
        new AccountGameLogicIntakeSettlementServiceGrpc
            .AccountGameLogicIntakeSettlementServiceImplBase() {
          @Override
          public void settleGameLogicIntake(
              SettleGameLogicIntakeRequest wire,
              StreamObserver<SettleGameLogicIntakeResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_DESIGN_CLIENT_URI);
            var request = AccountGameLogicIntakeSettlementProtoCodec.fromRequest(wire);
            var response =
                AccountGameLogicIntakeSettlementProtoCodec.toResponse(
                    new AccountGameLogicIntakeSettlementReadEvidence(request, evidence.receipt()));
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    ServerInterceptor countHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            headers.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(AccountGameLogicIntakeSettlementEvidence.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcAccountGameLogicIntakeSettlementReadClient newClient(
      Server target, IntakeTestPki.Identity local) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + target.getPort());
    return new GrpcAccountGameLogicIntakeSettlementReadClient(
        endpoints, pki.clientProperties(tempDirectory, local), new GrpcChannelFactory(), NAMESPACE);
  }

  private static void assertSanitizedUnavailable(ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> {
              var status = Status.fromThrowable(failure);
              assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(status.getDescription()).isNull();
              assertThat(failure.getCause()).isNull();
            });
  }

  /** These canonical bytes are deliberately synthetic transport evidence, not owner provenance. */
  private static AccountGameLogicIntakeSettlementReadEvidence evidence(
      String namespace, int entryCount, Outcome outcome) {
    var tenant = UUID.randomUUID();
    var version = UUID.randomUUID();
    var requestId = UUID.randomUUID();
    var commitId = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    var definitions = new ArrayList<GameplayRuleManifest.Definition>();
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var revisionIds = new ArrayList<UUID>();
    for (int index = 0; index < entryCount; index++) {
      var definition =
          new GameplayRuleManifest.AdmissionTag(
              String.format(java.util.Locale.ROOT, "tag-%05d", index));
      definitions.add(definition);
      var revisionId = UUID.randomUUID();
      revisionIds.add(revisionId);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              "0",
              revisionId,
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              GameplayRuleSourceRevision.upsertPayload(definition)));
    }
    var selectedBinding =
        DraftCommitBinding.create(
            target,
            requestId,
            commitId,
            "genesis",
            List.of(revisions.getFirst()),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    version.toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var definitionsByFamily =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values())
      definitionsByFamily.put(family, new ArrayList<>());
    definitionsByFamily.get(GameplayRuleManifest.Family.ADMISSION_TAGS).addAll(definitions);
    var manifest = new GameplayRuleManifest(definitionsByFamily);
    var entries = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < entryCount; index++) {
      var definition = definitions.get(index);
      var entryBinding =
          DraftCommitBinding.create(
              target,
              requestId,
              UUID.randomUUID(),
              "genesis",
              List.of(revisions.get(index)),
              List.of(
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                      "GAMEPLAY_RULE_SET",
                      version.toString(),
                      "GAMEPLAY_RULE_SET",
                      "effective",
                      Integer.toString(index))));
      entries.add(
          Map.of(
              "family", definition.family().name(),
              "definitionJson", GameplayRuleManifest.canonical(definition),
              "sourceBindingJson", entryBinding.canonicalJson(),
              "sourceBindingDigest", entryBinding.digest(),
              "revisionOrder", "0",
              "revisionId", revisionIds.get(index).toString()));
    }
    String snapshot =
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                selectedBinding.canonicalJson(),
                "bindingDigest",
                selectedBinding.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                entries));
    var source = new GameplayRuleSelectedSource(snapshot);
    var accountEvidence =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            UUID.randomUUID().toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    var authorization =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.fromString(accountEvidence.scopeId()),
            source,
            List.of(accountEvidence));
    var operation = new GameLogicGameplayRuleIntakeOperation(namespace, authorization);
    var terminal =
        outcome == Outcome.RETAINED
            ? GameLogicGameplayRuleIntakeTerminal.retained(
                operation,
                source.canonicalBytes(),
                manifest.canonicalJson().getBytes(StandardCharsets.UTF_8))
            : GameLogicGameplayRuleIntakeTerminal.aborted(operation);
    return new AccountGameLogicIntakeSettlementReadEvidence(
        AccountGameLogicIntakeSettlementReadEvidence.Request.create(namespace, authorization),
        new AccountGameLogicIntakeSettlementEvidence(terminal));
  }
}
