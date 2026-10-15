package net.firedevops.firemud.worldmanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Socket-level proof of authenticated World launch provenance reads from GS and GDS owners. */
class RunOwnedCanonicalWorldLaunchEvidenceMtlsIntegrationTest {
  private static final String RUN_ID = "world-mtls-fixture-test";
  private static final String PROJECT = "firemud-world-mtls-fixture-test";
  private static final String GS_URI = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/dev/sa/world-management-service";
  private static final String GDS_URI = "spiffe://firemud/ns/dev/sa/game-design-service";
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";
  private static final String STORE_PASSWORD = "test-only-world-mtls-password";
  private static final String TEST_JWT_SECRET = "testsecretkeytestsecretkeytest1234";
  private static final JwtUtil WORLD_JWT_UTIL = new JwtUtil(TEST_JWT_SECRET, 3_600_000L);
  private static final RuntimeIdentity WORLD_RUNTIME_IDENTITY =
      new RuntimeIdentity(
          "world-management-service",
          "world-mtls-fixture-test",
          "localhost",
          Instant.parse("2026-10-11T00:00:00Z"),
          "test",
          "test",
          "local");

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;
  private Server worldServer;
  private Server gameSessionOwnerServer;
  private Server gameDesignServer;
  private GameDesignClient gameDesignClient;
  private RunOwnedCanonicalWorldLaunchEvidenceProvider evidenceProvider;
  private ManagedChannel gameSessionChannel;

  @BeforeAll
  static void createCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    SessionContext.clear();
    if (evidenceProvider != null) {
      evidenceProvider.closeGameSessionFixtureClient();
      evidenceProvider = null;
    }
    if (gameSessionChannel != null) {
      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel = null;
    }
    if (gameDesignClient != null) {
      gameDesignClient.close();
      gameDesignClient = null;
    }
    if (worldServer != null) {
      worldServer.shutdownNow();
      assertThat(worldServer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      worldServer = null;
    }
    if (gameDesignServer != null) {
      gameDesignServer.shutdownNow();
      assertThat(gameDesignServer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameDesignServer = null;
    }
    if (gameSessionOwnerServer != null) {
      gameSessionOwnerServer.shutdownNow();
      assertThat(gameSessionOwnerServer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionOwnerServer = null;
    }
  }

  @Test
  void physicalMtlsReadsExactStartingFixtureProvenanceWithoutWorldWrites() throws Exception {
    Fixture fixture = Fixture.create(temporaryDirectory, pki);
    AtomicInteger descriptorReads = new AtomicInteger();
    AtomicInteger fixtureReads = new AtomicInteger();
    AtomicReference<GrpcPeerIdentity> worldAtGameDesign = new AtomicReference<>();
    AtomicReference<GrpcPeerCertificateEvidence> worldCertificateAtGameDesign =
        new AtomicReference<>();
    AtomicReference<GetLaunchDescriptorRequest> descriptorRequestAtOwner = new AtomicReference<>();
    AtomicReference<GrpcPeerIdentity> worldAtGameSession = new AtomicReference<>();
    AtomicReference<GrpcPeerCertificateEvidence> worldCertificateAtGameSession =
        new AtomicReference<>();
    AtomicReference<CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest>
        fixtureRequestAtOwner = new AtomicReference<>();
    AtomicBoolean returnChangedDescriptor = new AtomicBoolean();
    startGameDesignServer(
        fixture,
        descriptorReads,
        worldAtGameDesign,
        worldCertificateAtGameDesign,
        descriptorRequestAtOwner,
        returnChangedDescriptor);
    startGameSessionOwnerServer(
        fixture,
        fixtureReads,
        worldAtGameSession,
        worldCertificateAtGameSession,
        fixtureRequestAtOwner);

    CommonGrpcClientProperties worldTls = pki.worldClientProperties();
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + gameDesignServer.getPort());
    endpoints.setGameSessionService("localhost:" + gameSessionOwnerServer.getPort());
    gameDesignClient =
        new GameDesignClient(
            endpoints,
            worldTls,
            new GrpcChannelFactory(),
            new BlockingGrpcStubCustomizer() {
              @Override
              public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
                return GrpcClientAuth.attach(stub, WORLD_JWT_UTIL, WORLD_RUNTIME_IDENTITY);
              }
            });
    String previousReload = System.getProperty("firemud.grpc.tls-reload.enabled");
    System.setProperty("firemud.grpc.tls-reload.enabled", "false");
    try {
      ReflectionTestUtils.invokeMethod(gameDesignClient, "init");
      evidenceProvider = fixture.provider(gameDesignClient, endpoints);
      AtomicReference<RunOwnedCanonicalWorldLaunchEvidence> readEvidence = new AtomicReference<>();
      WorldManagementGrpcService readOnlyHandler =
          new WorldManagementGrpcService(
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              new SimpleMeterRegistry(),
              JsonMapper.builder().build(),
              "dev") {
            @Override
            public void getWorldInstanceLifecycle(
                GetWorldInstanceLifecycleRequest request,
                StreamObserver<GetWorldInstanceLifecycleResponse> responseObserver) {
              try {
                readEvidence.set(evidenceProvider.requireAuthenticatedEvidence());
                responseObserver.onNext(
                    GetWorldInstanceLifecycleResponse.newBuilder()
                        .setError(
                            net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                                .setCode("TEST_READ_PROVENANCE_ONLY")
                                .setMessage(
                                    "Controlled handler captured provenance without World reads or writes"))
                        .build());
              } catch (RuntimeException rejected) {
                responseObserver.onNext(
                    GetWorldInstanceLifecycleResponse.newBuilder()
                        .setError(
                            net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                                .setCode("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_INVALID")
                                .setMessage("Controlled fixture provenance read was rejected"))
                        .build());
              }
              responseObserver.onCompleted();
            }
          };
      worldServer = startWorldServer(readOnlyHandler);
      gameSessionChannel = channel("localhost", worldServer.getPort(), pki.gameSessionClient());
      var response =
          WorldManagementServiceGrpc.newBlockingStub(gameSessionChannel)
              .getWorldInstanceLifecycle(
                  GetWorldInstanceLifecycleRequest.newBuilder()
                      .setTenantId("142")
                      .setGameInstanceId("1001")
                      .build());

      assertThat(response.hasError()).isTrue();
      assertThat(response.getError().getCode()).isEqualTo("TEST_READ_PROVENANCE_ONLY");
      assertThat(readEvidence.get()).isNotNull();
      assertThat(readEvidence.get().gameSessionAssociation().gameSessionTenantId()).isEqualTo(142L);
      assertThat(readEvidence.get().gameSessionAssociation().gameInstanceId()).isEqualTo(1001L);
      assertThat(readEvidence.get().gameSessionAssociation().currentGameInstanceStatus())
          .isEqualTo(
              CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                  .STARTING);
      assertThat(readEvidence.get().gameSessionAssociation().fixturePolicySeal())
          .isEqualTo(fixture.policySeal);
      assertThat(readEvidence.get().gameSessionAssociation().fixturePolicyRequestBytes())
          .isEqualTo(
              RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
                  fixture.capability,
                  fixture.creationEvidence,
                  fixture.source,
                  fixture.launchReadEvidence));
      assertThat(readEvidence.get().gameSessionAssociation().canonicalTenantId())
          .isEqualTo(fixture.source.canonicalTenantId());
      assertThat(readEvidence.get().gameSessionAssociation().canonicalVersionId().toString())
          .isEqualTo(fixture.bundle.getCanonicalVersionId());
      assertThat(fixtureReads).hasValue(1);
      assertThat(descriptorReads).hasValue(1);
      assertThat(fixtureRequestAtOwner.get()).isNotNull();
      assertThat(fixtureRequestAtOwner.get().fixtureOperationId().toString())
          .isEqualTo(fixture.operationId);
      assertThat(fixtureRequestAtOwner.get().expectedFixturePolicyEvidenceDigest())
          .isEqualTo(fixture.policySeal.evidenceDigest());
      assertThat(worldAtGameSession.get())
          .isNotNull()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo(WORLD_URI);
      assertThat(worldAtGameSession.get().isInNamespace("dev")).isTrue();
      assertThat(worldCertificateAtGameSession.get())
          .isNotNull()
          .extracting(GrpcPeerCertificateEvidence::leafSha256)
          .isEqualTo(fingerprint(pki.worldManagement().certificate()));
      assertThat(worldAtGameDesign.get())
          .isNotNull()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo(WORLD_URI);
      assertThat(worldCertificateAtGameDesign.get())
          .isNotNull()
          .extracting(GrpcPeerCertificateEvidence::leafSha256)
          .isEqualTo(fingerprint(pki.worldManagement().certificate()));
      assertThat(descriptorRequestAtOwner.get()).isNotNull();
      assertThat(descriptorRequestAtOwner.get().getControlPlaneRequestId())
          .isEqualTo(fixture.operationId);
      assertThat(descriptorRequestAtOwner.get().getExpectedRequestDigest())
          .isEqualTo(fixture.descriptorEvidence.requestDigest());
      assertThat(descriptorRequestAtOwner.get().getExpectedResultDigest())
          .isEqualTo(fixture.descriptorEvidence.resultDigest());
      assertThat(Files.readAllBytes(fixture.capabilityPath))
          .isEqualTo(fixture.originalCapabilityBytes);
      assertThat(Files.readAllBytes(fixture.receiptPath)).isEqualTo(fixture.originalReceiptBytes);

      returnChangedDescriptor.set(true);
      var rejected =
          WorldManagementServiceGrpc.newBlockingStub(gameSessionChannel)
              .getWorldInstanceLifecycle(
                  GetWorldInstanceLifecycleRequest.newBuilder()
                      .setTenantId("142")
                      .setGameInstanceId("1001")
                      .build());
      assertThat(rejected.hasError()).isTrue();
      assertThat(rejected.getError().getCode())
          .isEqualTo("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_INVALID");
      assertThat(descriptorReads).hasValue(2);
      assertThat(fixtureReads).hasValue(1);
      assertThat(Files.readAllBytes(fixture.capabilityPath))
          .isEqualTo(fixture.originalCapabilityBytes);
      assertThat(Files.readAllBytes(fixture.receiptPath)).isEqualTo(fixture.originalReceiptBytes);
    } finally {
      if (previousReload == null) {
        System.clearProperty("firemud.grpc.tls-reload.enabled");
      } else {
        System.setProperty("firemud.grpc.tls-reload.enabled", previousReload);
      }
    }
  }

  @Test
  void gameDesignTransportRejectsMissingAndWrongJwtBeforeHandlerRead() throws Exception {
    AtomicInteger descriptorReads = new AtomicInteger();
    Fixture fixture = Fixture.create(temporaryDirectory, pki);
    startGameDesignServer(
        fixture,
        descriptorReads,
        new AtomicReference<>(),
        new AtomicReference<>(),
        new AtomicReference<>(),
        new AtomicBoolean());

    gameSessionChannel = channel("localhost", gameDesignServer.getPort(), pki.worldManagement());
    var missingJwtStub = GameDesignServiceGrpc.newBlockingStub(gameSessionChannel);
    assertThatThrownBy(
            () ->
                missingJwtStub.getLaunchDescriptor(GetLaunchDescriptorRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(descriptorReads).hasValue(0);

    JwtUtil wrongJwtUtil = new JwtUtil("different-testsecretkeytestsecretkey1234", 3_600_000L);
    var wrongJwtStub =
        GrpcClientAuth.attach(
            GameDesignServiceGrpc.newBlockingStub(gameSessionChannel),
            wrongJwtUtil,
            WORLD_RUNTIME_IDENTITY);
    assertThatThrownBy(
            () -> wrongJwtStub.getLaunchDescriptor(GetLaunchDescriptorRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(descriptorReads).hasValue(0);
  }

  private void startGameDesignServer(
      Fixture fixture,
      AtomicInteger descriptorReads,
      AtomicReference<GrpcPeerIdentity> worldAtOwner,
      AtomicReference<GrpcPeerCertificateEvidence> worldCertificateAtOwner,
      AtomicReference<GetLaunchDescriptorRequest> requestAtOwner,
      AtomicBoolean returnChangedDescriptor)
      throws Exception {
    String expectedWorldFingerprint = fingerprint(pki.worldManagement().certificate());
    var service =
        new GameDesignServiceGrpc.GameDesignServiceImplBase() {
          @Override
          public void getLaunchDescriptor(
              GetLaunchDescriptorRequest request,
              StreamObserver<GetLaunchDescriptorResponse> responseObserver) {
            descriptorReads.incrementAndGet();
            worldAtOwner.set(GrpcPeerIdentity.current());
            worldCertificateAtOwner.set(GrpcPeerCertificateEvidence.current());
            requestAtOwner.set(request);
            if (worldAtOwner.get() == null
                || !WORLD_URI.equals(worldAtOwner.get().uri())
                || !worldAtOwner.get().isService("world-management-service")
                || !worldAtOwner.get().isInNamespace("dev")
                || worldCertificateAtOwner.get() == null
                || !expectedWorldFingerprint.equals(worldCertificateAtOwner.get().leafSha256())
                || !fixture
                    .descriptorEvidence()
                    .request()
                    .canonicalTenantId()
                    .toString()
                    .equals(request.getCanonicalTenantId())
                || !isExpectedReadRequestId(request.getRequestId(), fixture.operationId)
                || !fixture.descriptorEvidence().worldSlug().equals(request.getWorldSlug())
                || !fixture.operationId.equals(request.getControlPlaneRequestId())
                || !fixture
                    .descriptorEvidence()
                    .requestDigest()
                    .equals(request.getExpectedRequestDigest())
                || !fixture
                    .descriptorEvidence()
                    .resultDigest()
                    .equals(request.getExpectedResultDigest())) {
              responseObserver.onError(
                  Status.PERMISSION_DENIED
                      .withDescription(
                          "World peer or exact launch descriptor read identity did not match")
                      .asRuntimeException());
              return;
            }
            responseObserver.onNext(
                GetLaunchDescriptorResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setLaunchDescriptor(
                        returnChangedDescriptor.get()
                            ? fixture.launchDescriptor().toBuilder()
                                .setRuntimeFlagsJson("{\"ownerChanged\":true}")
                                .build()
                            : fixture.launchDescriptor())
                    .build());
            responseObserver.onCompleted();
          }
        };
    gameDesignServer =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.gameDesignServer().privateKey(),
                            pki.gameDesignServer().certificate()))
                    .trustManager(pki.caCertificateValue())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    new GrpcPeerIdentityInterceptor(),
                    new AuthTokenInterceptor(WORLD_JWT_UTIL)))
            .build()
            .start();
  }

  private void startGameSessionOwnerServer(
      Fixture fixture,
      AtomicInteger fixtureReads,
      AtomicReference<GrpcPeerIdentity> worldAtOwner,
      AtomicReference<GrpcPeerCertificateEvidence> worldCertificateAtOwner,
      AtomicReference<CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest>
          requestAtOwner)
      throws Exception {
    String expectedWorldFingerprint = fingerprint(pki.worldManagement().certificate());
    var service =
        new GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase() {
          @Override
          public void getRunOwnedFixtureLaunchAssociation(
              GetRunOwnedFixtureLaunchAssociationRequest request,
              StreamObserver<GetRunOwnedFixtureLaunchAssociationResponse> responseObserver) {
            fixtureReads.incrementAndGet();
            worldAtOwner.set(GrpcPeerIdentity.current());
            worldCertificateAtOwner.set(GrpcPeerCertificateEvidence.current());
            try {
              var selector =
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureRequest(request);
              requestAtOwner.set(selector);
              if (worldAtOwner.get() == null
                  || !WORLD_URI.equals(worldAtOwner.get().uri())
                  || !worldAtOwner.get().isService("world-management-service")
                  || !worldAtOwner.get().isInNamespace("dev")
                  || worldCertificateAtOwner.get() == null
                  || !expectedWorldFingerprint.equals(worldCertificateAtOwner.get().leafSha256())
                  || !"dev".equals(selector.targetNamespace())
                  || !fixture.operationId.equals(selector.fixtureOperationId().toString())
                  || !fixture
                      .policySeal
                      .evidenceDigest()
                      .equals(selector.expectedFixturePolicyEvidenceDigest())) {
                responseObserver.onError(
                    Status.PERMISSION_DENIED
                        .withDescription(
                            "World peer or exact fixture launch selector did not match")
                        .asRuntimeException());
                return;
              }
              responseObserver.onNext(
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(
                      fixture.fixtureAssociation(selector)));
              responseObserver.onCompleted();
            } catch (RuntimeException invalid) {
              responseObserver.onError(
                  Status.INVALID_ARGUMENT
                      .withDescription("Fixture owner read selector was invalid")
                      .asRuntimeException());
            }
          }
        };
    gameSessionOwnerServer =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.gameSessionClient().privateKey(),
                            pki.gameSessionClient().certificate()))
                    .trustManager(pki.caCertificateValue())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private Server startWorldServer(WorldManagementGrpcService service) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.worldManagement().privateKey(), pki.worldManagement().certificate()))
                .trustManager(pki.caCertificateValue())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private ManagedChannel channel(String host, int port, TestIdentity identity) throws Exception {
    return NettyChannelBuilder.forAddress(host, port)
        .overrideAuthority("localhost")
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forClient()
                        .keyManager(identity.privateKey(), identity.certificate())
                        .trustManager(pki.caCertificateValue()))
                .build())
        .build();
  }

  private static boolean isExpectedReadRequestId(String requestId, String operationId) {
    try {
      return !operationId.equals(UUID.fromString(requestId).toString());
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static final class Fixture {
    private final TestPki pki;
    private final Path directory;
    private final Path capabilityPath;
    private final Path receiptPath;
    private final String operationId;
    private final String claimDigest;
    private final byte[] originalCapabilityBytes;
    private final byte[] originalReceiptBytes;
    private final AuthoredWorldSourceEvidence source;
    private final PublishedReleaseBundle bundle;
    private final VersionAssetArtifactState artifact;
    private final VersionStateSnapshot version;
    private final LaunchDescriptor launchDescriptor;
    private final AuthoredWorldLaunchDescriptorEvidence descriptorEvidence;
    private final FreshTenantCreationEvidence creationEvidence;
    private final RunOwnedInitialAdmissionFixtureWireCodec.Capability capability;
    private final RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt sourceReceipt;
    private final RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence launchReadEvidence;
    private final RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal policySeal;

    private Fixture(Path parent, TestPki pki) throws Exception {
      this.pki = pki;
      directory = Files.createDirectories(parent.resolve("world-fixture-" + UUID.randomUUID()));
      capabilityPath = directory.resolve("capability.json");
      receiptPath = directory.resolve("source-receipt.json");
      claimDigest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest((RUN_ID + "\n" + PROJECT + "\n").getBytes(StandardCharsets.UTF_8)));
      operationId =
          RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                  claimDigest,
                  RUN_ID,
                  PROJECT,
                  fingerprint(pki.gameSessionClient().certificate()),
                  fingerprint(pki.caCertificateValue()))
              .toString();
      source = sourceEvidence(operationId);
      bundle = publishedBundle(source);
      artifact = artifactState();
      version = versionState();
      descriptorEvidence =
          RunOwnedCanonicalWorldLaunchEvidenceMtlsIntegrationTest.descriptorEvidence(
              source, bundle, version, operationId);
      launchDescriptor =
          AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptorEvidence);
      creationEvidence = creationEvidence(source);
      launchReadEvidence =
          new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
              RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
              17L,
              bundle,
              artifact,
              version,
              launchDescriptor);
      String policyRequestDigest =
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
              RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(
                  capabilityJson(pki, operationId, claimDigest).getBytes(StandardCharsets.UTF_8)),
              creationEvidence,
              source,
              launchReadEvidence);
      policySeal =
          new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
              RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
              RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
              UUID.fromString(operationId),
              policyRequestDigest,
              RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                  UUID.fromString(operationId), policyRequestDigest));
      String capabilityJson = capabilityJson(pki, operationId, claimDigest);
      String receiptJson =
          receiptJson(
              source,
              bundle,
              artifact,
              version,
              launchDescriptor,
              creationEvidence,
              policySeal,
              operationId,
              pki);
      originalCapabilityBytes = capabilityJson.getBytes(StandardCharsets.UTF_8);
      originalReceiptBytes = receiptJson.getBytes(StandardCharsets.UTF_8);
      writeReadonly(capabilityPath, originalCapabilityBytes);
      writeReadonly(receiptPath, originalReceiptBytes);
      capability =
          RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(originalCapabilityBytes);
      sourceReceipt =
          RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(
              originalReceiptBytes, capability);
    }

    static Fixture create(Path parent, TestPki pki) throws Exception {
      return new Fixture(parent, pki);
    }

    RunOwnedCanonicalWorldLaunchEvidenceProvider provider(
        GameDesignClient client, ServiceEndpointsProperties endpoints) {
      return new RunOwnedCanonicalWorldLaunchEvidenceProvider(
          true,
          capabilityPath.toString(),
          receiptPath.toString(),
          RUN_ID,
          PROJECT,
          pki.worldClientProperties(),
          client,
          endpoints,
          new GrpcChannelFactory(),
          "dev");
    }

    CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult fixtureAssociation(
        CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest request) {
      return new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
          request,
          142L,
          1001L,
          source.canonicalTenantId(),
          UUID.fromString("20000000-0000-4000-8000-000000000011"),
          UUID.fromString(bundle.getCanonicalVersionId()),
          UUID.fromString("30000000-0000-4000-8000-000000000011"),
          UUID.fromString("40000000-0000-4000-8000-000000000011"),
          1L,
          CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
          0L,
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
              capability, creationEvidence, source, launchReadEvidence),
          policySeal);
    }

    LaunchDescriptor launchDescriptor() {
      return launchDescriptor;
    }

    AuthoredWorldLaunchDescriptorEvidence descriptorEvidence() {
      return descriptorEvidence;
    }
  }

  private static AuthoredWorldSourceEvidence sourceEvidence(String operationId) {
    UUID registrationId = UUID.fromString(operationId);
    UUID tenant = UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d");
    UUID sourceOperationId = UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, tenant, "fixture-tenant", "demo", "Demo World");
    return new AuthoredWorldSourceEvidence(
        1,
        "dev",
        registrationId,
        sourceOperationId,
        requestDigest,
        tenant,
        "fixture-tenant",
        "demo",
        "Demo World",
        29L,
        "42",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            tenant,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW"));
  }

  private static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    UUID creationRequestId = UUID.fromString("1b3fdbda-a9f6-4cf5-b21e-b43f0e855f65");
    UUID creationOperationId = UUID.fromString("31e36104-3336-4cf5-899a-cb419f2f361e");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            "dev", creationRequestId, "42", "Fixture Game", null);
    return new FreshTenantCreationEvidence(
        1,
        "dev",
        creationRequestId,
        creationOperationId,
        requestDigest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            "dev",
            creationRequestId,
            creationOperationId,
            requestDigest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()));
  }

  private static PublishedReleaseBundle publishedBundle(AuthoredWorldSourceEvidence source) {
    return PublishedReleaseBundle.newBuilder()
        .setId(77L)
        .setVersionId(11L)
        .setVersionNumber(1)
        .setAttestationSchemaVersion("v1")
        .setPublishWorkflowId("publish-11")
        .setManifestHash("sha256:" + "c".repeat(64))
        .addRequiredManifestAssetKeys("manifest.json")
        .setGenerationConfigRevision("genrev-11")
        .setPublishedAt("2026-10-11T10:00:00Z")
        .setPublishedReleaseBundleRef(RELEASE_REF)
        .setCanonicalTenantId(source.canonicalTenantId().toString())
        .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
        .setManifestSchemaVersion(1)
        .addArtifactDigests(
            net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                .setUsageKey("manifest.json")
                .setArtifactKind("manifest")
                .setImmutableObjectKey("artifacts/sha256/" + "d".repeat(64))
                .setContentDigest("sha256:" + "d".repeat(64))
                .setContentType("application/json")
                .setArtifactSchemaVersion(1)
                .build())
        .build();
  }

  private static VersionAssetArtifactState artifactState() {
    return VersionAssetArtifactState.newBuilder()
        .setTenantId("42")
        .setVersionId(11L)
        .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
        .setStateEpoch(2L)
        .setManifestHash("sha256:" + "c".repeat(64))
        .setUpdatedAt("2026-10-11T10:00:00Z")
        .addExportedManifestAssetKeys("manifest.json")
        .setExportedVersionNumber(1)
        .build();
  }

  private static VersionStateSnapshot versionState() {
    return VersionStateSnapshot.newBuilder()
        .setTenantId("42")
        .setVersionId(11L)
        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
        .setVersionStateEpoch(77L)
        .setUpdatedAt("2026-10-11T10:00:00Z")
        .build();
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest(
      AuthoredWorldSourceEvidence source, String operationId) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        "dev",
        operationId,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        17L,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptorEvidence(
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionStateSnapshot version,
      String operationId) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        expectedRequest(source, operationId),
        "launch-descriptor-fixture-11",
        bundle.getVersionId(),
        false,
        null,
        "{}",
        bundle.getGenerationConfigRevision(),
        version.getVersionStateEpoch(),
        bundle.getId(),
        bundle.getPublishedReleaseBundleRef(),
        false,
        null);
  }

  private static String capabilityJson(TestPki pki, String operationId, String claimDigest)
      throws Exception {
    return """
        {"schema":"firemud.run-owned-initial-admission-fixture.v1","runId":"%s","composeProjectName":"%s","operationId":"%s","tenantId":42,"gameTemplateId":17,"ownerAccountId":9,"worldSlug":"demo","worldDisplayName":"Demo World","realmSlug":"production","realmDisplayName":"Live Realm","visible":true,"publicProductionRealm":true,"requiresCharacterSelection":false,"stateScope":"SHARED","characterCreationPolicy":"ALLOW_NEW","gameSessionLeafSha256":"%s","gameSessionUriSan":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","claimDigest":"%s"}\
        """
        .formatted(
            RUN_ID,
            PROJECT,
            operationId,
            fingerprint(pki.gameSessionClient().certificate()),
            GS_URI,
            fingerprint(pki.caCertificateValue()),
            WORLD_URI,
            fingerprint(pki.worldManagement().certificate()),
            claimDigest);
  }

  private static String receiptJson(
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifact,
      VersionStateSnapshot version,
      LaunchDescriptor descriptor,
      FreshTenantCreationEvidence creation,
      RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal seal,
      String operationId,
      TestPki pki)
      throws Exception {
    return """
        {"schema":"firemud.run-owned-authored-world-source-receipt.v1","runId":"%s","composeProjectName":"%s","fixtureOperationId":"%s","ownerService":"game-design-service","gameSessionUriSan":"%s","gameSessionLeafSha256":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","sourceEvidence":{"schemaVersion":1,"targetNamespace":"dev","registrationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","tenantSlug":"%s","worldSlug":"%s","worldDisplayName":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"launchReadEvidence":{"schema":"firemud.run-owned-v1-launch-read-evidence.v1","gameTemplateId":17,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s","launchDescriptorBase64":"%s"},"creationEvidence":{"schemaVersion":%d,"targetNamespace":"%s","creationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"fixturePolicySeal":{"schema":"%s","ownerService":"%s","operationId":"%s","requestDigest":"%s","evidenceDigest":"%s"}}\
        """
        .formatted(
            RUN_ID,
            PROJECT,
            operationId,
            GS_URI,
            fingerprint(pki.gameSessionClient().certificate()),
            fingerprint(pki.caCertificateValue()),
            WORLD_URI,
            fingerprint(pki.worldManagement().certificate()),
            source.registrationRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest(),
            encode(bundle.toByteArray()),
            encode(artifact.toByteArray()),
            encode(version.toByteArray()),
            encode(descriptor.toByteArray()),
            creation.schemaVersion(),
            creation.targetNamespace(),
            creation.creationRequestId(),
            creation.operationId(),
            creation.requestDigest(),
            creation.canonicalTenantId(),
            creation.sourceGameRowId(),
            creation.sourceGameTenantKey(),
            creation.provenanceKind(),
            creation.evidenceDigest(),
            seal.schema(),
            seal.ownerService(),
            seal.operationId(),
            seal.requestDigest(),
            seal.evidenceDigest());
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String fingerprint(X509Certificate certificate) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  private static void writeReadonly(Path path, byte[] value) throws IOException {
    Files.write(path, value);
    Files.setPosixFilePermissions(
        path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
  }

  private record TestIdentity(
      PrivateKey privateKey,
      X509Certificate certificate,
      Path certificatePath,
      Path privateKeyPath) {}

  private record TestPki(
      X509Certificate caCertificateValue,
      Path caCertificatePath,
      TestIdentity worldManagement,
      TestIdentity gameDesignServer,
      TestIdentity gameSessionClient) {
    static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("world-mtls-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=World mTLS test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path ca = directory.resolve("world-mtls-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          ca.toString(),
          "-rfc");
      X509Certificate caCertificate;
      try (var input = Files.newInputStream(ca)) {
        caCertificate =
            (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
      return new TestPki(
          caCertificate,
          ca,
          issueIdentity(
              directory, caStore, ca, "world-management", WORLD_URI, "serverAuth,clientAuth"),
          issueIdentity(directory, caStore, ca, "game-design", GDS_URI, "serverAuth"),
          issueIdentity(directory, caStore, ca, "game-session", GS_URI, "serverAuth,clientAuth"));
    }

    CommonGrpcClientProperties worldClientProperties() {
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(worldManagement.certificatePath().toString());
      properties.setPrivateKey(worldManagement.privateKeyPath().toString());
      properties.setCaCert(caCertificatePath.toString());
      properties.setPlaintext(false);
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory,
        Path caStore,
        Path caFile,
        String alias,
        String uri,
        String extendedKeyUsage)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + uri + ",DNS:localhost,IP:127.0.0.1";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      PrivateKey key = (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
      X509Certificate leaf = (X509Certificate) keyStore.getCertificate(alias);
      Path certificatePem =
          writePem(directory.resolve(alias + "-chain.pem"), "CERTIFICATE", leaf.getEncoded());
      Path keyPem =
          writePem(directory.resolve(alias + "-key.pem"), "PRIVATE KEY", key.getEncoded());
      return new TestIdentity(key, leaf, certificatePem, keyPem);
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      List<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool timed out");
      }
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
    }
  }
}
