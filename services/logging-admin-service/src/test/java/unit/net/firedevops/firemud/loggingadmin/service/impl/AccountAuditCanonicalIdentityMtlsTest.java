package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptResponse;
import net.firedevops.firemud.test.TlsTestSupport;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Physical receiver transport, peer-certificate authorization, and wire-codec proof. */
class AccountAuditCanonicalIdentityMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_PEER_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final UUID TENANT_UUID = UUID.fromString("e90c7b8a-3743-4f1c-9a03-a6e7c1d26211");
  private static final String PAYLOAD_TEXT = "canonical account audit payload";
  private static final String PAYLOAD_DIGEST = digest(PAYLOAD_TEXT);
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static TestPki pki;

  private LogEventService logEventService;
  private Server server;

  @BeforeAll
  static void generateFreshTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Fresh Account Audit Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(caName, caKeyPair.getPrivate(), "logging-admin-server", null),
            issueLeaf(caName, caKeyPair.getPrivate(), "account-service", ACCOUNT_PEER_URI),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-session-service",
                "spiffe://firemud/ns/test/sa/game-session-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-namespace-account-service",
                "spiffe://firemud/ns/other-test/sa/account-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), "shared-workload-certificate", null));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    logEventService = mock(LogEventService.class);
    when(logEventService.createLogEvent(any()))
        .thenAnswer(
            invocation ->
                receipt(invocation.getArgument(0), "create", AccountAuditReceiptOutcome.ACCEPTED));
    when(logEventService.readLogEventReceipt(any()))
        .thenAnswer(
            invocation ->
                receipt(invocation.getArgument(0), "read", AccountAuditReceiptOutcome.DUPLICATE));

    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            mock(LogQueryService.class),
            logEventService,
            mock(ModerationService.class),
            new SimpleMeterRegistry(),
            NAMESPACE);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.serverCertificate().getPrivateKey(),
                            pki.serverCertificate().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void validAccountCanonicalUuidCreateAndReadEchoExactIdentityOverSocketMtls() throws Exception {
    ManagedChannel channel = channel(pki.accountCertificate(), null);
    try {
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = stub(channel);
      CreateLogEventRequest createRequest = request(2, "", TENANT_UUID.toString());
      ReadLogEventReceiptRequest readRequest = readRequest(createRequest);

      CreateLogEventResponse created = stub.createLogEvent(createRequest);
      ReadLogEventReceiptResponse read = stub.readLogEventReceipt(readRequest);

      assertThat(created.getTenantIdentityVersion()).isEqualTo(2);
      assertThat(created.getTenantUuid()).isEqualTo(TENANT_UUID.toString());
      assertThat(created.getTenantId()).isEmpty();
      assertThat(read.getTenantIdentityVersion()).isEqualTo(2);
      assertThat(read.getTenantUuid()).isEqualTo(TENANT_UUID.toString());
      assertThat(read.getTenantId()).isEmpty();
      assertServiceSawIdentity(2, null, TENANT_UUID);
    } finally {
      stopChannel(channel);
    }
  }

  @Test
  void retainedV1NumericCreateAndReadEchoExplicitIdentityOverSocketMtls() throws Exception {
    ManagedChannel channel = channel(pki.accountCertificate(), null);
    try {
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = stub(channel);
      CreateLogEventRequest createRequest = request(1, "42", "");
      ReadLogEventReceiptRequest readRequest = readRequest(createRequest);

      CreateLogEventResponse created = stub.createLogEvent(createRequest);
      ReadLogEventReceiptResponse read = stub.readLogEventReceipt(readRequest);

      assertThat(created.getTenantIdentityVersion()).isEqualTo(1);
      assertThat(created.getTenantId()).isEqualTo("42");
      assertThat(created.getTenantUuid()).isEmpty();
      assertThat(read.getTenantIdentityVersion()).isEqualTo(1);
      assertThat(read.getTenantId()).isEqualTo("42");
      assertThat(read.getTenantUuid()).isEmpty();
      assertServiceSawIdentity(1, 42L, null);
    } finally {
      stopChannel(channel);
    }
  }

  @Test
  void retainedV1PlatformCreateAndReadEchoExplicitIdentityOverSocketMtls() throws Exception {
    ManagedChannel channel = channel(pki.accountCertificate(), null);
    try {
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = stub(channel);
      CreateLogEventRequest createRequest =
          request(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM, 1, "", "");
      ReadLogEventReceiptRequest readRequest = readRequest(createRequest);

      CreateLogEventResponse created = stub.createLogEvent(createRequest);
      ReadLogEventReceiptResponse read = stub.readLogEventReceipt(readRequest);

      assertThat(created.getTenantIdentityVersion()).isEqualTo(1);
      assertThat(created.getTenantId()).isEmpty();
      assertThat(created.getTenantUuid()).isEmpty();
      assertThat(read.getTenantIdentityVersion()).isEqualTo(1);
      assertThat(read.getTenantId()).isEmpty();
      assertThat(read.getTenantUuid()).isEmpty();
      assertServiceSawIdentity(1, null, null);
    } finally {
      stopChannel(channel);
    }
  }

  @Test
  void missingClientCertificateIsRejectedByTls() throws Exception {
    ManagedChannel channel = channel(null, null);
    try {
      assertTlsDenied(stub(channel), request(2, "", TENANT_UUID.toString()));
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(logEventService);
  }

  @Test
  void wrongServiceValidCaCertificateCannotInvokeEitherAccountAuditMethod() throws Exception {
    assertWrongWorkloadCannotInvokeEitherMethod(pki.wrongServiceCertificate());
  }

  @Test
  void wrongNamespaceCertificateCannotInvokeEitherAccountAuditMethod() throws Exception {
    assertWrongWorkloadCannotInvokeEitherMethod(pki.wrongNamespaceCertificate());
  }

  @Test
  void sharedCertificateWithoutWorkloadUriCannotInvokeEitherAccountAuditMethod() throws Exception {
    assertWrongWorkloadCannotInvokeEitherMethod(pki.noWorkloadUriCertificate());
  }

  @Test
  void jwtOnlyCallerCannotBypassMissingClientCertificate() throws Exception {
    Metadata headers = new Metadata();
    headers.put(AUTHORIZATION, "Bearer header.payload.signature");
    ManagedChannel channel = channel(null, headers);
    try {
      assertTlsDenied(stub(channel), request(2, "", TENANT_UUID.toString()));
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(logEventService);
  }

  @Test
  void mixedAndUnknownTenantIdentityFailBeforeLogEventServiceInvocation() throws Exception {
    ManagedChannel channel = channel(pki.accountCertificate(), null);
    try {
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = stub(channel);
      CreateLogEventRequest mixedV1 = request(1, "42", TENANT_UUID.toString());
      CreateLogEventRequest unknownVersion = request(3, "", TENANT_UUID.toString());
      CreateLogEventRequest unsetVersion = request(0, "", "");

      assertApplicationDeniedAsInvalidArgument(() -> stub.createLogEvent(mixedV1));
      assertApplicationDeniedAsInvalidArgument(
          () -> stub.readLogEventReceipt(readRequest(mixedV1)));
      assertApplicationDeniedAsInvalidArgument(() -> stub.createLogEvent(unknownVersion));
      assertApplicationDeniedAsInvalidArgument(
          () -> stub.readLogEventReceipt(readRequest(unknownVersion)));
      assertApplicationDeniedAsInvalidArgument(() -> stub.createLogEvent(unsetVersion));
      assertApplicationDeniedAsInvalidArgument(
          () -> stub.readLogEventReceipt(readRequest(unsetVersion)));
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(logEventService);
  }

  private void assertWrongWorkloadCannotInvokeEitherMethod(TestCertificate certificate)
      throws Exception {
    ManagedChannel channel = channel(certificate, null);
    try {
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = stub(channel);
      CreateLogEventRequest create = request(2, "", TENANT_UUID.toString());
      assertApplicationDeniedAsPermissionDenied(() -> stub.createLogEvent(create));
      assertApplicationDeniedAsPermissionDenied(
          () -> stub.readLogEventReceipt(readRequest(create)));
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(logEventService);
  }

  private void assertTlsDenied(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub, CreateLogEventRequest request) {
    Throwable createFailure = catchFailure(() -> stub.createLogEvent(request));
    assertTlsRejection(createFailure, "CreateLogEvent");
    Throwable readFailure = catchFailure(() -> stub.readLogEventReceipt(readRequest(request)));
    assertTlsRejection(readFailure, "ReadLogEventReceipt");
  }

  private static void assertTlsRejection(Throwable failure, String method) {
    assertThat(failure)
        .as("%s must fail as a gRPC call with TLS rejection: %s", method, diagnostics(failure))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode())
        .as("%s must not be accepted as a transport timeout: %s", method, diagnostics(failure))
        .isNotEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
        .as(
            "%s cause chain must identify TLS certificate rejection: %s",
            method, diagnostics(failure))
        .isTrue();
  }

  private static void assertApplicationDeniedAsPermissionDenied(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private static void assertApplicationDeniedAsInvalidArgument(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  private void assertServiceSawIdentity(int version, Long tenantId, UUID tenantUuid) {
    ArgumentCaptor<net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest> createCaptor =
        ArgumentCaptor.forClass(
            net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest.class);
    ArgumentCaptor<net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest> readCaptor =
        ArgumentCaptor.forClass(
            net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest.class);
    verify(logEventService).createLogEvent(createCaptor.capture());
    verify(logEventService).readLogEventReceipt(readCaptor.capture());
    assertThat(createCaptor.getValue().tenantIdentityVersion()).isEqualTo(version);
    assertThat(createCaptor.getValue().tenantId()).isEqualTo(tenantId);
    assertThat(createCaptor.getValue().tenantUuid()).isEqualTo(tenantUuid);
    assertThat(readCaptor.getValue().tenantIdentityVersion()).isEqualTo(version);
    assertThat(readCaptor.getValue().tenantId()).isEqualTo(tenantId);
    assertThat(readCaptor.getValue().tenantUuid()).isEqualTo(tenantUuid);
  }

  private static LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub(
      ManagedChannel channel) {
    return LoggingAdminServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private ManagedChannel channel(TestCertificate clientCertificate, Metadata headers)
      throws Exception {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .sslContext(clientSslContext(clientCertificate));
    if (headers != null) {
      builder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    return builder.build();
  }

  private static io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSslContext(
      TestCertificate clientCertificate) throws Exception {
    var builder = GrpcSslContexts.forClient().trustManager(pki.caCertificate());
    if (clientCertificate != null) {
      builder.keyManager(clientCertificate.getPrivateKey(), clientCertificate.certificate());
    }
    return builder.build();
  }

  private static CreateLogEventRequest request(
      int tenantIdentityVersion, String tenantId, String tenantUuid) {
    return request(
        AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT, tenantIdentityVersion, tenantId, tenantUuid);
  }

  private static CreateLogEventRequest request(
      AccountAuditScope scope, int tenantIdentityVersion, String tenantId, String tenantUuid) {
    ByteString payload = ByteString.copyFrom(PAYLOAD_TEXT, StandardCharsets.UTF_8);
    return CreateLogEventRequest.newBuilder()
        .setScope(scope)
        .setTenantIdentityVersion(tenantIdentityVersion)
        .setTenantId(tenantId)
        .setTenantUuid(tenantUuid)
        .setAuditEventId("11111111-1111-4111-8111-111111111111")
        .setProducerService("account-service")
        .setEventType("ACCOUNT_CREATED")
        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_000_000))
        .setSchemaVersion(1)
        .setPayload(payload)
        .setPayloadDigestVersion(1)
        .setPayloadDigest(PAYLOAD_DIGEST)
        .build();
  }

  private static ReadLogEventReceiptRequest readRequest(CreateLogEventRequest request) {
    return ReadLogEventReceiptRequest.newBuilder()
        .setScope(request.getScope())
        .setTenantIdentityVersion(request.getTenantIdentityVersion())
        .setTenantId(request.getTenantId())
        .setTenantUuid(request.getTenantUuid())
        .setAuditEventId(request.getAuditEventId())
        .setProducerService(request.getProducerService())
        .setEventType(request.getEventType())
        .setOccurredAt(request.getOccurredAt())
        .setSchemaVersion(request.getSchemaVersion())
        .setPayload(request.getPayload())
        .setPayloadDigestVersion(request.getPayloadDigestVersion())
        .setPayloadDigest(request.getPayloadDigest())
        .build();
  }

  private static Throwable catchFailure(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable throwable) {
      return throwable;
    }
  }

  private static String diagnostics(Throwable throwable) {
    if (throwable == null) {
      return "RPC unexpectedly completed";
    }
    StringBuilder result = new StringBuilder();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (!result.isEmpty()) {
        result.append(" <- ");
      }
      result.append(current.getClass().getName()).append(": ").append(current.getMessage());
    }
    return result.toString();
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static AccountAuditReceiptDto receipt(
      net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest request,
      String operation,
      AccountAuditReceiptOutcome outcome) {
    return new AccountAuditReceiptDto(
        request.scope(),
        request.tenantIdentityVersion(),
        request.tenantId(),
        request.tenantUuid(),
        request.auditEventId(),
        operation + "-receipt",
        731L,
        request.schemaVersion(),
        request.payloadDigestVersion(),
        request.payloadDigest(),
        AccountAuditReceiptStatus.COMMITTED,
        outcome);
  }

  private static String digest(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (Exception exception) {
      throw new AssertionError("unable to calculate deterministic test payload digest", exception);
    }
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String commonName, String workloadUri)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralName[] subjectAltNames =
        workloadUri == null
            ? new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }
            : new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            };
    X509Certificate certificate =
        issueCertificate(
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            new GeneralNames(subjectAltNames));
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
    Instant now = Instant.now().minusSeconds(60);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(now),
            Date.from(now.plusSeconds(60L * 60L * 24L * 14L)),
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

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {
    PrivateKey getPrivateKey() {
      return privateKey;
    }
  }

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate serverCertificate,
      TestCertificate accountCertificate,
      TestCertificate wrongServiceCertificate,
      TestCertificate wrongNamespaceCertificate,
      TestCertificate noWorkloadUriCertificate) {}
}
