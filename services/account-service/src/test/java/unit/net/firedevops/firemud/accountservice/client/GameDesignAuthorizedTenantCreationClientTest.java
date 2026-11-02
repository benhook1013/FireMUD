package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Empty;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantResponse;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.TenantCreationServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled RPC responses; configured peer pinning is not physical mTLS execution proof. */
class GameDesignAuthorizedTenantCreationClientTest {
  @TempDir private Path tlsDirectory;

  @BeforeEach
  void copyTlsResources() throws Exception {
    copyCertificateResource("dev-cert.pem");
    copyCertificateResource("dev-key.pem");
    copyCertificateResource("dev-ca.pem");
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void sendsCompleteExactOriginalWithBoundedDeadlineAndExplicitDescriptionPresence()
      throws Exception {
    for (String description : new String[] {null, ""}) {
      var f = new Fixture(description);
      when(f.stub.createAuthorizedTenant(any()))
          .thenAnswer(
              invocation -> {
                var request = invocation.<CreateAuthorizedTenantRequest>getArgument(0);
                assertThat(request.getCreateRequestId()).isEqualTo(f.call.toString());
                assertThat(request.hasDescription()).isEqualTo(description != null);
                assertThat(request.getCreationRequestDigest())
                    .isEqualTo(f.binding.creationRequestDigest());
                return f.response();
              });
      assertThat(f.send()).isEqualTo(f.receipt);
      verify(f.stub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }
  }

  @Test
  void rejectsChangedEchoNestedReceiptUnknownStatusFieldsAndOversize() throws Exception {
    List<UnaryOperator<CreateAuthorizedTenantResponse>> mutations =
        List.of(
            r -> r.toBuilder().setCreateRequestId(UUID.randomUUID().toString()).build(),
            r -> r.toBuilder().setTargetNamespace("other").build(),
            r -> r.toBuilder().setCreationRequestId(UUID.randomUUID().toString()).build(),
            r ->
                r.toBuilder()
                    .setAccountAuthorizationOperationId(UUID.randomUUID().toString())
                    .build(),
            r -> r.toBuilder().setInitiatingAccountId(UUID.randomUUID().toString()).build(),
            r -> r.toBuilder().setCreationRequestDigest("sha256:" + "b".repeat(64)).build(),
            r -> r.toBuilder().setAccountAuthorizationDigest("sha256:" + "b".repeat(64)).build(),
            r -> r.toBuilder().setStatusValue(99).build(),
            r -> r.toBuilder().clearCreatedReceipt().build(),
            r ->
                r.toBuilder()
                    .setCreatedReceipt(
                        r.getCreatedReceipt().toBuilder()
                            .setCanonicalTenantId(UUID.randomUUID().toString()))
                    .build(),
            r ->
                r.toBuilder()
                    .setCreatedReceipt(
                        r.getCreatedReceipt().toBuilder().setSourceGameTenantKey("other"))
                    .build());
    for (var mutation : mutations) {
      var f = new Fixture(null);
      when(f.stub.createAuthorizedTenant(any())).thenReturn(mutation.apply(f.response()));
      assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    }
    var f = new Fixture(null);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    when(f.stub.createAuthorizedTenant(any()))
        .thenReturn(f.response().toBuilder().setUnknownFields(unknown).build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    when(f.stub.createAuthorizedTenant(any()))
        .thenReturn(
            f.response().toBuilder()
                .setCreatedReceipt(
                    f.response().getCreatedReceipt().toBuilder().setUnknownFields(unknown))
                .build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    when(f.stub.createAuthorizedTenant(any()))
        .thenReturn(f.response().toBuilder().setCreateRequestId("x".repeat(4097)).build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void neverRetriesLostRepliesAndRefusesAmbientSqlOrExpiredDispatchBeforeNetwork()
      throws Exception {
    var f = new Fixture(null);
    var failure = Status.UNAVAILABLE.asRuntimeException();
    when(f.stub.createAuthorizedTenant(any())).thenThrow(failure);
    assertThatThrownBy(f::send).isSameAs(failure);
    verify(f.stub).createAuthorizedTenant(any());
    var untouched = new Fixture(null);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(untouched::send).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clear();
    assertThatThrownBy(
            () ->
                untouched.client.create(
                    untouched.call, untouched.binding, "fresh", "Game", null, 1L))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(untouched.stub);
  }

  @Test
  void requiresFileMtlsAndPinsExactSameNamespaceGameDesignServer() throws Exception {
    var tls = tls();
    tls.setPlaintext(true);
    var channels = mock(GrpcChannelFactory.class);
    assertThatThrownBy(
            () ->
                new GameDesignAuthorizedTenantCreationClient(
                    new ServiceEndpointsProperties(), tls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var f = new Fixture(null);
    Field field = GameDesignAuthorizedTenantCreationClient.class.getDeclaredField("peer");
    field.setAccessible(true);
    Field expected =
        GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri");
    expected.setAccessible(true);
    assertThat(expected.get(field.get(f.client)))
        .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
    verifyNoInteractions(channels);

    var missingFileTls = tls();
    missingFileTls.setCertChain(tlsDirectory.resolve("missing-cert.pem").toString());
    assertThatThrownBy(
            () ->
                new GameDesignAuthorizedTenantCreationClient(
                    new ServiceEndpointsProperties(), missingFileTls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");
    verifyNoInteractions(channels);
  }

  private CommonGrpcClientProperties tls() {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(tlsDirectory.resolve("dev-cert.pem").toString());
    tls.setPrivateKey(tlsDirectory.resolve("dev-key.pem").toString());
    tls.setCaCert(tlsDirectory.resolve("dev-ca.pem").toString());
    return tls;
  }

  private void copyCertificateResource(String name) throws Exception {
    Path destination = tlsDirectory.resolve(name);
    try (InputStream source =
        GameDesignAuthorizedTenantCreationClientTest.class.getResourceAsStream("/certs/" + name)) {
      if (source == null) throw new IllegalStateException("Missing TLS test resource: " + name);
      Files.copy(source, destination);
    }
  }

  @Test
  void historicalClosureUsesBoundedExactSelectorAndTreatsAckOnlyAsAcknowledgment()
      throws Exception {
    var f = new Fixture(null);
    when(f.stub.closeOriginalTenantCreation(any())).thenReturn(Empty.getDefaultInstance());
    f.client.closeOriginal(f.binding);
    var selector =
        org.mockito.ArgumentCaptor.forClass(ResolveOriginalTenantCreationOutcomeRequest.class);
    verify(f.stub).closeOriginalTenantCreation(selector.capture());
    assertThat(
            net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec
                .fromReadRequest("test", selector.getValue())
                .binding())
        .isEqualTo(f.binding);
    verify(f.stub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    var failure = Status.DEADLINE_EXCEEDED.asRuntimeException();
    when(f.stub.closeOriginalTenantCreation(any())).thenThrow(failure);
    assertThatThrownBy(() -> f.client.closeOriginal(f.binding)).isSameAs(failure);
  }

  @Test
  void createAndCloseRequireInitializedClientAndCloseMakesItPermanentlyUnavailable()
      throws Exception {
    var f = new Fixture(null);
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    var client = newClient(channelFactory);

    assertThatThrownBy(
            () ->
                client.create(
                    f.call, f.binding, "fresh", "Game", null, System.currentTimeMillis() + 60_000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(() -> client.closeOriginal(f.binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);

    client.init();
    client.init();
    verify(channelFactory, org.mockito.Mockito.times(1))
        .buildChannel(anyString(), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
    client.close();

    assertThatThrownBy(
            () ->
                client.create(
                    f.call, f.binding, "fresh", "Game", null, System.currentTimeMillis() + 60_000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(() -> client.closeOriginal(f.binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    verify(channel).shutdown();
    verify(channelFactory, org.mockito.Mockito.times(1))
        .buildChannel(anyString(), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
  }

  @Test
  void createAndCloseRejectWrongServerPeerBeforeRequestReachesOwner() throws Exception {
    var f = new Fixture(null);
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = new PeerGatedChannel("spiffe://firemud/ns/other/sa/game-design-service");
    when(channelFactory.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    var client = newClient(channelFactory);
    try {
      client.init();
      var baseStub = initializedStub(client);
      assertThat(baseStub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      Field expectedPeer =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      expectedPeer.setAccessible(true);
      assertThat(expectedPeer.get(baseStub.getCallOptions().getCredentials()))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");

      assertThatThrownBy(
              () ->
                  client.create(
                      f.call,
                      f.binding,
                      "fresh",
                      "Game",
                      null,
                      System.currentTimeMillis() + 60_000))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThatThrownBy(() -> client.closeOriginal(f.binding))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);

      assertThat(channel.ownerRequests).isZero();
      assertThat(channel.methods)
          .containsExactly("CreateAuthorizedTenant", "CloseOriginalTenantCreation");
    } finally {
      client.close();
    }
  }

  private GameDesignAuthorizedTenantCreationClient newClient(GrpcChannelFactory channelFactory) {
    return new GameDesignAuthorizedTenantCreationClient(
        new ServiceEndpointsProperties(), tls(), channelFactory, "test");
  }

  private static TenantCreationServiceGrpc.TenantCreationServiceBlockingStub initializedStub(
      GameDesignAuthorizedTenantCreationClient client) throws Exception {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (TenantCreationServiceGrpc.TenantCreationServiceBlockingStub) field.get(client);
  }

  private static final class PeerGatedChannel extends ManagedChannel {
    private final CallCredentials.RequestInfo requestInfo;
    private final List<String> methods = new java.util.ArrayList<>();
    private int ownerRequests;
    private boolean shutdown;

    private PeerGatedChannel(String peerUri) throws Exception {
      SSLSession sslSession = mock(SSLSession.class);
      X509Certificate certificate = mock(X509Certificate.class);
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
      when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
      requestInfo = mock(CallCredentials.RequestInfo.class);
      when(requestInfo.getTransportAttrs())
          .thenReturn(
              Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
    }

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      methods.add(methodDescriptor.getBareMethodName());
      return new ClientCall<>() {
        private Listener<RespT> listener;
        private boolean peerVerified;
        private boolean closed;

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          listener = responseListener;
          CallCredentials credentials = callOptions.getCredentials();
          if (credentials == null) {
            reject(Status.UNAUTHENTICATED.withDescription("Peer credential required"));
            return;
          }
          credentials.applyRequestMetadata(
              requestInfo,
              Runnable::run,
              new CallCredentials.MetadataApplier() {
                @Override
                public void apply(Metadata metadata) {
                  peerVerified = true;
                }

                @Override
                public void fail(Status failure) {
                  reject(failure);
                }
              });
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {
          closed = true;
        }

        @Override
        public void halfClose() {
          if (peerVerified && !closed) ownerRequests++;
        }

        @Override
        public void sendMessage(ReqT message) {}

        private void reject(Status failure) {
          if (closed) return;
          closed = true;
          listener.onClose(failure, new Metadata());
        }
      };
    }

    @Override
    public ManagedChannel shutdown() {
      shutdown = true;
      return this;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return shutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  private final class Fixture {
    final UUID call = UUID.randomUUID(),
        request = UUID.randomUUID(),
        operation = UUID.randomUUID(),
        tenant = UUID.randomUUID();
    final Binding binding;
    final FreshTenantCreationEvidence receipt;
    final String description;
    final TenantCreationServiceGrpc.TenantCreationServiceBlockingStub stub =
        mock(TenantCreationServiceGrpc.TenantCreationServiceBlockingStub.class);
    final GameDesignAuthorizedTenantCreationClient client;

    Fixture(String description) throws Exception {
      this.description = description;
      String digest =
          GameTenantCreationDigest.requestDigest("test", request, "fresh", "Game", description);
      binding =
          new Binding(
              "test",
              request,
              UUID.randomUUID(),
              UUID.randomUUID(),
              digest,
              "sha256:" + "a".repeat(64));
      receipt =
          new FreshTenantCreationEvidence(
              1,
              "test",
              request,
              operation,
              digest,
              tenant,
              9,
              "fresh",
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  "test", request, operation, digest, tenant, 9, "fresh", "NEW_GAME_ROW"));
      client =
          new GameDesignAuthorizedTenantCreationClient(
              new ServiceEndpointsProperties(), tls(), mock(GrpcChannelFactory.class), "test");
      Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      field.set(client, stub);
      Field initialized =
          AbstractReloadingBlockingGrpcClient.class.getDeclaredField("explicitlyInitialized");
      initialized.setAccessible(true);
      // Codec cases exercise the production requireInitializedStub guard with a controlled stub.
      initialized.setBoolean(client, true);
      when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(stub);
    }

    FreshTenantCreationEvidence send() {
      return client.create(
          call, binding, "fresh", "Game", description, System.currentTimeMillis() + 60000);
    }

    CreateAuthorizedTenantResponse response() {
      return CreateAuthorizedTenantResponse.newBuilder()
          .setSchemaVersion(1)
          .setCreateRequestId(call.toString())
          .setTargetNamespace("test")
          .setCreationRequestId(request.toString())
          .setAccountAuthorizationOperationId(binding.accountAuthorizationOperationId().toString())
          .setInitiatingAccountId(binding.initiatingAccountId().toString())
          .setCreationRequestDigest(binding.creationRequestDigest())
          .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
          .setStatus(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED)
          .setCreatedReceipt(
              ResolveFreshTenantCreationResponse.newBuilder()
                  .setSchemaVersion(1)
                  .setTargetNamespace("test")
                  .setCreationRequestId(request.toString())
                  .setOperationId(operation.toString())
                  .setRequestDigest(binding.creationRequestDigest())
                  .setCanonicalTenantId(tenant.toString())
                  .setSourceGameRowId(9)
                  .setSourceGameTenantKey("fresh")
                  .setProvenanceKind("NEW_GAME_ROW")
                  .setEvidenceDigest(receipt.evidenceDigest()))
          .build();
    }
  }
}
