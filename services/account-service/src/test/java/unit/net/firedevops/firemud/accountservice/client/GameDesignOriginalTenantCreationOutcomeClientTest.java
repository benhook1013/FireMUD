package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcomeReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameDesignOriginalTenantCreationOutcomeClientTest {
  private static final UUID READ_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID CREATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID AUTH_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID RECEIPT_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String AUTH_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void exactCreatedAndNotCreatedReadsReturnOnlyCodecValidatedEvidence() throws Exception {
    var stub = mockStub();
    when(stub.resolveOriginalTenantCreationOutcome(any()))
        .thenAnswer(
            invocation ->
                responseFor(
                    invocation.getArgument(0, ResolveOriginalTenantCreationOutcomeRequest.class),
                    Outcome.CREATED));
    var client = newClient(stub);

    var first = client.readOriginal(binding());
    var second = client.readOriginal(binding());

    assertThat(first)
        .isInstanceOf(GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal.class);
    assertThat(first.binding()).isEqualTo(binding());
    assertThat(first.evidence().outcome()).isEqualTo(Outcome.CREATED);
    assertThat(first.evidence().receipt()).contains(receipt());
    assertThat(second.evidence()).isEqualTo(first.evidence());
    ArgumentCaptor<ResolveOriginalTenantCreationOutcomeRequest> requests =
        ArgumentCaptor.forClass(ResolveOriginalTenantCreationOutcomeRequest.class);
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, times(2)).resolveOriginalTenantCreationOutcome(requests.capture());
    assertThat(requests.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getSchemaVersion()).isEqualTo(1);
              assertThat(request.getTargetNamespace()).isEqualTo("test");
              assertThat(request.getCreationRequestId()).isEqualTo(CREATION_ID.toString());
              assertThat(request.getAccountAuthorizationOperationId())
                  .isEqualTo(AUTH_OPERATION_ID.toString());
              assertThat(request.getInitiatingAccountId()).isEqualTo(ACCOUNT_ID.toString());
              assertThat(request.getCreationRequestDigest()).isEqualTo(CREATION_DIGEST);
              assertThat(request.getAccountAuthorizationDigest()).isEqualTo(AUTH_DIGEST);
              assertThat(UUID.fromString(request.getReadRequestId())).isNotEqualTo(new UUID(0, 0));
            });
    assertThat(requests.getAllValues().get(0).getReadRequestId())
        .isNotEqualTo(requests.getAllValues().get(1).getReadRequestId());
    verifyNoMoreInteractions(stub);

    var notCreatedStub = mockStub();
    when(notCreatedStub.resolveOriginalTenantCreationOutcome(any()))
        .thenAnswer(
            invocation ->
                responseFor(
                    invocation.getArgument(0, ResolveOriginalTenantCreationOutcomeRequest.class),
                    Outcome.NOT_CREATED));
    var notCreated = newClient(notCreatedStub).readOriginal(binding());
    assertThat(notCreated.evidence().outcome()).isEqualTo(Outcome.NOT_CREATED);
    assertThat(notCreated.evidence().receipt()).isEmpty();
  }

  @Test
  void refusesAnyChangedEchoOutcomeDigestOrNestedReceipt() throws Exception {
    assertRejected(response -> response.toBuilder().setTargetNamespace("other").build());
    assertRejected(
        response ->
            response.toBuilder().setCreationRequestId(UUID.randomUUID().toString()).build());
    assertRejected(
        response ->
            response.toBuilder()
                .setAccountAuthorizationOperationId(UUID.randomUUID().toString())
                .build());
    assertRejected(
        response ->
            response.toBuilder().setInitiatingAccountId(UUID.randomUUID().toString()).build());
    assertRejected(
        response ->
            response.toBuilder().setCreationRequestDigest("sha256:" + "d".repeat(64)).build());
    assertRejected(
        response ->
            response.toBuilder().setAccountAuthorizationDigest("sha256:" + "e".repeat(64)).build());
    assertRejected(
        response -> response.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build());
    assertRejected(
        response -> response.toBuilder().setEvidenceDigest("sha256:" + "f".repeat(64)).build());
    assertRejected(response -> response.toBuilder().setSchemaVersion(2).build());
    assertRejected(
        response ->
            response.toBuilder()
                .setOutcome(
                    OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_UNSPECIFIED)
                .build());
    assertRejected(response -> response.toBuilder().setOutcomeValue(99).build());
    assertRejected(
        response ->
            response.toBuilder()
                .setCreatedReceipt(
                    response.getCreatedReceipt().toBuilder()
                        .setCanonicalTenantId(UUID.randomUUID().toString()))
                .build());
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertRejected(response -> response.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        response ->
            response.toBuilder()
                .setCreatedReceipt(
                    response.getCreatedReceipt().toBuilder().setUnknownFields(unknown))
                .build());
    assertRejected(
        response ->
            response.toBuilder()
                .setOutcome(
                    OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_NOT_CREATED)
                .setCreatedReceipt(ResolveFreshTenantCreationResponse.getDefaultInstance())
                .build());
    assertRejected(response -> response.toBuilder().clearCreatedReceipt().build());
  }

  @Test
  void absencePendingAndOutageArePropagatedWithoutInventingNoCreate() throws Exception {
    var stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("unavailable"));
    when(stub.resolveOriginalTenantCreationOutcome(any())).thenThrow(unavailable);
    assertThatThrownBy(() -> newClient(stub).readOriginal(binding())).isSameAs(unavailable);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);

    for (Status.Code code : List.of(Status.Code.NOT_FOUND, Status.Code.DEADLINE_EXCEEDED)) {
      var absentStub = mockStub();
      StatusRuntimeException absent = new StatusRuntimeException(Status.fromCode(code));
      when(absentStub.resolveOriginalTenantCreationOutcome(any())).thenThrow(absent);
      assertThatThrownBy(() -> newClient(absentStub).readOriginal(binding())).isSameAs(absent);
      verify(absentStub).resolveOriginalTenantCreationOutcome(any());
    }
  }

  @Test
  void rejectsWrongLocalNamespaceAndInvalidBindingsBeforeRpc() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    assertThatThrownBy(
            () ->
                client.readOriginal(
                    new Binding(
                        "other",
                        CREATION_ID,
                        AUTH_OPERATION_ID,
                        ACCOUNT_ID,
                        CREATION_DIGEST,
                        AUTH_DIGEST)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.readOriginal(null)).isInstanceOf(NullPointerException.class);
    verifyNoInteractions(stub);
  }

  @Test
  void rejectsPlaintextAndIncompleteOrClasspathMtlsBeforeChannelCreation() {
    GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameDesignOriginalTenantCreationOutcomeClient(
                    new ServiceEndpointsProperties(), plaintext, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameDesignOriginalTenantCreationOutcomeClient(
                    new ServiceEndpointsProperties(), null, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration");
    for (CommonGrpcClientProperties incomplete :
        new CommonGrpcClientProperties[] {
          mtlsPropertiesWith(null, "key", "ca"),
          mtlsPropertiesWith("cert", null, "ca"),
          mtlsPropertiesWith("cert", "key", null),
          mtlsPropertiesWith("classpath:cert", "key", "ca"),
          mtlsPropertiesWith("cert", "classpath:key", "ca"),
          mtlsPropertiesWith("cert", "key", "classpath:ca")
        }) {
      assertThatThrownBy(
              () ->
                  new GameDesignOriginalTenantCreationOutcomeClient(
                      new ServiceEndpointsProperties(), incomplete, channels, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String namespace : new String[] {null, "", "Upper", "not/a-label"}) {
      assertThatThrownBy(
              () ->
                  new GameDesignOriginalTenantCreationOutcomeClient(
                      new ServiceEndpointsProperties(), mtlsProperties(), channels, namespace))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
    }
    verifyNoInteractions(channels);
  }

  @Test
  void initializedClientUsesFileBackedMtlsAndExactGameDesignPeerIdentity(@TempDir Path directory)
      throws Exception {
    var tls = mtlsProperties(directory);
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design-service.internal:6565");
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    var client =
        new GameDesignOriginalTenantCreationOutcomeClient(endpoints, tls, channelFactory, "test");
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design-service.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
      Field interceptorField =
          GameDesignOriginalTenantCreationOutcomeClient.class.getDeclaredField(
              "serverPeerIdentityInterceptor");
      interceptorField.setAccessible(true);
      var interceptor = (GrpcServerPeerIdentityClientInterceptor) interceptorField.get(client);
      Field uriField =
          GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri");
      uriField.setAccessible(true);
      assertThat(uriField.get(interceptor))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsResponseWhenServerPeerSessionIsUnauthenticated(@TempDir Path directory)
      throws Exception {
    var channel = new UnauthenticatedResponseChannel(responseFor(readRequest(), Outcome.CREATED));
    GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
    when(channels.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    var client =
        new GameDesignOriginalTenantCreationOutcomeClient(
            new ServiceEndpointsProperties(), mtlsProperties(directory), channels, "test");
    try {
      client.init();
      assertThatThrownBy(() -> client.readOriginal(binding()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(channel.lastCallCancelled).isTrue();
    } finally {
      client.close();
    }
    assertThat(channel.isShutdown()).isTrue();
    assertThat(channel.isTerminated()).isTrue();
  }

  private static void assertRejected(
      UnaryOperator<ResolveOriginalTenantCreationOutcomeResponse> mutation) throws Exception {
    var stub = mockStub();
    when(stub.resolveOriginalTenantCreationOutcome(any()))
        .thenAnswer(
            invocation ->
                mutation.apply(
                    responseFor(
                        invocation.getArgument(
                            0, ResolveOriginalTenantCreationOutcomeRequest.class),
                        Outcome.CREATED)));
    assertThatThrownBy(() -> newClient(stub).readOriginal(binding()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GameDesignOriginalTenantCreationOutcomeClient newClient(
      OriginalTenantCreationOutcomeReadServiceGrpc
              .OriginalTenantCreationOutcomeReadServiceBlockingStub
          stub)
      throws Exception {
    var client =
        new GameDesignOriginalTenantCreationOutcomeClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            "test");
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static OriginalTenantCreationOutcomeReadServiceGrpc
          .OriginalTenantCreationOutcomeReadServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            OriginalTenantCreationOutcomeReadServiceGrpc
                .OriginalTenantCreationOutcomeReadServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolveOriginalTenantCreationOutcomeResponse responseFor(
      ResolveOriginalTenantCreationOutcomeRequest request, Outcome outcome) {
    ReadRequest decoded =
        GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest("test", request);
    return GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(decoded, evidence(outcome));
  }

  private static ResolveOriginalTenantCreationOutcomeResponse responseFor(
      ReadRequest request, Outcome outcome) {
    return GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(request, evidence(outcome));
  }

  private static ReadRequest readRequest() {
    return new ReadRequest(READ_ID, binding());
  }

  private static GameTenantCreationOriginalOutcomeEvidence evidence(Outcome outcome) {
    return new GameTenantCreationOriginalOutcomeEvidence(
        1,
        binding(),
        outcome,
        outcome == Outcome.CREATED ? java.util.Optional.of(receipt()) : java.util.Optional.empty());
  }

  private static FreshTenantCreationEvidence receipt() {
    return new FreshTenantCreationEvidence(
        1,
        "test",
        CREATION_ID,
        RECEIPT_OPERATION_ID,
        CREATION_DIGEST,
        TENANT_ID,
        91L,
        "fresh-owner-key-91",
        "NEW_GAME_ROW",
        net.firedevops.firemud.common.tenant.GameTenantCreationDigest.evidenceDigest(
            "test",
            CREATION_ID,
            RECEIPT_OPERATION_ID,
            CREATION_DIGEST,
            TENANT_ID,
            91L,
            "fresh-owner-key-91",
            "NEW_GAME_ROW"));
  }

  private static Binding binding() {
    return new Binding(
        "test", CREATION_ID, AUTH_OPERATION_ID, ACCOUNT_ID, CREATION_DIGEST, AUTH_DIGEST);
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    return mtlsPropertiesWith(
        "certs/account-client.crt", "certs/account-client.key", "certs/gamedesign-ca.crt");
  }

  private static CommonGrpcClientProperties mtlsPropertiesWith(String cert, String key, String ca) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(cert);
    tls.setPrivateKey(key);
    tls.setCaCert(ca);
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    return mtlsPropertiesWith(
        copyCertificateResource("dev-cert.pem", directory).toString(),
        copyCertificateResource("dev-key.pem", directory).toString(),
        copyCertificateResource("dev-ca.pem", directory).toString());
  }

  private static Path copyCertificateResource(String name, Path directory) throws Exception {
    Path destination = directory.resolve(name);
    try (InputStream source =
        GameDesignOriginalTenantCreationOutcomeClientTest.class.getResourceAsStream(
            "/certs/" + name)) {
      if (source == null) throw new IllegalStateException("Missing TLS test resource: " + name);
      Files.copy(source, destination);
    }
    return destination;
  }

  private static final class UnauthenticatedResponseChannel extends ManagedChannel {
    private final byte[] responseBytes;
    private boolean lastCallCancelled;
    private boolean shutdown;

    private UnauthenticatedResponseChannel(ResolveOriginalTenantCreationOutcomeResponse response) {
      responseBytes = response.toByteArray();
    }

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options) {
      return new ClientCall<>() {
        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
          listener.onHeaders(new Metadata());
          listener.onMessage(method.parseResponse(new ByteArrayInputStream(responseBytes)));
          listener.onClose(Status.OK, new Metadata());
        }

        @Override
        public void request(int count) {}

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
      shutdown = true;
      return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }
}
