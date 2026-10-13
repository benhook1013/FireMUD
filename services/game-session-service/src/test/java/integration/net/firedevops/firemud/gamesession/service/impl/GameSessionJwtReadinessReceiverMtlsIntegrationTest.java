package integration.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
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
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.config.GameSessionJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeCrypto;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeOwnerClient;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper;
import net.firedevops.firemud.gamesession.service.impl.GameSessionJwtReadinessReceiverGrpcService;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback mTLS proof of the isolated Account-to-Pod boundary and the production verification
 * sequence. The Account owner response and protected local identity are explicitly test fixtures;
 * neither establishes live provenance, current inventory, or deployed workload identity.
 */
class GameSessionJwtReadinessReceiverMtlsIntegrationTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/firemud-prod/sa/account-service";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
  private static final String OTHER_WORKLOAD_URI =
      "spiffe://firemud/ns/firemud-prod/sa/game-design-service";
  private static final String KID = "pending-key-7";
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final String DEPLOYMENT_UID = "33333333-3333-4333-8333-333333333333";
  private static final String POD_UID = "44444444-4444-4444-8444-444444444444";
  private static final String CLUSTER_INCARNATION_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicInteger CERTIFICATE_SERIAL = new AtomicInteger(1);

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private Server gameSessionServer;
  private Server accountOwnerServer;
  private GameSessionJwtReadinessProbeOwnerClient ownerClient;
  private TestIdentityFiles accountClientFiles;
  private TestIdentityFiles gameSessionFiles;
  private Path caCertificateFile;
  private final AtomicReference<OwnerResponseMode> ownerResponseMode =
      new AtomicReference<>(OwnerResponseMode.EXACT);
  private final AtomicInteger ownerReadCalls = new AtomicInteger();
  private final AtomicInteger metadataReadCalls = new AtomicInteger();
  private final AtomicReference<GetCurrentReadinessProbeOwnerRequest> lastOwnerRequest =
      new AtomicReference<>();
  private final AtomicReference<GetCurrentReadinessReceiverMetadataRequest> lastMetadataRequest =
      new AtomicReference<>();
  private final AtomicReference<String> ownerSawPeerUri = new AtomicReference<>();
  private final AtomicReference<String> metadataSawPeerUri = new AtomicReference<>();
  private final AtomicReference<GetCurrentReadinessReceiverMetadataResponse> metadataResponse =
      new AtomicReference<>();
  private final AtomicReference<AccountPodTargetBinding> ownerTarget = new AtomicReference<>();
  private final AtomicReference<GameSessionJwtReadinessLocalIdentityProvider.LocalObservation>
      localObservation = new AtomicReference<>();
  private final AtomicBoolean responseHadNoApplicationAuthContext = new AtomicBoolean();
  private KeyPair signingKey;
  private AccountPublicJwksCache.SourceIdentity jwksSourceIdentity;
  private String fixturePublicJwksSha256;
  private ReadinessReceiverLocalIdentity localIdentity;
  private AccountPodTargetBinding exactOwnerTarget;
  private ReceiveReadinessProbeRequest validRequest;
  private String validCompactJwt;

  @BeforeAll
  static void createTestCertificates() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair authority = rsaKeyPair(2048);
    X500Name authorityName = new X500Name("CN=Game Session readiness test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(
            authorityName,
            authority.getPublic(),
            authorityName,
            authority.getPrivate(),
            true,
            null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(authorityName, authority.getPrivate(), ACCOUNT_URI),
            issueLeaf(authorityName, authority.getPrivate(), GAME_SESSION_URI),
            issueLeaf(authorityName, authority.getPrivate(), OTHER_WORKLOAD_URI),
            issueLeaf(authorityName, authority.getPrivate(), null));
  }

  @BeforeEach
  void startIsolatedTransports() throws Exception {
    ownerResponseMode.set(OwnerResponseMode.EXACT);
    ownerReadCalls.set(0);
    metadataReadCalls.set(0);
    lastOwnerRequest.set(null);
    lastMetadataRequest.set(null);
    ownerSawPeerUri.set(null);
    metadataSawPeerUri.set(null);
    metadataResponse.set(null);
    ownerTarget.set(null);
    localObservation.set(null);
    responseHadNoApplicationAuthContext.set(false);

    caCertificateFile = writePem("readiness-test-ca.crt", "CERTIFICATE", pki.caCertificate());
    accountClientFiles = writeIdentityFiles("account", pki.account());
    gameSessionFiles = writeIdentityFiles("game-session", pki.gameSession());
    accountOwnerServer = startAccountOwnerServer();
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + accountOwnerServer.getPort());
    ownerClient =
        new GameSessionJwtReadinessProbeOwnerClient(
            endpoints, clientProperties(gameSessionFiles), new GrpcChannelFactory(), NAMESPACE);

    signingKey = rsaKeyPair(3072);
    jwksSourceIdentity = sourceIdentity();
    String jwks = jwks(KID, (RSAPublicKey) signingKey.getPublic());
    fixturePublicJwksSha256 = sha256(jwks);
    AccountPublicJwksCache keyCache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    jwksSourceIdentity, jwks.getBytes(StandardCharsets.US_ASCII)),
            jwksSourceIdentity,
            CLOCK,
            Duration.ofSeconds(300));
    GameSessionJwtReadinessProbeCrypto crypto =
        new GameSessionJwtReadinessProbeCrypto(
            new AccountAsymmetricJwtVerifier(keyCache, CLOCK), 8);

    GameSessionJwtReadinessReceiverProtoMapper mapper =
        new GameSessionJwtReadinessReceiverProtoMapper();
    GameSessionJwtReadinessReceiverEngine engine =
        new GameSessionJwtReadinessReceiverEngine(
            new GameSessionJwtReadinessProbeOwnerWorkloadGuard(NAMESPACE),
            localObservation::get,
            ownerClient,
            mapper,
            crypto,
            CLOCK);
    var receiverService = new GameSessionJwtReadinessReceiverGrpcService(engine);
    gameSessionServer =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(serverTlsContext(gameSessionFiles))
            .addService(
                ServerInterceptors.intercept(
                    receiverService,
                    new GrpcPeerIdentityInterceptor(),
                    applicationAuthContextProbe()))
            .build()
            .start();

    localIdentity = localIdentity(gameSessionServer.getPort());
    metadataResponse.set(
        GetCurrentReadinessReceiverMetadataResponse.newBuilder()
            .setSchemaVersion(1)
            .setRotationOperationId("66666666-6666-4666-8666-666666666666")
            .setOperationDigest("a".repeat(64))
            .setPlanDigest("b".repeat(64))
            .setCurrentIdentity(localIdentity)
            .build());
    exactOwnerTarget = ownerTarget(localIdentity);
    localObservation.set(
        new GameSessionJwtReadinessLocalIdentityProvider.LocalObservation(
            localIdentity, jwksSourceIdentity));
    ownerTarget.set(exactOwnerTarget);
    UUID jti = UUID.randomUUID();
    long issuedAt = NOW.getEpochSecond() - 1L;
    ReadinessProbeCoordinates coordinates = coordinates(jti, issuedAt);
    validCompactJwt = signedDelegationToken(jti, issuedAt);
    validRequest = request(validCompactJwt, coordinates);

    assertThat(accountClientFiles.certificate()).isEqualTo(pki.account().certificate());
  }

  @AfterEach
  void stopTransportsAndClearContext() throws Exception {
    SessionContext.clear();
    stopServer(gameSessionServer);
    gameSessionServer = null;
    stopServer(accountOwnerServer);
    accountOwnerServer = null;
  }

  @Test
  void accountMtlsCallUsesProductionOwnerReadAndRealProfileVerificationWithoutAuthSideEffects()
      throws Exception {
    ReceiveReadinessProbeResponse response = receive(pki.account(), validRequest);

    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getCoordinates()).isEqualTo(validRequest.getCoordinates());
    assertThat(response.getCompactTokenSha256()).isEqualTo(sha256(validCompactJwt));
    assertThat(response.getVerifiedKeyId()).isEqualTo(KID);
    assertThat(response.getReceiverIdentity()).isEqualTo(localIdentity);
    assertThat(response.getAccountPodTarget()).isEqualTo(exactOwnerTarget);
    assertThat(response.getObservedAtEpochSeconds()).isEqualTo(NOW.getEpochSecond());
    assertThat(response.getOutcome())
        .isEqualTo(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED);
    assertThat(ownerReadCalls).hasValue(2);
    assertThat(ownerSawPeerUri).hasValue(GAME_SESSION_URI);
    assertThat(lastOwnerRequest.get().getExpectedCoordinates())
        .isEqualTo(validRequest.getCoordinates());
    assertThat(lastOwnerRequest.get().getCompactTokenSha256()).isEqualTo(sha256(validCompactJwt));
    assertThat(lastOwnerRequest.get().getProtectedLocalIdentity()).isEqualTo(localIdentity);
    assertThat(lastOwnerRequest.get().getUnknownFields().asMap()).isEmpty();
    assertThat(responseHadNoApplicationAuthContext).isTrue();
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();
    assertThat(SessionContext.getScopedRolesMap()).isEmpty();
    assertThat(SessionContext.isInternalService()).isFalse();
    assertThat(SessionContext.getServiceName()).isNull();
    assertThat(SessionContext.getServiceInstanceId()).isNull();
  }

  @Test
  void currentMetadataBootstrapUsesAccountMtlsAndOnlyProjectedUidAndActualLeafPin()
      throws Exception {
    GetCurrentReadinessReceiverMetadataRequest request =
        GetCurrentReadinessReceiverMetadataRequest.newBuilder()
            .setSchemaVersion(1)
            .setProjectedPodUid(POD_UID)
            .setServerLeafSpkiSha256(spkiSha256(pki.gameSession().certificate()))
            .build();

    GetCurrentReadinessReceiverMetadataResponse response = ownerClient.readCurrent(request);

    assertThat(response).isEqualTo(metadataResponse.get());
    assertThat(metadataReadCalls).hasValue(1);
    assertThat(lastMetadataRequest.get()).isEqualTo(request);
    assertThat(lastMetadataRequest.get().getUnknownFields().asMap()).isEmpty();
    assertThat(metadataSawPeerUri).hasValue(GAME_SESSION_URI);
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();
    assertThat(SessionContext.getScopedRolesMap()).isEmpty();
    assertThat(SessionContext.isInternalService()).isFalse();
  }

  @Test
  void ambientSqlTransactionDeniesBothAccountReadsBeforeDispatch() {
    boolean previousTransactionState =
        TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  ownerClient.readCurrent(
                      GetCurrentReadinessProbeOwnerRequest.newBuilder().build()))
          .isInstanceOf(
              GameSessionJwtReadinessProbeOwnerClient.OwnerReadUnavailableException.class);
      assertThatThrownBy(
              () ->
                  ownerClient.readCurrent(
                      GetCurrentReadinessReceiverMetadataRequest.newBuilder().build()))
          .isInstanceOf(
              GameSessionJwtReadinessProbeOwnerClient.OwnerReadUnavailableException.class);
      assertThat(ownerReadCalls).hasValue(0);
      assertThat(metadataReadCalls).hasValue(0);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previousTransactionState);
    }
  }

  @Test
  void wrongWorkloadAndMissingWorkloadIdentityAreDeniedBeforeOwnerRead() throws Exception {
    for (TestCertificate caller : List.of(pki.wrongWorkload(), pki.missingWorkloadUri())) {
      assertThatThrownBy(() -> receive(caller, validRequest))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      assertThat(ownerReadCalls).hasValue(0);
    }
  }

  @Test
  void mismatchedOwnerCoordinatesTokenHashAndPodTargetFailClosedBeforeReceipt() throws Exception {
    ownerResponseMode.set(OwnerResponseMode.STALE_COORDINATES);
    assertUnavailable(validRequest);
    assertThat(ownerReadCalls).hasValue(1);

    ownerReadCalls.set(0);
    ownerResponseMode.set(OwnerResponseMode.STALE_TOKEN_HASH);
    assertUnavailable(validRequest);
    assertThat(ownerReadCalls).hasValue(1);

    ownerReadCalls.set(0);
    ownerResponseMode.set(OwnerResponseMode.WRONG_POD_TARGET);
    assertUnavailable(validRequest);
    assertThat(ownerReadCalls).hasValue(1);
  }

  @Test
  void changedOwnerEvidenceOnSecondReadDeniesAfterCryptographicVerification() throws Exception {
    ownerResponseMode.set(OwnerResponseMode.CHANGED_AFTER_FIRST_READ);

    assertUnavailable(validRequest);

    assertThat(ownerReadCalls).hasValue(2);
    assertThat(ownerSawPeerUri).hasValue(GAME_SESSION_URI);
  }

  @Test
  void validInapplicableProductionProfileReturnsOnlyItsNonAuthorizingNegativeObservation()
      throws Exception {
    UUID jti = UUID.randomUUID();
    long issuedAt = NOW.getEpochSecond() - 1L;
    ReadinessProbeCoordinates coordinates =
        validRequest.getCoordinates().toBuilder()
            .setJti(jti.toString())
            .setTokenProfile("control-ui")
            .setAudience("control-ui")
            .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
            .setIssuedAtEpochSeconds(issuedAt)
            .setExpiresAtEpochSeconds(issuedAt + 120L)
            .setPlanExpiresAtEpochSeconds(issuedAt + 180L)
            .build();
    ReceiveReadinessProbeResponse response =
        receive(pki.account(), request(signedControlUiToken(jti, issuedAt), coordinates));

    assertThat(response.getOutcome())
        .isEqualTo(ReceiveReadinessProbeResponse.ObservationOutcome.INAPPLICABLE_REJECT);
    assertThat(response.getCoordinates()).isEqualTo(coordinates);
    assertThat(response.getVerifiedKeyId()).isEqualTo(KID);
    assertThat(ownerReadCalls).hasValue(2);
    assertThat(responseHadNoApplicationAuthContext).isTrue();
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();
    assertThat(SessionContext.getScopedRolesMap()).isEmpty();
  }

  @Test
  void wrongKeyAndTamperedSignatureAreRejectedByProductionVerifier() throws Exception {
    ownerResponseMode.set(OwnerResponseMode.EXACT);
    ReadinessProbeCoordinates wrongKeyCoordinates =
        validRequest.getCoordinates().toBuilder().setTargetKid("pending-key-8").build();
    ReceiveReadinessProbeRequest wrongKeyRequest = request(validCompactJwt, wrongKeyCoordinates);
    assertThatThrownBy(() -> receive(pki.account(), wrongKeyRequest))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(ownerReadCalls).hasValue(1);

    ownerReadCalls.set(0);
    String tamperedJwt = tamperSignature(validCompactJwt);
    ReceiveReadinessProbeRequest tamperedRequest =
        request(tamperedJwt, validRequest.getCoordinates());
    assertThatThrownBy(() -> receive(pki.account(), tamperedRequest))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(ownerReadCalls).hasValue(1);
  }

  private void assertUnavailable(ReceiveReadinessProbeRequest request) throws Exception {
    assertThatThrownBy(() -> receive(pki.account(), request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
  }

  private ReceiveReadinessProbeResponse receive(
      TestCertificate caller, ReceiveReadinessProbeRequest request) throws Exception {
    TestIdentityFiles callerFiles =
        caller == pki.account()
            ? accountClientFiles
            : caller == pki.gameSession()
                ? gameSessionFiles
                : writeIdentityFiles("temporary-caller-" + ownerReadCalls.get(), caller);
    ManagedChannel transport =
        NettyChannelBuilder.forAddress("127.0.0.1", gameSessionServer.getPort())
            .sslContext(clientTlsContext(callerFiles))
            .build();
    Channel authenticatedServer =
        ClientInterceptors.intercept(
            transport, new GrpcServerPeerIdentityClientInterceptor(GAME_SESSION_URI));
    try {
      return GameSessionJwtReadinessReceiverServiceGrpc.newBlockingStub(authenticatedServer)
          .receiveReadinessProbe(request);
    } finally {
      transport.shutdownNow();
      assertThat(transport.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Server startAccountOwnerServer() throws Exception {
    AccountJwtReadinessProbeOwnerServiceGrpc.AccountJwtReadinessProbeOwnerServiceImplBase service =
        new AccountJwtReadinessProbeOwnerServiceGrpc
            .AccountJwtReadinessProbeOwnerServiceImplBase() {
          @Override
          public void getCurrentReadinessProbeOwner(
              GetCurrentReadinessProbeOwnerRequest request,
              StreamObserver<GetCurrentReadinessProbeOwnerResponse> responseObserver) {
            ownerReadCalls.incrementAndGet();
            lastOwnerRequest.set(request);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            ownerSawPeerUri.set(peer == null ? null : peer.uri());
            responseObserver.onNext(
                ownerResponse(request, ownerResponseMode.get(), ownerReadCalls.get()));
            responseObserver.onCompleted();
          }

          @Override
          public void getCurrentReadinessReceiverMetadata(
              GetCurrentReadinessReceiverMetadataRequest request,
              StreamObserver<GetCurrentReadinessReceiverMetadataResponse> responseObserver) {
            metadataReadCalls.incrementAndGet();
            lastMetadataRequest.set(request);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            metadataSawPeerUri.set(peer == null ? null : peer.uri());
            responseObserver.onNext(metadataResponse.get());
            responseObserver.onCompleted();
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(serverTlsContext(writeIdentityFiles("account-server", pki.account())))
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private GetCurrentReadinessProbeOwnerResponse ownerResponse(
      GetCurrentReadinessProbeOwnerRequest request, OwnerResponseMode mode, int callNumber) {
    var builder =
        GetCurrentReadinessProbeOwnerResponse.newBuilder()
            .setSchemaVersion(1)
            .setOwnerRecordFound(true)
            .setCurrentCoordinates(request.getExpectedCoordinates())
            .setCompactTokenSha256(request.getCompactTokenSha256())
            .setCurrentState(GetCurrentReadinessProbeOwnerResponse.ProbeState.ISSUED)
            .setCurrentPodTarget(
                ownerTarget.get().toBuilder()
                    .setExpectedOutcome(request.getExpectedCoordinates().getExpectedOutcome()));
    return switch (mode) {
      case EXACT -> builder.build();
      case STALE_COORDINATES ->
          builder
              .setCurrentCoordinates(
                  request.getExpectedCoordinates().toBuilder().setPlanDigest("0".repeat(64)))
              .build();
      case STALE_TOKEN_HASH -> builder.setCompactTokenSha256("f".repeat(64)).build();
      case WRONG_POD_TARGET ->
          builder
              .setCurrentPodTarget(
                  ownerTarget.get().toBuilder()
                      .setPodIp("127.0.0.2")
                      .setDirectPodEndpoint("grpcs://127.0.0.2:" + endpointPort(ownerTarget.get())))
              .build();
      case CHANGED_AFTER_FIRST_READ ->
          callNumber == 1
              ? builder.build()
              : builder
                  .setCurrentState(GetCurrentReadinessProbeOwnerResponse.ProbeState.VERIFIED)
                  .build();
    };
  }

  private static int endpointPort(AccountPodTargetBinding target) {
    return Integer.parseInt(
        target
            .getDirectPodEndpoint()
            .substring(target.getDirectPodEndpoint().lastIndexOf(':') + 1));
  }

  private ServerInterceptor applicationAuthContextProbe() {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          io.grpc.Metadata headers,
          ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> observingCall =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              @Override
              public void sendMessage(RespT message) {
                responseHadNoApplicationAuthContext.set(
                    SessionContext.getAccountId() == null
                        && SessionContext.getGlobalRoles().isEmpty()
                        && SessionContext.getScopedRolesMap().isEmpty()
                        && !SessionContext.isInternalService()
                        && SessionContext.getServiceName() == null
                        && SessionContext.getServiceInstanceId() == null);
                super.sendMessage(message);
              }
            };
        return next.startCall(observingCall, headers);
      }
    };
  }

  private ReadinessProbeCoordinates coordinates(UUID jti, long issuedAt) {
    return ReadinessProbeCoordinates.newBuilder()
        .setRotationOperationId(UUID.randomUUID().toString())
        .setOperationDigest("a".repeat(64))
        .setPlanDigest("b".repeat(64))
        .setPlanVersion(2)
        .setRegistryVersion(1)
        .setValidatorId(GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR)
        .setTokenProfile(GameSessionAccountDelegationProfile.PROFILE)
        .setAudience(GameSessionAccountDelegationProfile.AUDIENCE)
        .setProbeKind(ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
        .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
        .setJti(jti.toString())
        .setEntryVersion(4)
        .setTargetGeneration("7")
        .setTargetKid(KID)
        .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
        .setIssuedAtEpochSeconds(issuedAt)
        .setExpiresAtEpochSeconds(issuedAt + 120L)
        .setPlanExpiresAtEpochSeconds(issuedAt + 180L)
        .build();
  }

  private static ReceiveReadinessProbeRequest request(
      String jwt, ReadinessProbeCoordinates coordinates) {
    return ReceiveReadinessProbeRequest.newBuilder()
        .setSchemaVersion(1)
        .setCoordinates(coordinates)
        .setCompactJwt(jwt)
        .build();
  }

  private String signedDelegationToken(UUID jti, long issuedAt) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", KID, "typ", "JWT");
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(),
            "membershipAuthorityGeneration", Map.of(),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", jti.toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("iat", issuedAt);
    claims.put("nbf", issuedAt);
    claims.put("exp", issuedAt + 120L);
    claims.put("accountId", RESERVED_SUBJECT);
    claims.put("tokenGeneration", "1");
    claims.put("authorityTuple", authorityTuple);
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", "1");
    return sign(header, claims);
  }

  private String signedControlUiToken(UUID jti, long issuedAt) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", KID, "typ", "JWT");
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 1L,
            "tenantAuthorityGeneration", Map.of(),
            "membershipAuthorityGeneration", Map.of(),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", jti.toString());
    claims.put("aud", "control-ui");
    claims.put("iat", issuedAt);
    claims.put("nbf", issuedAt);
    claims.put("exp", issuedAt + 120L);
    claims.put("accountId", RESERVED_SUBJECT);
    claims.put("tokenGeneration", 1L);
    claims.put("authorityTuple", authorityTuple);
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", 1L);
    claims.put("scopedRoles", Map.of());
    return sign(header, claims);
  }

  private String sign(Map<String, Object> header, Map<String, Object> claims) throws Exception {
    String signingInput =
        encode(JSON.writeValueAsBytes(header))
            + "."
            + encode(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(claims)));
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(signingKey.getPrivate());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + encode(signer.sign());
  }

  private ReadinessReceiverLocalIdentity localIdentity(int port) throws Exception {
    return ReadinessReceiverLocalIdentity.newBuilder()
        .setValidatorId(GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR)
        .setDeploymentUid(DEPLOYMENT_UID)
        .setPodUid(POD_UID)
        .setPodIp("127.0.0.1")
        .setDirectPodEndpoint("grpcs://127.0.0.1:" + port)
        .setCanonicalServiceUri(GAME_SESSION_URI)
        .setImage("registry.example/firemud/game-session-service@sha256:" + "a".repeat(64))
        .setVerifierConfigSha256("b".repeat(64))
        .setApplicabilityMatrixDigest("c".repeat(64))
        .setSourceInventoryRevision("inventory-r1")
        .setSourceInventoryDigest("d".repeat(64))
        .setServerLeafSpkiSha256(spkiSha256(pki.gameSession().certificate()))
        .setAccountJwksSourceIdentity(sourceIdentityProto())
        .setAccountJwksTrustBindingRevision(jwksSourceIdentity.bindingRevision())
        .setAccountPublicJwksSha256(fixturePublicJwksSha256)
        .build();
  }

  private AccountPodTargetBinding ownerTarget(ReadinessReceiverLocalIdentity receiverIdentity) {
    return AccountPodTargetBinding.newBuilder()
        .setInventorySnapshotDigest("f".repeat(64))
        .setEnvironmentId("prod")
        .setClusterId("cluster-a")
        .setClusterIncarnationUid(CLUSTER_INCARNATION_UID)
        .setNamespace(NAMESPACE)
        .setNamespaceUid(NAMESPACE_UID)
        .setApiBindingRevision("api-r1")
        .setApiBindingDigest("8".repeat(64))
        .setInventoryBindingRevision("inventory-r1")
        .setInventoryBindingDigest("d".repeat(64))
        .setValidatorId(receiverIdentity.getValidatorId())
        .setDeploymentUid(receiverIdentity.getDeploymentUid())
        .setPodUid(receiverIdentity.getPodUid())
        .setPodIp(receiverIdentity.getPodIp())
        .setImage(receiverIdentity.getImage())
        .setVerifierConfigSha256(receiverIdentity.getVerifierConfigSha256())
        .setApplicabilityMatrixDigest(receiverIdentity.getApplicabilityMatrixDigest())
        .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
        .setDirectPodEndpoint(receiverIdentity.getDirectPodEndpoint())
        .setCanonicalServiceUri(receiverIdentity.getCanonicalServiceUri())
        .setServerLeafSpkiSha256(receiverIdentity.getServerLeafSpkiSha256())
        .build();
  }

  private AccountPublicJwksCache.SourceIdentity sourceIdentity() {
    return new AccountPublicJwksCache.SourceIdentity(
        "prod",
        "cluster-a",
        CLUSTER_INCARNATION_UID,
        NAMESPACE,
        NAMESPACE_UID,
        "55555555-5555-4555-8555-555555555555",
        "account-api-r1",
        "https://kubernetes.example.test:6443",
        "9".repeat(64));
  }

  private AccountSourceIdentity sourceIdentityProto() {
    return AccountSourceIdentity.newBuilder()
        .setEnvironmentId("prod")
        .setClusterId("cluster-a")
        .setClusterIncarnationUid(CLUSTER_INCARNATION_UID)
        .setNamespace(NAMESPACE)
        .setNamespaceUid(NAMESPACE_UID)
        .setConfigMapUid("55555555-5555-4555-8555-555555555555")
        .setBindingRevision("account-api-r1")
        .setApiServerOrigin("https://kubernetes.example.test:6443")
        .setServingCaSha256("9".repeat(64))
        .build();
  }

  private static String jwks(String kid, RSAPublicKey key) {
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\""
        + kid
        + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
        + encode(unsigned(key.getModulus()))
        + "\",\"e\":\""
        + encode(unsigned(key.getPublicExponent()))
        + "\"}]}";
  }

  private static String tamperSignature(String jwt) {
    String[] parts = jwt.split("\\.", -1);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 1;
    return parts[0] + "." + parts[1] + "." + encode(signature);
  }

  private static String sha256(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String spkiSha256(X509Certificate certificate) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate.getPublicKey().getEncoded()));
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return bytes.length > 1 && bytes[0] == 0
        ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length)
        : bytes;
  }

  private static KeyPair rsaKeyPair(int bits) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(new RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4));
    return generator.generateKeyPair();
  }

  private static TestCertificate issueLeaf(
      X500Name authorityName, PrivateKey authorityKey, String workloadUri) throws Exception {
    KeyPair keys = rsaKeyPair(2048);
    GeneralName[] names =
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
            new X500Name("CN=FireMUD mTLS readiness test workload, O=FireMUD Test"),
            keys.getPublic(),
            authorityName,
            authorityKey,
            false,
            new GeneralNames(names));
    return new TestCertificate(keys.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerKey,
      boolean certificateAuthority,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant notBefore = Instant.now().minusSeconds(60);
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(notBefore),
            Date.from(notBefore.plusSeconds(14L * 24L * 60L * 60L)),
            subject,
            publicKey);
    builder.addExtension(
        Extension.basicConstraints, true, new BasicConstraints(certificateAuthority));
    if (certificateAuthority) {
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
                    .build(issuerKey)));
  }

  private Path writePem(String name, String label, X509Certificate certificate) throws Exception {
    return writePem(name, label, certificate.getEncoded());
  }

  private Path writePem(String name, String label, byte[] bytes) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return Files.writeString(
        tempDirectory.resolve(name),
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private TestIdentityFiles writeIdentityFiles(String prefix, TestCertificate identity)
      throws Exception {
    Path certificate = writePem(prefix + ".crt", "CERTIFICATE", identity.certificate());
    Path privateKey = writePem(prefix + ".key", "PRIVATE KEY", identity.privateKey().getEncoded());
    return new TestIdentityFiles(certificate, privateKey, identity.certificate());
  }

  private CommonGrpcClientProperties clientProperties(TestIdentityFiles identity) {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(identity.certificateFile().toString());
    properties.setPrivateKey(identity.privateKeyFile().toString());
    properties.setCaCert(caCertificateFile.toString());
    return properties;
  }

  private io.grpc.netty.shaded.io.netty.handler.ssl.SslContext serverTlsContext(
      TestIdentityFiles identity) throws Exception {
    return GrpcSslContexts.configure(
            SslContextBuilder.forServer(
                identity.certificateFile().toFile(), identity.privateKeyFile().toFile()))
        .trustManager(caCertificateFile.toFile())
        .clientAuth(ClientAuth.REQUIRE)
        .build();
  }

  private io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientTlsContext(
      TestIdentityFiles identity) throws Exception {
    return GrpcSslContexts.forClient()
        .trustManager(caCertificateFile.toFile())
        .keyManager(identity.certificateFile().toFile(), identity.privateKeyFile().toFile())
        .build();
  }

  private static void stopServer(Server server) throws InterruptedException {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private enum OwnerResponseMode {
    EXACT,
    STALE_COORDINATES,
    STALE_TOKEN_HASH,
    WRONG_POD_TARGET,
    CHANGED_AFTER_FIRST_READ
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestIdentityFiles(
      Path certificateFile, Path privateKeyFile, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate account,
      TestCertificate gameSession,
      TestCertificate wrongWorkload,
      TestCertificate missingWorkloadUri) {}
}
