package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignFreshTenantCreationClientTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final String SOURCE_TENANT_KEY = "fresh-owner-key-91";
  private static final long SOURCE_GAME_ROW_ID = 91L;

  @Test
  void readsExactImmutableReceiptAndSendsExactRequestWithBoundedDeadline() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveFreshTenantCreation(any())).thenReturn(validResponse());
    GameDesignFreshTenantCreationClient client = newClient(stub);

    FreshTenantCreationEvidence evidence = client.resolveCreation(REQUEST_ID, REQUEST_DIGEST);

    assertThat(evidence.creationRequestId()).isEqualTo(REQUEST_ID);
    assertThat(evidence.operationId()).isEqualTo(SOURCE_OPERATION_ID);
    assertThat(evidence.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(evidence.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(evidence.sourceGameRowId()).isEqualTo(SOURCE_GAME_ROW_ID);
    assertThat(evidence.sourceGameTenantKey()).isEqualTo(SOURCE_TENANT_KEY);
    ArgumentCaptor<ResolveFreshTenantCreationRequest> request =
        ArgumentCaptor.forClass(ResolveFreshTenantCreationRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).resolveFreshTenantCreation(request.capture());
    assertThat(request.getValue().getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(request.getValue().getExpectedRequestDigest()).isEqualTo(REQUEST_DIGEST);
  }

  @Test
  void rejectsMalformedSelectorUnknownResponseFieldsAndMismatchedEvidence() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub unused = mockStub();
    GameDesignFreshTenantCreationClient noCall = newClient(unused);
    assertThatThrownBy(() -> noCall.resolveCreation(null, REQUEST_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> noCall.resolveCreation(REQUEST_ID, "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(unused);

    assertRejected(
        validResponse().toBuilder().setRequestDigest("sha256:" + "b".repeat(64)).build());
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertRejected(validResponse().toBuilder().setUnknownFields(unknown).build());
    assertRejected(validResponse().toBuilder().setProvenanceKind("RETAINED_GAME_V30").build());
  }

  @Test
  void refusesRemoteReadInsideAnActiveGameSessionTransaction() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    GameDesignFreshTenantCreationClient client = newClient(stub);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside a Game Session transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(stub);
  }

  @Test
  void requiresFileBackedMtlsAndPinsTheGameDesignServerPeer(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design-service.internal:6565");
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    GameDesignFreshTenantCreationClient client =
        new GameDesignFreshTenantCreationClient(endpoints, tls, channelFactory, "test");
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design-service.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
      assertThat(installedServerPeerUri(client))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
    } finally {
      client.close();
    }

    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameDesignFreshTenantCreationClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAnOwnerResponseWithoutAuthenticatedGameDesignServerSession(@TempDir Path directory)
      throws Exception {
    UnauthenticatedResponseChannel channel = new UnauthenticatedResponseChannel(validResponse());
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    GameDesignFreshTenantCreationClient client =
        new GameDesignFreshTenantCreationClient(
            new ServiceEndpointsProperties(), mtlsProperties(directory), factory, "test");
    try {
      client.init();
      assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(channel.lastCallCancelled).isTrue();
    } finally {
      client.close();
    }
  }

  private static void assertRejected(ResolveFreshTenantCreationResponse response) throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveFreshTenantCreation(any())).thenReturn(response);
    GameDesignFreshTenantCreationClient client = newClient(stub);
    assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GameDesignFreshTenantCreationClient newClient(
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub) throws Exception {
    GameDesignFreshTenantCreationClient client =
        new GameDesignFreshTenantCreationClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            "test");
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static String installedServerPeerUri(GameDesignFreshTenantCreationClient client)
      throws Exception {
    Field interceptorField =
        GameDesignFreshTenantCreationClient.class.getDeclaredField("serverPeerIdentityInterceptor");
    interceptorField.setAccessible(true);
    GrpcServerPeerIdentityClientInterceptor interceptor =
        (GrpcServerPeerIdentityClientInterceptor) interceptorField.get(client);
    Field expectedPeerUriField =
        GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri");
    expectedPeerUriField.setAccessible(true);
    return (String) expectedPeerUriField.get(interceptor);
  }

  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub mockStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolveFreshTenantCreationResponse validResponse() {
    return ResolveFreshTenantCreationResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace("test")
        .setCreationRequestId(REQUEST_ID.toString())
        .setOperationId(SOURCE_OPERATION_ID.toString())
        .setRequestDigest(REQUEST_DIGEST)
        .setCanonicalTenantId(CANONICAL_TENANT_ID.toString())
        .setSourceGameRowId(SOURCE_GAME_ROW_ID)
        .setSourceGameTenantKey(SOURCE_TENANT_KEY)
        .setProvenanceKind("NEW_GAME_ROW")
        .setEvidenceDigest(
            GameTenantCreationDigest.evidenceDigest(
                "test",
                REQUEST_ID,
                SOURCE_OPERATION_ID,
                REQUEST_DIGEST,
                CANONICAL_TENANT_ID,
                SOURCE_GAME_ROW_ID,
                SOURCE_TENANT_KEY,
                "NEW_GAME_ROW"))
        .build();
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/game-design-server-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(copyCertificateResource("dev-cert.pem", directory).toString());
    tls.setPrivateKey(copyCertificateResource("dev-key.pem", directory).toString());
    tls.setCaCert(copyCertificateResource("dev-ca.pem", directory).toString());
    return tls;
  }

  private static Path copyCertificateResource(String fileName, Path directory) throws Exception {
    Path destination = directory.resolve(fileName);
    try (InputStream source =
        GameDesignFreshTenantCreationClientTest.class.getResourceAsStream("/certs/" + fileName)) {
      if (source == null) {
        throw new IllegalStateException("Missing shared TLS test resource: " + fileName);
      }
      Files.copy(source, destination);
    }
    return destination;
  }

  private static final class UnauthenticatedResponseChannel extends ManagedChannel {
    private final byte[] responseBytes;
    private boolean lastCallCancelled;
    private boolean shutDown;

    private UnauthenticatedResponseChannel(ResolveFreshTenantCreationResponse response) {
      this.responseBytes = response.toByteArray();
    }

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      return new ClientCall<>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          responseListener.onHeaders(new Metadata());
          responseListener.onMessage(method.parseResponse(new ByteArrayInputStream(responseBytes)));
          responseListener.onClose(Status.OK, new Metadata());
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {
          lastCallCancelled = true;
        }

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}

        @Override
        public Attributes getAttributes() {
          return Attributes.EMPTY;
        }
      };
    }

    @Override
    public ManagedChannel shutdown() {
      shutDown = true;
      return this;
    }

    @Override
    public boolean isShutdown() {
      return shutDown;
    }

    @Override
    public boolean isTerminated() {
      return shutDown;
    }

    @Override
    public ManagedChannel shutdownNow() {
      shutDown = true;
      return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutDown;
    }
  }
}
