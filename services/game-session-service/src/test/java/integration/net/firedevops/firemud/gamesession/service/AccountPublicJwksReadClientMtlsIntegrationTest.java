package integration.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountPublicJwksReadServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;
import net.firedevops.firemud.gamesession.service.AccountPublicJwksReadClient;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real GS client and loopback TLS with a controlled Account-shaped producer, not Account source
 * custody or deployment qualification. Only generated protocol and shared codec cross modules.
 */
class AccountPublicJwksReadClientMtlsIntegrationTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final byte[] PUBLIC_BYTES = "{\"keys\":[]}".getBytes(StandardCharsets.US_ASCII);
  @TempDir static Path temporaryDirectory;
  private static Identity ca;
  private static Identity clientIdentity;
  private static Identity accountIdentity;
  private static List<Identity> invalidAccounts;
  private Server server;
  private final AtomicReference<Status> upstreamFailure = new AtomicReference<>();
  private final AtomicReference<GetCurrentAccountPublicJwksResponse> upstreamReply =
      new AtomicReference<>();
  private final AtomicReference<String> clientUri = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<GetCurrentAccountPublicJwksRequest> request =
      new AtomicReference<>();

  @BeforeAll
  static void createControlledTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    ca = issue("jwks-test-ca", null, true, List.of());
    clientIdentity = issue("jwks-test-client", ca, false, List.of(SESSION_URI));
    accountIdentity = issue("jwks-test-account", ca, false, List.of(ACCOUNT_URI));
    invalidAccounts =
        List.of(
            issue(
                "wrong-workload",
                ca,
                false,
                List.of("spiffe://firemud/ns/test/sa/world-management-service")),
            issue(
                "wrong-namespace",
                ca,
                false,
                List.of("spiffe://firemud/ns/other/sa/account-service")),
            issue("no-workload-uri", ca, false, List.of()),
            issue("multiple-workload-uris", ca, false, List.of(ACCOUNT_URI, SESSION_URI)));
  }

  @AfterEach
  void stopServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void actualClientReadsOnlyExactAuthenticatedAccountWithNoBearer() throws Exception {
    start(accountIdentity, response(), false, false);
    ReadResult result = client().readCurrent();
    assertThat(result.sourceIdentity()).isEqualTo(snapshot().sourceIdentity());
    assertThat(result.jwksBytes()).containsExactly(PUBLIC_BYTES);
    assertThat(result.preparedDeny()).isFalse();
    assertThat(result.committedRecoveryEligible()).isFalse();
    assertThat(request.get().getSchemaVersion()).isEqualTo(2);
    assertThat(clientUri.get()).isEqualTo(SESSION_URI);
    assertThat(authorization.get()).isNull();
    assertThat(request.get()).isEqualTo(AccountPublicJwksReadCodec.request());
    byte[] mutable = result.jwksBytes();
    mutable[0] = 0;
    assertThat(result.jwksBytes()).containsExactly(PUBLIC_BYTES);
  }

  @Test
  void rejectsWrongNamespaceWrongWorkloadAbsentAndMultipleUriSans() throws Exception {
    for (Identity invalid : invalidAccounts) {
      for (GetCurrentAccountPublicJwksResponse reply : List.of(response(), denyResponse())) {
        start(invalid, reply, false, false);
        assertThatThrownBy(() -> client().readCurrent()).isInstanceOf(InvalidJwksException.class);
        stopServer();
      }
    }
  }

  @Test
  void exactAuthenticatedAccountCanReturnPreparedDenyThenExplicitEligibleRecovery()
      throws Exception {
    start(accountIdentity, denyResponse(), false, false);
    var reader = client();
    ReadResult denied = reader.readCurrent();
    assertThat(denied.sourceIdentity()).isEqualTo(snapshot().sourceIdentity());
    assertThat(denied.preparedDeny()).isTrue();
    assertThat(denied.committedRecoveryEligible()).isFalse();
    assertThat(denied.jwksBytes()).isNullOrEmpty();
    upstreamReply.set(response());
    assertThat(reader.readCurrent().committedRecoveryEligible()).isFalse();
    upstreamReply.set(
        AccountPublicJwksReadCodec.response(
            new ReadResult(snapshot().sourceIdentity(), PUBLIC_BYTES, false, true)));
    ReadResult recovered = reader.readCurrent();
    assertThat(recovered.preparedDeny()).isFalse();
    assertThat(recovered.committedRecoveryEligible()).isTrue();
    assertThat(recovered.sourceIdentity()).isEqualTo(denied.sourceIdentity());
    assertThat(recovered.jwksBytes()).containsExactly(PUBLIC_BYTES);
    assertThat(authorization.get()).isNull();
  }

  @Test
  void malformedDispositionAndRecoveryEligibilityAreTerminalFromExactAccount() throws Exception {
    start(accountIdentity, response(), false, false);
    var reader = client();
    for (GetCurrentAccountPublicJwksResponse invalid :
        List.of(
            response().toBuilder().setSchemaVersion(1).build(),
            response().toBuilder().clearDisposition().build(),
            response().toBuilder().setDispositionValue(99).build(),
            response().toBuilder().clearCommittedRecoveryEligible().build(),
            denyResponse().toBuilder().setPublicJwks(ByteString.copyFrom(PUBLIC_BYTES)).build(),
            denyResponse().toBuilder().setCommittedRecoveryEligible(false).build(),
            denyResponse().toBuilder().setCommittedRecoveryEligible(true).build(),
            denyResponse().toBuilder().clearSourceIdentity().build())) {
      upstreamReply.set(invalid);
      assertThatThrownBy(reader::readCurrent).isInstanceOf(InvalidJwksException.class);
    }
  }

  @Test
  void boundedDeadlineIsRetryableUnavailability() throws Exception {
    start(accountIdentity, response(), true, false);
    Instant started = Instant.now();
    assertThatThrownBy(() -> client().readCurrent()).isInstanceOf(SourceUnavailableException.class);
    assertThat(Duration.between(started, Instant.now()))
        .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(8));
  }

  @Test
  void reachableUnavailableDiffersFromAuthenticationFailure() throws Exception {
    start(accountIdentity, response(), false, true);
    var reader = client();
    assertThatThrownBy(reader::readCurrent).isInstanceOf(SourceUnavailableException.class);
    upstreamFailure.set(null);
    assertThat(reader.readCurrent().jwksBytes()).containsExactly(PUBLIC_BYTES);
    stopServer();
    start(invalidAccounts.getFirst(), response(), false, true);
    assertThatThrownBy(() -> client().readCurrent()).isInstanceOf(InvalidJwksException.class);
  }

  @Test
  void genericStatusCodesRemainTerminalEvenFromTheExactAccountPeer() throws Exception {
    start(accountIdentity, response(), false, false);
    var reader = client();
    for (Status failure :
        List.of(
            Status.UNKNOWN,
            Status.CANCELLED,
            Status.ABORTED,
            Status.INTERNAL,
            Status.RESOURCE_EXHAUSTED,
            Status.FAILED_PRECONDITION,
            Status.INVALID_ARGUMENT)) {
      upstreamFailure.set(failure);
      assertThatThrownBy(reader::readCurrent).isInstanceOf(InvalidJwksException.class);
    }
  }

  @Test
  void oversizedFrameIsDeniedByActualTransportBeforeCodec() throws Exception {
    var huge =
        response().toBuilder()
            .setPublicJwks(
                ByteString.copyFrom(new byte[AccountPublicJwksReadCodec.MAX_RESPONSE_BYTES + 1]))
            .build();
    start(accountIdentity, huge, false, false);
    assertThatThrownBy(() -> client().readCurrent()).isInstanceOf(InvalidJwksException.class);
  }

  @Test
  void unknownWireFieldsAreInvalidRatherThanOutage() throws Exception {
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    start(accountIdentity, response().toBuilder().setUnknownFields(unknown).build(), false, false);
    assertThatThrownBy(() -> client().readCurrent()).isInstanceOf(InvalidJwksException.class);
  }

  private void start(
      Identity identity,
      GetCurrentAccountPublicJwksResponse reply,
      boolean neverReply,
      boolean unavailable)
      throws Exception {
    upstreamFailure.set(unavailable ? Status.UNAVAILABLE : null);
    upstreamReply.set(reply);
    // This fixture mimics only the private Account read, and enforces the caller's exact URI.
    var producer =
        new AccountPublicJwksReadServiceGrpc.AccountPublicJwksReadServiceImplBase() {
          @Override
          public void getCurrentPublicJwks(
              GetCurrentAccountPublicJwksRequest incoming,
              StreamObserver<GetCurrentAccountPublicJwksResponse> observer) {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null || !SESSION_URI.equals(peer.uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            clientUri.set(peer.uri());
            request.set(incoming);
            AccountPublicJwksReadCodec.requireRequest(incoming);
            Status failure = upstreamFailure.get();
            if (failure != null) observer.onError(failure.asRuntimeException());
            else if (!neverReply) {
              observer.onNext(upstreamReply.get());
              observer.onCompleted();
            }
          }
        };
    ServerInterceptor captureHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            authorization.set(
                headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
            return next.startCall(call, headers);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.key(), identity.certificate()))
                    .trustManager(ca.certificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    producer, captureHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private AccountPublicJwksReadClient client() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(
        pem("client.crt", "CERTIFICATE", clientIdentity.certificate().getEncoded()).toString());
    tls.setPrivateKey(
        pem("client.key", "PRIVATE KEY", clientIdentity.key().getEncoded()).toString());
    tls.setCaCert(pem("ca.crt", "CERTIFICATE", ca.certificate().getEncoded()).toString());
    return new AccountPublicJwksReadClient(endpoints, tls, new GrpcChannelFactory(), "test");
  }

  private static PublicJwksSnapshot snapshot() {
    return new PublicJwksSnapshot(
        new SourceIdentity(
            "test",
            "test-cluster",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "revision-1",
            "https://kubernetes.example:6443",
            "a".repeat(64)),
        PUBLIC_BYTES);
  }

  private static GetCurrentAccountPublicJwksResponse response() {
    return AccountPublicJwksReadCodec.response(snapshot());
  }

  private static GetCurrentAccountPublicJwksResponse denyResponse() {
    return AccountPublicJwksReadCodec.response(
        new ReadResult(snapshot().sourceIdentity(), new byte[0], true, false));
  }

  private static Path pem(String name, String type, byte[] bytes) throws Exception {
    return Files.writeString(
        temporaryDirectory.resolve(name),
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
            + "\n-----END "
            + type
            + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private static Identity issue(String name, Identity issuer, boolean isCa, List<String> uris)
      throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    X500Name subject = new X500Name("CN=" + name);
    X500Name issuerName =
        issuer == null
            ? subject
            : new X500Name(issuer.certificate().getSubjectX500Principal().getName());
    Instant now = Instant.now();
    var builder =
        new JcaX509v3CertificateBuilder(
            issuerName,
            new BigInteger(120, new java.security.SecureRandom()).add(BigInteger.ONE),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plus(Duration.ofDays(1))),
            subject,
            keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(
            isCa
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    if (!isCa) {
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      List<GeneralName> sans = new ArrayList<>();
      sans.add(new GeneralName(GeneralName.dNSName, "localhost"));
      sans.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
      for (String uri : uris) sans.add(new GeneralName(GeneralName.uniformResourceIdentifier, uri));
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(sans.toArray(GeneralName[]::new)));
    }
    var signer =
        new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(issuer == null ? keys.getPrivate() : issuer.key());
    X509Certificate certificate =
        new JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer));
    return new Identity(keys.getPrivate(), certificate);
  }

  private record Identity(PrivateKey key, X509Certificate certificate) {}
}
