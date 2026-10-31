package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.config.AccountAuditGrpcAuthConfiguration;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptRequest;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Physical TLS caller-policy proof with a mocked receipt owner, not durable storage proof. */
class AccountAuditPeerGrpcMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String EVENT_ID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String RECEIPT_ID = "d2719d4f-3b2a-4f64-a994-0f9ccdfdd2b3";
  private static final String PAYLOAD = "{\"auditMarker\":\"loopback-read\"}";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T12:00:00.123456789Z");
  private static final String JWT_SECRET =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String CLAIM_EVIDENCE_METHOD =
      "logging_admin.v1.StartSessionReservationEvidenceService/ReadCurrentClaimEvidence";

  @TempDir static Path temporaryDirectory;

  private static LoopbackMtlsTestPki.Pki pki;
  private static AuthTokenInterceptor authInterceptor;
  private static String payloadDigest;

  @BeforeAll
  static void createTrustedWorkloadCertificatesAndSelectProductionAuthInterceptor()
      throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temporaryDirectory,
            "FireMUD Account audit peer test CA",
            new IdentitySpec("logging", "spiffe://firemud/ns/test/sa/logging-admin-service", true),
            new IdentitySpec("account", ACCOUNT_URI, false),
            new IdentitySpec(
                "world", "spiffe://firemud/ns/test/sa/world-management-service", false),
            new IdentitySpec(
                "other-account", "spiffe://firemud/ns/other/sa/account-service", false));
    payloadDigest =
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withUserConfiguration(AccountAuditGrpcAuthConfiguration.class)
        .withPropertyValues(
            "firemud.auth.jwt-secret=" + JWT_SECRET,
            "firemud.auth.grpc.public-methods[0]=" + CLAIM_EVIDENCE_METHOD)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .containsExactly(CLAIM_EVIDENCE_METHOD);
              authInterceptor = context.getBean(AuthTokenInterceptor.class);
              assertThat(authInterceptor)
                  .isSameAs(context.getBean("accountAuditAuthTokenInterceptor"));
            });
  }

  @Test
  void exactAccountCertificateReachesCreateOnlyAsUnavailableWithoutOwnerAccess() throws Exception {
    LogEventService owner = mock(LogEventService.class);
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), "account")) {
      assertStatus(Status.Code.UNAVAILABLE, () -> client.stub().createLogEvent(createRequest()));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void exactAccountCertificateReadsCanonicalReceiptWithoutJwtFromMockedOwner() throws Exception {
    LogEventService owner = mock(LogEventService.class);
    var expected =
        new net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest(
            AccountAuditScope.TENANT,
            42L,
            EVENT_ID,
            "account-service",
            "PUBLIC_PRODUCTION_JOINED",
            OCCURRED_AT,
            1,
            ByteString.copyFromUtf8(PAYLOAD),
            1,
            payloadDigest);
    doAnswer(
            invocation -> {
              assertThat(GrpcPeerIdentity.current()).isNotNull();
              assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(ACCOUNT_URI);
              assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
              assertThat(invocation.getArgument(0, expected.getClass())).isEqualTo(expected);
              return new AccountAuditReceiptDto(
                  AccountAuditScope.TENANT,
                  42L,
                  EVENT_ID,
                  RECEIPT_ID,
                  77L,
                  1,
                  1,
                  payloadDigest,
                  AccountAuditReceiptStatus.COMMITTED,
                  AccountAuditReceiptOutcome.DUPLICATE);
            })
        .when(owner)
        .readLogEventReceipt(expected);
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), "account")) {
      var response = client.stub().readLogEventReceipt(readRequest());
      assertThat(response.hasError()).isFalse();
      assertThat(response.getScope())
          .isEqualTo(
              net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT);
      assertThat(response.getTenantId()).isEqualTo("42");
      assertThat(response.getAuditEventId()).isEqualTo(EVENT_ID);
      assertThat(response.getReceiptId()).isEqualTo(RECEIPT_ID);
      assertThat(response.getLogEventId()).isEqualTo("77");
      assertThat(response.getSchemaVersion()).isEqualTo(1);
      assertThat(response.getPayloadDigestVersion()).isEqualTo(1);
      assertThat(response.getPayloadDigest()).isEqualTo(payloadDigest);
      assertThat(response.getStatus())
          .isEqualTo(
              net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus
                  .ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED);
      assertThat(response.getOutcome())
          .isEqualTo(
              net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
                  .ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE);
      verify(owner).readLogEventReceipt(expected);
      verifyNoMoreInteractions(owner);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"world", "other-account"})
  void trustedWrongWorkloadOrNamespaceCannotUseEitherAuditMethod(String alias) throws Exception {
    LogEventService owner = mock(LogEventService.class);
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), alias)) {
      assertAuditDenied(client.stub());
      verifyNoInteractions(owner);
    }
  }

  @Test
  void forgedPeerHeadersAndAdminServiceJwtCannotReplaceWorkloadCertificate() throws Exception {
    LogEventService owner = mock(LogEventService.class);
    Metadata spoofed = new Metadata();
    for (String header : List.of("x-peer-identity", "x-spiffe-id", "x-workload-identity")) {
      spoofed.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER), ACCOUNT_URI);
    }
    String token =
        new JwtUtil(JWT_SECRET, 60_000L)
            .generateToken(
                "account-service",
                Map.of(
                    "internalService",
                    true,
                    "serviceName",
                    "account-service",
                    "globalRoles",
                    List.of("platformAdmin")));
    spoofed.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), "world")) {
      assertAuditDenied(
          client.stub().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(spoofed)));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void missingClientCertificateFailsHandshakeBeforeOwnerAccess() throws Exception {
    LogEventService owner = mock(LogEventService.class);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    var issued = pki.clientProperties(temporaryDirectory, pki.identity("account"));
    tls.setCaCert(issued.getCaCert());
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), tls)) {
      assertHandshakeRejected(() -> client.stub().createLogEvent(createRequest()));
      assertHandshakeRejected(() -> client.stub().readLogEventReceipt(readRequest()));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void exactAccountCertificateDoesNotBypassJwtOnUnrelatedQueryMethod() throws Exception {
    LogEventService owner = mock(LogEventService.class);
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), "account")) {
      assertStatus(
          Status.Code.UNAUTHENTICATED,
          () -> client.stub().queryLogs(QueryLogsRequest.newBuilder().setTenantId("42").build()));
      verifyNoInteractions(owner);
    }
  }

  private static CreateLogEventRequest createRequest() {
    return CreateLogEventRequest.newBuilder()
        .setScope(
            net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantId("42")
        .setAuditEventId(EVENT_ID)
        .setProducerService("account-service")
        .setEventType("PUBLIC_PRODUCTION_JOINED")
        .setOccurredAt(
            Timestamp.newBuilder()
                .setSeconds(OCCURRED_AT.getEpochSecond())
                .setNanos(OCCURRED_AT.getNano()))
        .setSchemaVersion(1)
        .setPayload(ByteString.copyFromUtf8(PAYLOAD))
        .setPayloadDigestVersion(1)
        .setPayloadDigest(payloadDigest)
        .build();
  }

  private static ReadLogEventReceiptRequest readRequest() {
    var request = createRequest();
    return ReadLogEventReceiptRequest.newBuilder()
        .setScope(request.getScope())
        .setTenantId(request.getTenantId())
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

  private static void assertAuditDenied(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) {
    assertStatus(Status.Code.PERMISSION_DENIED, () -> stub.createLogEvent(createRequest()));
    assertStatus(Status.Code.PERMISSION_DENIED, () -> stub.readLogEventReceipt(readRequest()));
  }

  private static void assertStatus(Status.Code expected, Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static void assertHandshakeRejected(Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
  }

  private static TransportServer startServer(LogEventService owner) throws Exception {
    var service =
        new LoggingAdminGrpcService(
            mock(LogQueryService.class),
            owner,
            mock(ModerationService.class),
            new SimpleMeterRegistry(),
            NAMESPACE);
    var identity = pki.identity("logging");
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, authInterceptor, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new TransportServer(server);
  }

  private static ClientTransport newClient(int port, String alias) throws Exception {
    return newClient(port, pki.clientProperties(temporaryDirectory, pki.identity(alias)));
  }

  private static ClientTransport newClient(int port, CommonGrpcClientProperties tls)
      throws Exception {
    return new ClientTransport(
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false));
  }

  private record TransportServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account audit server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub() {
      return LoggingAdminServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account audit client did not terminate");
      }
    }
  }
}
