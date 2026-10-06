package unit.net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical Account-client mTLS proof; the receiver is a transport-only generated-service double.
 */
class WorldDraftTerminalReadMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_PEER = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_PEER =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_NAMESPACE_PEER =
      "spiffe://firemud/ns/other/sa/world-management-service";
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REPLAY_READ_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID TENANT_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID VERSION_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID REVISION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);

  @Test
  void physicallyReadsUnknownAndReplaysExactCanonicalWorldAbort(@TempDir Path directory)
      throws Exception {
    TestPki pki = newTestPki();
    PhysicalServer unknownServer =
        startReceiver(pki.worldServer(), pki.caCertificate(), Reply.UNKNOWN);
    WorldDraftTerminalReadClient unknownClient =
        newClient(unknownServer.server().getPort(), pki, directory.resolve("unknown"));
    try {
      unknownClient.init();
      WorldDraftTerminalReadEvidence unknown = unknownClient.read(request(READ_REQUEST_ID));

      assertThat(unknown.ownerReadback()).isEmpty();
      assertPhysicalRequest(unknownServer, request(READ_REQUEST_ID));
      assertThat(unknownServer.applicationMetadataCalls()).hasValue(1);
      assertThat(unknownServer.requestBodies()).hasValue(1);
    } finally {
      unknownClient.close();
      stopServer(unknownServer.server());
    }

    PhysicalServer abortServer = startReceiver(pki.worldServer(), pki.caCertificate(), Reply.ABORT);
    WorldDraftTerminalReadClient abortClient =
        newClient(abortServer.server().getPort(), pki, directory.resolve("abort"));
    try {
      abortClient.init();
      var firstRequest = request(READ_REQUEST_ID);
      var replayRequest = request(REPLAY_READ_REQUEST_ID);
      var first = abortClient.read(firstRequest);
      assertPhysicalRequest(abortServer, firstRequest);
      var replay = abortClient.read(replayRequest);

      assertThat(first.ownerReadback()).isPresent();
      assertThat(replay.ownerReadback()).isPresent();
      assertThat(first.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(replay.ownerReadback().orElseThrow().canonicalBytes());
      assertThat(first.ownerReadback().orElseThrow().fullBinding())
          .containsExactly(firstRequest.originalAccountBinding());
      assertThat(first.ownerReadback().orElseThrow().result()).containsExactly(1, 2, 3);
      assertPhysicalRequest(abortServer, replayRequest);
      assertThat(abortServer.applicationMetadataCalls()).hasValue(2);
      assertThat(abortServer.requestBodies()).hasValue(2);
    } finally {
      abortClient.close();
      stopServer(abortServer.server());
    }
  }

  @Test
  void wrongTrustedWorldWorkloadAndNamespaceSeeNoAuthorizationMetadataOrRequestBody(
      @TempDir Path directory) throws Exception {
    TestPki pki = newTestPki();
    int index = 0;
    for (TestCertificate wrongServer :
        List.of(pki.wrongWorkloadServer(), pki.wrongNamespaceServer())) {
      PhysicalServer server = startReceiver(wrongServer, pki.caCertificate(), Reply.UNKNOWN);
      WorldDraftTerminalReadClient client =
          newClient(server.server().getPort(), pki, directory.resolve("wrong-" + index++));
      try {
        client.init();
        Throwable failure = catchThrowable(() -> client.read(request(READ_REQUEST_ID)));

        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) failure).getStatus().getCode())
            .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
        assertThat(server.applicationMetadataCalls()).hasValue(0);
        assertThat(server.requestBodies()).hasValue(0);
        assertThat(server.authenticatedCallerPeer()).hasValue(null);
        assertThat(server.request()).hasValue(null);
      } finally {
        client.close();
        stopServer(server.server());
      }
    }
  }

  @Test
  void physicallyReadsExactCommittedCarrierAndRejectsTerminalSubstitution(@TempDir Path directory)
      throws Exception {
    TestPki pki = newTestPki();
    for (Reply reply :
        List.of(
            Reply.COMMITTED,
            Reply.COMMITTED_STATUS_MISMATCH,
            Reply.COMMITTED_WRONG_OWNER,
            Reply.COMMITTED_SUBSTITUTED_RESULT,
            Reply.COMMITTED_CHANGED_BINDING)) {
      PhysicalServer server = startReceiver(pki.worldServer(), pki.caCertificate(), reply);
      var client = newClient(server.server().getPort(), pki, directory.resolve(reply.name()));
      try {
        client.init();
        var request = request(READ_REQUEST_ID);
        if (reply == Reply.COMMITTED) {
          var first = client.read(request).ownerReadback().orElseThrow();
          var retry = client.read(request(REPLAY_READ_REQUEST_ID)).ownerReadback().orElseThrow();
          assertThat(first.outcome()).isEqualTo(Outcome.COMMITTED);
          assertThat(first.canonicalBytes()).containsExactly(canonicalCommitted().canonicalBytes());
          assertThat(retry.canonicalBytes()).containsExactly(first.canonicalBytes());
          assertThat(first.fullBinding()).containsExactly(request.originalAccountBinding());
          assertPhysicalRequest(server, request(REPLAY_READ_REQUEST_ID));
          assertThat(server.applicationMetadataCalls()).hasValue(2);
          assertThat(server.requestBodies()).hasValue(2);
        } else {
          assertThat(catchThrowable(() -> client.read(request)))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("invalid terminal readback evidence");
          assertPhysicalRequest(server, request);
          assertThat(server.applicationMetadataCalls()).hasValue(1);
          assertThat(server.requestBodies()).hasValue(1);
        }
      } finally {
        client.close();
        stopServer(server.server());
      }
    }
  }

  @Test
  void rejectsChangedEchoedReadIdentityAndOriginalBinding(@TempDir Path directory)
      throws Exception {
    TestPki pki = newTestPki();
    for (Reply reply : List.of(Reply.CHANGED_READ_ID, Reply.CHANGED_ORIGINAL_BINDING)) {
      PhysicalServer server = startReceiver(pki.worldServer(), pki.caCertificate(), reply);
      WorldDraftTerminalReadClient client =
          newClient(server.server().getPort(), pki, directory.resolve(reply.name()));
      try {
        client.init();
        Throwable failure = catchThrowable(() -> client.read(request(READ_REQUEST_ID)));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure).hasMessageContaining("invalid terminal readback evidence");
        assertThat(server.applicationMetadataCalls()).hasValue(1);
        assertThat(server.requestBodies()).hasValue(1);
        assertThat(server.authenticatedCallerPeer()).hasValue(ACCOUNT_PEER);
      } finally {
        client.close();
        stopServer(server.server());
      }
    }
  }

  @Test
  void plaintextAndMissingCertificateCustodyAreUnavailableBeforeTransport(@TempDir Path directory)
      throws Exception {
    CommonGrpcClientProperties plaintext = tls(directory.resolve("plaintext"), "plain");
    plaintext.setPlaintext(true);
    assertThat(catchThrowable(() -> newClient(1, plaintext, NAMESPACE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    CommonGrpcClientProperties missingCustody = tls(directory.resolve("missing"), "missing");
    missingCustody.setCertChain(null);
    assertThat(catchThrowable(() -> newClient(1, missingCustody, NAMESPACE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed certificate");
  }

  private static WorldDraftTerminalReadEvidence.Request request(UUID readRequestId) {
    return new WorldDraftTerminalReadEvidence.Request(
        1, NAMESPACE, readRequestId, originalAccountBinding());
  }

  private static byte[] originalAccountBinding() {
    DraftCommitBinding gameDesign =
        DraftCommitBinding.create(
            new TargetProof(
                TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            REQUEST_ID,
            COMMIT_ID,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0", REVISION_ID, DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = gameDesign.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            OPERATION_ID,
            REQUEST_ID,
            COMMIT_ID,
            FENCE_ID,
            ACTOR_ID,
            TENANT_ID,
            VERSION_ID,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            gameDesign.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})))
        .canonicalBytes();
  }

  private static void assertPhysicalRequest(
      PhysicalServer server, WorldDraftTerminalReadEvidence.Request expected) {
    assertThat(server.authenticatedCallerPeer()).hasValue(ACCOUNT_PEER);
    assertThat(server.request()).hasValue(WorldDraftTerminalReadGrpcCodec.toRequest(expected));
  }

  private static WorldDraftTerminalReadClient newClient(int port, TestPki pki, Path directory)
      throws Exception {
    return newClient(port, tls(directory, "account", pki), NAMESPACE);
  }

  private static WorldDraftTerminalReadClient newClient(
      int port, CommonGrpcClientProperties tls, String namespace) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + port);
    return new WorldDraftTerminalReadClient(
        endpoints, tls, new net.firedevops.firemud.common.grpc.GrpcChannelFactory(), namespace);
  }

  private static PhysicalServer startReceiver(
      TestCertificate serverCertificate, X509Certificate caCertificate, Reply reply)
      throws Exception {
    AtomicInteger applicationMetadataCalls = new AtomicInteger();
    AtomicInteger requestBodies = new AtomicInteger();
    AtomicReference<ReadWorldDraftTerminalOutcomeRequest> capturedRequest = new AtomicReference<>();
    AtomicReference<String> authenticatedCallerPeer = new AtomicReference<>();
    OwnerReadback canonicalAbort = canonicalAbort();
    OwnerReadback canonicalCommitted = canonicalCommitted();

    // This receiver double proves transport and client decoding only. It is not World's SQL owner,
    // an authenticated production readback producer, Account authorization, or settlement proof.
    WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceImplBase service =
        new WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceImplBase() {
          @Override
          public void readWorldDraftTerminalOutcome(
              ReadWorldDraftTerminalOutcomeRequest request,
              StreamObserver<ReadWorldDraftTerminalOutcomeResponse> responseObserver) {
            requestBodies.incrementAndGet();
            capturedRequest.set(request);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            authenticatedCallerPeer.set(peer == null ? null : peer.uri());
            var builder =
                ReadWorldDraftTerminalOutcomeResponse.newBuilder()
                    .setSchemaVersion(request.getSchemaVersion())
                    .setTargetNamespace(request.getTargetNamespace())
                    .setReadRequestId(request.getReadRequestId())
                    .setOriginalAccountBinding(request.getOriginalAccountBinding());
            switch (reply) {
              case UNKNOWN ->
                  builder.setStatus(
                      WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
              case ABORT ->
                  builder
                      .setStatus(
                          WorldDraftTerminalReadStatus
                              .WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
                      .setOwnerReadbackBytes(ByteString.copyFrom(canonicalAbort.canonicalBytes()));
              case COMMITTED ->
                  builder
                      .setStatus(
                          WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
                      .setOwnerReadbackBytes(
                          ByteString.copyFrom(canonicalCommitted.canonicalBytes()));
              case COMMITTED_STATUS_MISMATCH ->
                  builder
                      .setStatus(
                          WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
                      .setOwnerReadbackBytes(ByteString.copyFrom(canonicalAbort.canonicalBytes()));
              case COMMITTED_WRONG_OWNER,
                  COMMITTED_SUBSTITUTED_RESULT,
                  COMMITTED_CHANGED_BINDING -> {
                var original =
                    DraftAuthorizationFenceBinding.fromStored(canonicalCommitted.fullBinding());
                byte[] fullBinding = original.canonicalBytes();
                if (reply == Reply.COMMITTED_CHANGED_BINDING)
                  fullBinding =
                      new DraftAuthorizationFenceBinding(
                              original.operationId(),
                              original.requestId(),
                              original.commitId(),
                              original.fenceId(),
                              UUID.randomUUID(),
                              original.tenantId(),
                              original.versionId(),
                              original.baseCommitId(),
                              original.expectedDraftEpoch(),
                              original.gameDesignBinding(),
                              original.normalizedInput(),
                              original.inputDigest(),
                              original.sources())
                          .canonicalBytes();
                var changed =
                    new OwnerReadback(
                        reply == Reply.COMMITTED_WRONG_OWNER ? Owner.GAME_DESIGN : Owner.WORLD,
                        Outcome.COMMITTED,
                        original.operationId(),
                        original.commitId(),
                        original.fenceId(),
                        original.inputDigest(),
                        fullBinding,
                        reply == Reply.COMMITTED_SUBSTITUTED_RESULT
                            ? new byte[] {1}
                            : canonicalCommitted.result());
                builder
                    .setStatus(
                        WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
                    .setOwnerReadbackBytes(ByteString.copyFrom(changed.canonicalBytes()));
              }
              case CHANGED_READ_ID -> builder.setReadRequestId(REPLAY_READ_REQUEST_ID.toString());
              case CHANGED_ORIGINAL_BINDING ->
                  builder.setOriginalAccountBinding(ByteString.copyFrom(new byte[] {9, 8, 7}));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
          }
        };
    ServerInterceptor metadataCounter =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            applicationMetadataCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverCertificate.privateKey(), serverCertificate.certificate()))
                    .trustManager(caCertificate)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, metadataCounter, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new PhysicalServer(
        server, applicationMetadataCalls, requestBodies, capturedRequest, authenticatedCallerPeer);
  }

  private static OwnerReadback canonicalCommitted() {
    var request = request(READ_REQUEST_ID);
    try {
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), java.nio.charset.StandardCharsets.UTF_8),
              account.inputDigest());
      var operation = new java.io.ByteArrayOutputStream();
      var frames = new java.io.DataOutputStream(operation);
      java.util.function.Consumer<byte[]> frame =
          bytes -> {
            try {
              frames.writeInt(bytes.length);
              frames.write(bytes);
            } catch (java.io.IOException impossible) {
              throw new AssertionError(impossible);
            }
          };
      java.util.function.Consumer<String> text =
          value -> frame.accept(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      text.accept("world-draft-terminal-operation/v1");
      for (UUID id :
          List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) text.accept(id.toString());
      frame.accept(draft.canonicalBytes());
      for (String value :
          List.of(
              request.targetNamespace(),
              account.tenantId().toString(),
              account.versionId().toString(),
              "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              Long.toString(draft.target().gameDesignVersionRowId()),
              "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
              "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
              "a".repeat(64),
              "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              "b".repeat(64),
              "c".repeat(64))) text.accept(value);
      text.accept(
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(account.canonicalBytes())));
      frame.accept(account.canonicalBytes());
      byte[] graph =
          ("{\"schemaVersion\":\"2\",\"canonicalTenantId\":\""
                  + account.tenantId()
                  + "\",\"canonicalVersionId\":\""
                  + account.versionId()
                  + "\",\"rows\":[{}]}")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var result = new java.util.LinkedHashMap<String, Object>();
      result.put("schema", "world-draft-graph-applied/v1");
      result.put("status", "APPLIED");
      result.put(
          "operationBytesBase64",
          java.util.Base64.getEncoder().encodeToString(operation.toByteArray()));
      result.put("graphBytesBase64", java.util.Base64.getEncoder().encodeToString(graph));
      result.put(
          "graphDigest",
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(graph)));
      result.put(
          "appliedEpochs",
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      java.util.Map.of(
                          "aggregateType",
                          unit.aggregateType(),
                          "aggregateId",
                          unit.aggregateId(),
                          "scopeType",
                          unit.scopeType(),
                          "scopeId",
                          unit.scopeId(),
                          "expectedEpoch",
                          unit.expectedEpoch(),
                          "resultingEpoch",
                          new java.math.BigInteger(unit.expectedEpoch())
                              .add(java.math.BigInteger.ONE)
                              .toString()))
              .toList());
      var bytes =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              new tools.jackson.databind.ObjectMapper().writeValueAsString(result));
      return new DraftAuthorizationFenceBinding.OwnerReadback(
          Owner.WORLD,
          Outcome.COMMITTED,
          account.operationId(),
          account.commitId(),
          account.fenceId(),
          account.inputDigest(),
          account.canonicalBytes(),
          bytes);
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static OwnerReadback canonicalAbort() {
    var binding = DraftAuthorizationFenceBinding.fromStored(originalAccountBinding());
    return new OwnerReadback(
        Owner.WORLD,
        Outcome.DEFINITIVELY_ABORTED,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        new byte[] {1, 2, 3});
  }

  private static CommonGrpcClientProperties tls(Path directory, String prefix) throws IOException {
    Files.createDirectories(directory);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve(prefix + ".crt"), "test").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve(prefix + ".key"), "test").toString());
    tls.setCaCert(Files.writeString(directory.resolve(prefix + "-ca.crt"), "test").toString());
    return tls;
  }

  private static void stopServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static TestPki newTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=World terminal mTLS Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    return new TestPki(
        caCertificate,
        issueLeaf(caName, caKeyPair.getPrivate(), "account-service", ACCOUNT_PEER),
        issueLeaf(caName, caKeyPair.getPrivate(), "world-management-service", WORLD_PEER),
        issueLeaf(caName, caKeyPair.getPrivate(), "game-design-service", WRONG_WORKLOAD_PEER),
        issueLeaf(
            caName,
            caKeyPair.getPrivate(),
            "other-namespace-world-management",
            WRONG_NAMESPACE_PEER));
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String commonName, String workloadUri)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralNames subjectAltNames =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });
    X509Certificate certificate =
        issueCertificate(
            subject, keyPair.getPublic(), caName, caPrivateKey, false, subjectAltNames);
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant notBefore = Instant.now().minusSeconds(60);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(notBefore),
            Date.from(notBefore.plusSeconds(60L * 60L * 24L * 14L)),
            subject,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (ca) {
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    } else {
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerPrivateKey)));
  }

  private static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static CommonGrpcClientProperties tls(Path directory, String prefix, TestPki pki)
      throws Exception {
    Files.createDirectories(directory);
    TestCertificate account = pki.accountClient();
    var caPath =
        writePem(
            directory.resolve(prefix + "-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    var certPath =
        writePem(
            directory.resolve(prefix + "-account.crt"),
            "CERTIFICATE",
            account.certificate().getEncoded());
    var keyPath =
        writePem(
            directory.resolve(prefix + "-account.key"),
            "PRIVATE KEY",
            account.privateKey().getEncoded());
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certPath.toString());
    tls.setPrivateKey(keyPath.toString());
    tls.setCaCert(caPath.toString());
    return tls;
  }

  private static Path writePem(Path path, String type, byte[] encoded) throws IOException {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    Files.writeString(
        path,
        "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate accountClient,
      TestCertificate worldServer,
      TestCertificate wrongWorkloadServer,
      TestCertificate wrongNamespaceServer) {}

  private record PhysicalServer(
      Server server,
      AtomicInteger applicationMetadataCalls,
      AtomicInteger requestBodies,
      AtomicReference<ReadWorldDraftTerminalOutcomeRequest> request,
      AtomicReference<String> authenticatedCallerPeer) {}

  private enum Reply {
    UNKNOWN,
    ABORT,
    COMMITTED,
    COMMITTED_STATUS_MISMATCH,
    COMMITTED_WRONG_OWNER,
    COMMITTED_SUBSTITUTED_RESULT,
    COMMITTED_CHANGED_BINDING,
    CHANGED_READ_ID,
    CHANGED_ORIGINAL_BINDING
  }
}
