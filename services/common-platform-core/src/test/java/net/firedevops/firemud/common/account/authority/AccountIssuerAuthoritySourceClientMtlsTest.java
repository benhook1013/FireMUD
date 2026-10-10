package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
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
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceServiceGrpc;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical file-backed workload mTLS transport proof against an issuer-owner transport double. */
class AccountIssuerAuthoritySourceClientMtlsTest {
  private static final String NAMESPACE = "firemud";
  private static final String CALLER = "spiffe://firemud/ns/firemud/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  @Test
  void revalidatesSequenceZeroAndPositiveSourcesExactlyOverFileBackedMtls(@TempDir Path directory)
      throws Exception {
    var clientTls = writeClientTlsMaterial(directory);
    var serverTls = writeServerTlsMaterial(directory, ServerIdentity.ACCOUNT);
    IssuerOwnerTransportDouble owner = new IssuerOwnerTransportDouble();
    try (TransportServer server = startServer(serverTls, clientTls.caCertificate(), owner);
        AccountIssuerAuthoritySourceClient client = newClient(server.port(), clientTls)) {
      client.init();

      UUID zeroOperation = UUID.randomUUID();
      var initialZero = client.read(request(zeroOperation, Optional.empty()));
      assertThat(initialZero.lastCommittedOutboxSequence()).isEqualTo("0");
      assertThat(initialZero.sourceEvent()).isEmpty();
      assertThat(client.read(request(zeroOperation, Optional.of(initialZero))))
          .isEqualTo(initialZero);

      UUID positiveOperation = UUID.randomUUID();
      var positive = source(positiveOperation, "1", "2");
      owner.setCurrentSource(positive);
      assertThat(client.read(request(positiveOperation, Optional.of(positive))))
          .isEqualTo(positive);
      assertThat(positive.sourceEvent()).isPresent();

      assertThat(owner.observation.metadataCount()).isEqualTo(3);
      assertThat(owner.observation.messageCount()).isEqualTo(3);
      assertThat(owner.ownerReadCount()).isEqualTo(3);
    }
  }

  @Test
  void rejectsBadUnknownAndChangedSourceResponsesFailClosed(@TempDir Path directory)
      throws Exception {
    var clientTls = writeClientTlsMaterial(directory);
    var serverTls = writeServerTlsMaterial(directory, ServerIdentity.ACCOUNT);
    IssuerOwnerTransportDouble owner = new IssuerOwnerTransportDouble();
    try (TransportServer server = startServer(serverTls, clientTls.caCertificate(), owner);
        AccountIssuerAuthoritySourceClient client = newClient(server.port(), clientTls)) {
      client.init();
      UUID operation = UUID.randomUUID();
      var expected = source(operation, "1", "2");
      owner.setCurrentSource(expected);

      owner.setResponseMode(ResponseMode.MISSING_FIELDS);
      assertThatThrownBy(() -> client.read(request(operation, Optional.of(expected))))
          .isInstanceOf(IllegalArgumentException.class);

      owner.setResponseMode(ResponseMode.UNKNOWN_FIELD);
      assertThatThrownBy(() -> client.read(request(operation, Optional.of(expected))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown protobuf fields");

      owner.setResponseMode(ResponseMode.CHANGED_SOURCE);
      assertThatThrownBy(() -> client.read(request(operation, Optional.of(expected))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact expected snapshot");

      assertThat(owner.observation.metadataCount()).isEqualTo(3);
      assertThat(owner.observation.messageCount()).isEqualTo(3);
      assertThat(owner.ownerReadCount()).isEqualTo(3);
    }
  }

  @Test
  void wrongAccountServiceOrNamespacePeerIsRejectedBeforeAnyRpcMetadataOrOwnerRead(
      @TempDir Path directory) throws Exception {
    var clientTls = writeClientTlsMaterial(directory);
    for (ServerIdentity identity :
        new ServerIdentity[] {ServerIdentity.WRONG_SERVICE, ServerIdentity.WRONG_NAMESPACE}) {
      Path serverDirectory = Files.createDirectories(directory.resolve(identity.name()));
      var serverTls = writeServerTlsMaterial(serverDirectory, identity);
      IssuerOwnerTransportDouble owner = new IssuerOwnerTransportDouble();
      try (TransportServer server = startServer(serverTls, clientTls.caCertificate(), owner);
          AccountIssuerAuthoritySourceClient client = newClient(server.port(), clientTls)) {
        client.init();
        UUID operation = UUID.randomUUID();
        assertThatThrownBy(() -> client.read(request(operation, Optional.empty())))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
            .isEqualTo(Status.Code.UNAUTHENTICATED);

        assertThat(owner.observation.metadataCount()).isZero();
        assertThat(owner.observation.messageCount()).isZero();
        assertThat(owner.ownerReadCount()).isZero();
      }
    }
  }

  static ClientTlsMaterial writeClientTlsMaterial(Path directory) throws IOException {
    return writeClientTlsMaterial(directory, false);
  }

  static ClientTlsMaterial writeClientTlsMaterial(Path directory, boolean wrongIdentity)
      throws IOException {
    Files.createDirectories(directory);
    Path ca = write(directory.resolve("test-ca.pem"), TEST_CA);
    Path clientCertificate =
        write(
            directory.resolve("client.crt"),
            wrongIdentity ? WRONG_NAMESPACE_SERVER_CERTIFICATE : CLIENT_CERTIFICATE);
    Path clientPrivateKey = write(directory.resolve("client.key"), CLIENT_PRIVATE_KEY);
    return new ClientTlsMaterial(ca, clientCertificate, clientPrivateKey);
  }

  private static ServerTlsMaterial writeServerTlsMaterial(Path directory, ServerIdentity identity)
      throws IOException {
    Files.createDirectories(directory);
    Path ca = write(directory.resolve("test-ca.pem"), TEST_CA);
    return switch (identity) {
      case ACCOUNT ->
          new ServerTlsMaterial(
              ca,
              write(directory.resolve("server.crt"), ACCOUNT_SERVER_CERTIFICATE),
              write(directory.resolve("server.key"), ACCOUNT_SERVER_PRIVATE_KEY));
      case WRONG_SERVICE ->
          new ServerTlsMaterial(
              ca,
              write(directory.resolve("server.crt"), WRONG_SERVICE_SERVER_CERTIFICATE),
              write(directory.resolve("server.key"), WRONG_SERVICE_SERVER_PRIVATE_KEY));
      case WRONG_NAMESPACE ->
          new ServerTlsMaterial(
              ca,
              write(directory.resolve("server.crt"), WRONG_NAMESPACE_SERVER_CERTIFICATE),
              write(directory.resolve("server.key"), WRONG_NAMESPACE_SERVER_PRIVATE_KEY));
    };
  }

  private static TransportServer startServer(
      ServerTlsMaterial serverTls, Path trustedClientCa, IssuerOwnerTransportDouble owner)
      throws Exception {
    var sslContext =
        GrpcSslContexts.forServer(
                serverTls.serverCertificate().toFile(), serverTls.serverPrivateKey().toFile())
            .trustManager(trustedClientCa.toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    Server server =
        NettyServerBuilder.forPort(0)
            .sslContext(sslContext)
            .addService(
                ServerInterceptors.intercept(
                    owner, owner.observation, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new TransportServer(server);
  }

  private static AccountIssuerAuthoritySourceClient newClient(
      int port, ClientTlsMaterial tlsMaterial) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(tlsMaterial.clientCertificate().toString());
    tls.setPrivateKey(tlsMaterial.clientPrivateKey().toString());
    tls.setCaCert(tlsMaterial.caCertificate().toString());
    return new AccountIssuerAuthoritySourceClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private static AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request(
      UUID operation, Optional<AccountIssuerSourceSnapshotEvidence> expected) {
    return new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
        operation, NAMESPACE, ISSUER, expected);
  }

  private static AccountIssuerSourceSnapshotEvidence source(
      UUID operation, String sequence, String generation) {
    Optional<String> event = Optional.empty();
    if (!"0".equals(sequence)) {
      event =
          Optional.of(
              AccountAuthoritySourceEventV1Codec.sealIssuer(
                      new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                          operation.toString(),
                          operation.toString(),
                          STREAM,
                          sequence,
                          ISSUER,
                          generation,
                          generation,
                          "SIGNER_COMPROMISE"))
                  .canonicalJson());
    }
    return new AccountIssuerSourceSnapshotEvidence(
        operation, NAMESPACE, CALLER, ISSUER, generation, generation, STREAM, sequence, event);
  }

  private static Path write(Path path, String contents) throws IOException {
    return Files.writeString(path, contents);
  }

  record ClientTlsMaterial(Path caCertificate, Path clientCertificate, Path clientPrivateKey) {}

  private record ServerTlsMaterial(
      Path caCertificate, Path serverCertificate, Path serverPrivateKey) {}

  private record TransportServer(Server server) implements AutoCloseable {
    int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow().awaitTermination();
    }
  }

  private enum ServerIdentity {
    ACCOUNT,
    WRONG_SERVICE,
    WRONG_NAMESPACE
  }

  private enum ResponseMode {
    CURRENT,
    MISSING_FIELDS,
    UNKNOWN_FIELD,
    CHANGED_SOURCE
  }

  private static final class TransportObservation implements ServerInterceptor {
    private final AtomicInteger metadataCount = new AtomicInteger();
    private final AtomicInteger messageCount = new AtomicInteger();

    @Override
    public <RequestT, ResponseT> io.grpc.ServerCall.Listener<RequestT> interceptCall(
        ServerCall<RequestT, ResponseT> call,
        Metadata headers,
        ServerCallHandler<RequestT, ResponseT> next) {
      metadataCount.incrementAndGet();
      return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
        @Override
        public void onMessage(RequestT message) {
          messageCount.incrementAndGet();
          super.onMessage(message);
        }
      };
    }

    int metadataCount() {
      return metadataCount.get();
    }

    int messageCount() {
      return messageCount.get();
    }
  }

  /** Synthetic owner transport fixture; it does not execute or claim Account database semantics. */
  private static final class IssuerOwnerTransportDouble
      extends AccountIssuerAuthoritySourceServiceGrpc.AccountIssuerAuthoritySourceServiceImplBase {
    private final TransportObservation observation = new TransportObservation();
    private final AtomicInteger ownerReadCount = new AtomicInteger();
    private volatile AccountIssuerSourceSnapshotEvidence currentSource;
    private volatile ResponseMode responseMode = ResponseMode.CURRENT;

    @Override
    public void readCurrentIssuerAuthoritySource(
        ReadCurrentIssuerAuthoritySourceRequest request,
        StreamObserver<AccountIssuerAuthoritySourceSnapshot> responseObserver) {
      ownerReadCount.incrementAndGet();
      try {
        GrpcPeerIdentity caller = GrpcPeerIdentity.current();
        if (caller == null || !CALLER.equals(caller.uri())) {
          responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());
          return;
        }
        var parsedRequest =
            AccountIssuerSourceSnapshotGrpcCodec.fromRequest(request, NAMESPACE, caller.uri());
        AccountIssuerSourceSnapshotEvidence responseSource = currentSource;
        if (responseSource == null && parsedRequest.expectedSource().isPresent()) {
          responseSource = parsedRequest.expectedSource().orElseThrow();
        }
        if (responseSource == null) {
          responseSource = source(parsedRequest.reconciliationOperationId(), "0", "1");
          currentSource = responseSource;
        }

        AccountIssuerAuthoritySourceSnapshot wireResponse;
        switch (responseMode) {
          case MISSING_FIELDS ->
              wireResponse =
                  AccountIssuerAuthoritySourceSnapshot.newBuilder()
                      .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
                      .build();
          case UNKNOWN_FIELD ->
              wireResponse =
                  AccountIssuerSourceSnapshotGrpcCodec.toWire(responseSource).toBuilder()
                      .setUnknownFields(
                          UnknownFieldSet.newBuilder()
                              .addField(
                                  99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                              .build())
                      .build();
          case CHANGED_SOURCE ->
              wireResponse =
                  AccountIssuerSourceSnapshotGrpcCodec.toWire(
                      source(parsedRequest.reconciliationOperationId(), "2", "3"));
          case CURRENT ->
              wireResponse =
                  AccountIssuerSourceSnapshotGrpcCodec.toResponse(
                      responseSource, parsedRequest, caller.uri());
          default -> throw new IllegalStateException("unsupported transport-double response mode");
        }
        responseObserver.onNext(wireResponse);
        responseObserver.onCompleted();
      } catch (RuntimeException failure) {
        responseObserver.onError(Status.INVALID_ARGUMENT.withCause(failure).asRuntimeException());
      }
    }

    int ownerReadCount() {
      return ownerReadCount.get();
    }

    void setCurrentSource(AccountIssuerSourceSnapshotEvidence source) {
      currentSource = source;
    }

    void setResponseMode(ResponseMode mode) {
      responseMode = mode;
    }
  }

  private static final String TEST_CA =
      """
      -----BEGIN CERTIFICATE-----
      MIIBtTCCAVugAwIBAgIUEVP0Q8L5Ora/p1kDdJlLB272mNMwCgYIKoZIzj0EAwIw
      KDEmMCQGA1UEAwwdRmlyZU1VRCBpc3N1ZXItc291cmNlIHRlc3QgQ0EwHhcNMjYx
      MDA4MDIyNjQ4WhcNNDYxMDAzMDIyNjQ4WjAoMSYwJAYDVQQDDB1GaXJlTVVEIGlz
      c3Vlci1zb3VyY2UgdGVzdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAqz
      jOqtLuabC4VgS1wSj+BNxfFj5P4sPKJ0JxVw1k0rg2DJlGmCLPPU0cq8ow7DgtU0
      Gmi2BoLCuMOWlW719S+jYzBhMB8GA1UdIwQYMBaAFDcLkswEeTQ+ubifCjqS//hA
      nQAAMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMB0GA1UdDgQWBBQ3
      C5LMBHk0Prm4nwo6kv/4QJ0AADAKBggqhkjOPQQDAgNIADBFAiEA+sYWAP6HFISr
      qGcbHAXzA9SgrH7nHWVwRX2vgRoA7DMCICnfSOJCWGedTmGbaT2m8xjpDMlgvnrL
      Vwi8c/15tJei
      -----END CERTIFICATE-----
      """;

  private static final String CLIENT_CERTIFICATE =
      """
      -----BEGIN CERTIFICATE-----
      MIICBTCCAaugAwIBAgIUIbUYSjzVvJpHUY1Y0zoWfQiI9SIwCgYIKoZIzj0EAwIw
      KDEmMCQGA1UEAwwdRmlyZU1VRCBpc3N1ZXItc291cmNlIHRlc3QgQ0EwHhcNMjYx
      MDA4MDIyNjQ4WhcNNDYxMDAzMDIyNjQ4WjAkMSIwIAYDVQQDDBlmaXJlbXVkIGdh
      bWUtc2Vzc2lvbiB0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE95+zYl2Q
      C1ply40Sa76hBaIBQ3xGRydNUbOq0aie9RWWlhe7Hdfj1jiWa+TfMvDo4g49tOxb
      re8OlicZ4c2QXqOBtjCBszAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIHgDAT
      BgNVHSUEDDAKBggrBgEFBQcDAjA+BgNVHREENzA1hjNzcGlmZmU6Ly9maXJlbXVk
      L25zL2ZpcmVtdWQvc2EvZ2FtZS1zZXNzaW9uLXNlcnZpY2UwHQYDVR0OBBYEFF+C
      ZbOODKc5aG50EuJcDYrMvqG4MB8GA1UdIwQYMBaAFDcLkswEeTQ+ubifCjqS//hA
      nQAAMAoGCCqGSM49BAMCA0gAMEUCIQCpKhU72Vr+0J/ecKtssCdwL7ZpW/7/tEKu
      TqBG3a212gIgZ/d+bD5cEXnHH2s9FiHtthuiJ5TG/M82vsmhLWUWD+I=
      -----END CERTIFICATE-----
      """;

  private static final String CLIENT_PRIVATE_KEY =
      """
      -----BEGIN PRIVATE KEY-----
      MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgL7a8bTP2x/haYtxx
      ZmHwnXgNeLo+ok4/oOTcChj5IByhRANCAAT3n7NiXZALWmXLjRJrvqEFogFDfEZH
      J01Rs6rRqJ71FZaWF7sd1+PWOJZr5N8y8OjiDj207Fut7w6WJxnhzZBe
      -----END PRIVATE KEY-----
      """;

  private static final String ACCOUNT_SERVER_CERTIFICATE =
      """
      -----BEGIN CERTIFICATE-----
      MIICBzCCAaygAwIBAgIUM7M4f8TIsmepkFGPEGvzno7AhlAwCgYIKoZIzj0EAwIw
      KDEmMCQGA1UEAwwdRmlyZU1VRCBpc3N1ZXItc291cmNlIHRlc3QgQ0EwHhcNMjYx
      MDA4MDIyNjQ4WhcNNDYxMDAzMDIyNjQ4WjAfMR0wGwYDVQQDDBRmaXJlbXVkIGFj
      Y291bnQgdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABJYzdFJrNpJomn8o
      vTyLc+w0O6x9ZJH0Ve7DqrpL4151fWjv+QSyunwogecWWDtXyBJg0t1+E1izowFO
      Dm/NxKCjgbwwgbkwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0l
      BAwwCgYIKwYBBQUHAwEwRAYDVR0RBD0wO4IJbG9jYWxob3N0hi5zcGlmZmU6Ly9m
      aXJlbXVkL25zL2ZpcmVtdWQvc2EvYWNjb3VudC1zZXJ2aWNlMB0GA1UdDgQWBBRP
      u+0KoAlDJBd9BZIcftifunH/nDAfBgNVHSMEGDAWgBQ3C5LMBHk0Prm4nwo6kv/4
      QJ0AADAKBggqhkjOPQQDAgNJADBGAiEArSG4oVkjw24XI04ckfdefWeX0FAbo4n5
      uKAGNu+vJjYCIQDO0K7fzv/sD76KIAe6lCjO7uWIMqrXgYcxKM87WtZZwA==
      -----END CERTIFICATE-----
      """;

  private static final String ACCOUNT_SERVER_PRIVATE_KEY =
      """
      -----BEGIN PRIVATE KEY-----
      MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgsF8YPfzEzsLMcFwK
      jDNpeuA/UYR01ZJJHggn1GuDGRShRANCAASWM3RSazaSaJp/KL08i3PsNDusfWSR
      9FXuw6q6S+NedX1o7/kEsrp8KIHnFlg7V8gSYNLdfhNYs6MBTg5vzcSg
      -----END PRIVATE KEY-----
      """;

  private static final String WRONG_SERVICE_SERVER_CERTIFICATE =
      """
      -----BEGIN CERTIFICATE-----
      MIICEDCCAbagAwIBAgIUUoUlbNguY4u1g9cd3CTkLx1ph14wCgYIKoZIzj0EAwIw
      KDEmMCQGA1UEAwwdRmlyZU1VRCBpc3N1ZXItc291cmNlIHRlc3QgQ0EwHhcNMjYx
      MDA4MDIyNjQ4WhcNNDYxMDAzMDIyNjQ4WjAlMSMwIQYDVQQDDBpmaXJlbXVkIHdy
      b25nIHNlcnZpY2UgdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABIfFrRHj
      wS3v8/1NseHlHaVgUYBr+qPVgvCNTgOigjT/S/zQ7ObTK0QgjJQkm6qhWhtNNWVb
      ViPeGKieLlncC02jgcAwgb0wDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4Aw
      EwYDVR0lBAwwCgYIKwYBBQUHAwEwSAYDVR0RBEEwP4IJbG9jYWxob3N0hjJzcGlm
      ZmU6Ly9maXJlbXVkL25zL2ZpcmVtdWQvc2EvZ2FtZS1kZXNpZ24tc2VydmljZTAd
      BgNVHQ4EFgQUAqO+RBJqRVOb8ivvTyWWpWp5QK0wHwYDVR0jBBgwFoAUNwuSzAR5
      ND65uJ8KOpL/+ECdAAAwCgYIKoZIzj0EAwIDSAAwRQIhAIgMjCy7nTnqcGBNexw4
      hKCu4IvM6sYc/yRmzcQ/pq2GAiBQJZOUStWnGJKz35ODoZHndOCSGE3J/ZMehFrW
      3EF8Rg==
      -----END CERTIFICATE-----
      """;

  private static final String WRONG_SERVICE_SERVER_PRIVATE_KEY =
      """
      -----BEGIN PRIVATE KEY-----
      MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgU/N/DJ4u72eEVy6M
      lBc2ZZoa0id2EkrAXbEIzsbjDYKhRANCAASHxa0R48Et7/P9TbHh5R2lYFGAa/qj
      1YLwjU4DooI0/0v80Ozm0ytEIIyUJJuqoVobTTVlW1Yj3hioni5Z3AtN
      -----END PRIVATE KEY-----
      """;

  private static final String WRONG_NAMESPACE_SERVER_CERTIFICATE =
      """
      -----BEGIN CERTIFICATE-----
      MIICDTCCAbKgAwIBAgIUTXRuwVMIcnUSv5EIbCG1IIInpocwCgYIKoZIzj0EAwIw
      KDEmMCQGA1UEAwwdRmlyZU1VRCBpc3N1ZXItc291cmNlIHRlc3QgQ0EwHhcNMjYx
      MDA4MDIyNjQ4WhcNNDYxMDAzMDIyNjQ4WjAnMSUwIwYDVQQDDBxmaXJlbXVkIHdy
      b25nIG5hbWVzcGFjZSB0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqnH+
      OVHMgjnsqHMj2JcGkztSnOzCGrRdMgmqPjQoU6aQKQ6kW6g9QIf+IRuypZqP5gWu
      7P2lpxEiSSwo0tRulKOBujCBtzAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIH
      gDATBgNVHSUEDDAKBggrBgEFBQcDATBCBgNVHREEOzA5gglsb2NhbGhvc3SGLHNw
      aWZmZTovL2ZpcmVtdWQvbnMvb3RoZXIvc2EvYWNjb3VudC1zZXJ2aWNlMB0GA1Ud
      DgQWBBTQ0iPp7mEFoUWE6jUas8gHr/t6KjAfBgNVHSMEGDAWgBQ3C5LMBHk0Prm4
      nwo6kv/4QJ0AADAKBggqhkjOPQQDAgNJADBGAiEAnmXME6WMdN0ORDptJ0FE1uTM
      Muuiz2Z7DbIBd3YihwgCIQCtEaz1wc3t/ecLLNlgy6311eXLc+XTM1ijnLhxFClj
      iA==
      -----END CERTIFICATE-----
      """;

  private static final String WRONG_NAMESPACE_SERVER_PRIVATE_KEY =
      """
      -----BEGIN PRIVATE KEY-----
      MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg92jz3gfsT5pGQ8+z
      Iqw5o8rUe1CwrS3rOs/aEDdK9RShRANCAASqcf45UcyCOeyocyPYlwaTO1Kc7MIa
      tF0yCao+NChTppApDqRbqD1Ah/4hG7Klmo/mBa7s/aWnESJJLCjS1G6U
      -----END PRIVATE KEY-----
      """;
}
