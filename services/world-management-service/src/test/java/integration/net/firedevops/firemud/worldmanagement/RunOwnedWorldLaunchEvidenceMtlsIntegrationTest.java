package net.firedevops.firemud.worldmanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** Socket-level proof of authenticated GS to World and World to exact GDS descriptor transport. */
class RunOwnedWorldLaunchEvidenceMtlsIntegrationTest {
  private static final String RUN_ID = "world-mtls-fixture-test";
  private static final String PROJECT = "firemud-world-mtls-fixture-test";
  private static final String CLAIM_DIGEST = sha256Hex("controlled-world-mtls-fixture-claim-v1");
  private static final String GS_URI = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/dev/sa/world-management-service";
  private static final String GDS_URI = "spiffe://firemud/ns/dev/sa/game-design-service";
  private static final String WRONG_GS_URI = "spiffe://firemud/ns/other/sa/game-session-service";
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";
  private static final String STORE_PASSWORD = "test-only-world-mtls-password";
  private static final String TEST_JWT_SECRET = "testsecretkeytestsecretkeytest1234";
  private static final JwtUtil WORLD_JWT_UTIL = new JwtUtil(TEST_JWT_SECRET, 3_600_000L);
  private static final RuntimeIdentity GAME_SESSION_RUNTIME_IDENTITY =
      new RuntimeIdentity(
          "game-session-service",
          "world-mtls-fixture-test-gs",
          "localhost",
          Instant.parse("2026-10-11T00:00:00Z"),
          "test",
          "test",
          "local");
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
  private Server gameDesignServer;
  private GameDesignClient gameDesignClient;
  private ManagedChannel gameSessionChannel;

  @BeforeAll
  static void createCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    SessionContext.clear();
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
  }

  @Test
  void physicalMtlsPrepareActivateAndOriginalEpochReplayReverifyReceiptAndOwnerDescriptor()
      throws Exception {
    Fixture fixture = Fixture.create(temporaryDirectory, pki);
    AtomicInteger descriptorReads = new AtomicInteger();
    AtomicReference<GrpcPeerIdentity> worldAtOwner = new AtomicReference<>();
    AtomicReference<GrpcPeerCertificateEvidence> worldCertificateAtOwner = new AtomicReference<>();
    AtomicReference<GetLaunchDescriptorRequest> requestAtOwner = new AtomicReference<>();
    AtomicBoolean returnChangedDescriptor = new AtomicBoolean();
    startGameDesignServer(
        fixture,
        descriptorReads,
        worldAtOwner,
        worldCertificateAtOwner,
        requestAtOwner,
        returnChangedDescriptor);
    CommonGrpcClientProperties worldTls = pki.worldClientProperties();
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + gameDesignServer.getPort());
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
      RunOwnedWorldLaunchEvidenceProvider evidenceProvider = fixture.provider(gameDesignClient);
      AtomicReference<WorldInstance> storedWorld = new AtomicReference<>();
      AtomicInteger worldSaves = new AtomicInteger();
      AtomicInteger entityCleanupCalls = new AtomicInteger();
      WorldInstanceRepository worldRepository = mockWorldRepository(storedWorld, worldSaves);
      WorldLifecycleCommandServiceImpl commandService =
          newCommandService(
              worldRepository, evidenceProvider, gameDesignClient, entityCleanupCalls);
      WorldInstanceActivationServiceImpl activationService =
          new WorldInstanceActivationServiceImpl(
              commandService, java.util.Optional.empty(), evidenceProvider, worldRepository);
      AtomicReference<GrpcPeerIdentity> gameSessionAtWorld = new AtomicReference<>();
      WorldManagementGrpcService grpcService =
          new WorldManagementGrpcService(
              null,
              null,
              activationService,
              null,
              null,
              null,
              null,
              new SimpleMeterRegistry(),
              JsonMapper.builder().build(),
              "dev") {
            @Override
            public void prepareWorldInstance(
                PrepareWorldInstanceRequest request,
                StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse>
                    responseObserver) {
              gameSessionAtWorld.set(GrpcPeerIdentity.current());
              super.prepareWorldInstance(request, responseObserver);
            }

            @Override
            public void activatePreparedWorldInstance(
                ActivatePreparedWorldInstanceRequest request,
                StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1
                            .ActivatePreparedWorldInstanceResponse>
                    responseObserver) {
              gameSessionAtWorld.set(GrpcPeerIdentity.current());
              super.activatePreparedWorldInstance(request, responseObserver);
            }

            @Override
            public void failPreparedWorldInstance(
                FailPreparedWorldInstanceRequest request,
                StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse>
                    responseObserver) {
              gameSessionAtWorld.set(GrpcPeerIdentity.current());
              super.failPreparedWorldInstance(request, responseObserver);
            }

            @Override
            public void terminateWorldInstance(
                TerminateWorldInstanceRequest request,
                StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse>
                    responseObserver) {
              gameSessionAtWorld.set(GrpcPeerIdentity.current());
              super.terminateWorldInstance(request, responseObserver);
            }
          };
      worldServer = startWorldServer(grpcService);
      gameSessionChannel = channel("localhost", worldServer.getPort(), pki.gameSessionClient());
      WorldManagementServiceGrpc.WorldManagementServiceBlockingStub gameSession =
          authenticatedGameSessionStub();

      PrepareWorldInstanceRequest prepareRequest =
          PrepareWorldInstanceRequest.newBuilder()
              .setTenantId("42")
              .setGameInstanceId("101")
              .setGameTemplateId("17")
              .setControlPlaneRequestId(fixture.operationId())
              .setLaunchDescriptorId("launch-descriptor-fixture-11")
              .setVersionId("11")
              .setRuntimeFlagsJson("{}")
              .setGenerationConfigRevision("genrev-11")
              .setReleaseBundleId("77")
              .setPublishedReleaseBundleRef(RELEASE_REF)
              .setVersionStateEpoch(77L)
              .build();
      var missingJwtStub = WorldManagementServiceGrpc.newBlockingStub(gameSessionChannel);
      assertThatThrownBy(() -> missingJwtStub.prepareWorldInstance(prepareRequest))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(worldSaves).hasValue(0);
      assertThat(descriptorReads).hasValue(0);
      assertThat(entityCleanupCalls).hasValue(0);

      var prepare = gameSession.prepareWorldInstance(prepareRequest);
      assertThat(prepare.hasError()).isFalse();
      assertThat(prepare.getWorldInstance().getStatus())
          .isEqualTo(WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING);
      assertThat(storedWorld.get().getRunOwnedFixtureBindingSha256()).matches("[0-9a-f]{64}");
      assertThat(storedWorld.get().getRunOwnedFixturePreparingLifecycleEpoch()).isEqualTo(1L);
      int writesAfterPrepare = worldSaves.get();
      int readsAfterPrepare = descriptorReads.get();
      SessionContext.setContext("42", List.of("tenantAdmin"), java.util.Map.of());
      var playerContextFailure =
          authenticatedPlayerGameSessionStub()
              .failPreparedWorldInstance(
                  FailPreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .setReason("denied player context")
                      .build());
      SessionContext.clear();
      assertThat(playerContextFailure.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterPrepare);
      assertThat(descriptorReads).hasValue(readsAfterPrepare);

      var wrongReference =
          gameSession.prepareWorldInstance(
              PrepareWorldInstanceRequest.newBuilder()
                  .setTenantId("42")
                  .setGameInstanceId("101")
                  .setGameTemplateId("17")
                  .setControlPlaneRequestId(fixture.operationId())
                  .setLaunchDescriptorId("launch-descriptor-fixture-11")
                  .setVersionId("11")
                  .setRuntimeFlagsJson("{}")
                  .setGenerationConfigRevision("genrev-11")
                  .setReleaseBundleId("77")
                  .setPublishedReleaseBundleRef("11111111-1111-4111-8111-111111111111")
                  .setVersionStateEpoch(77L)
                  .build());
      assertThat(wrongReference.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterPrepare);
      assertThat(descriptorReads).hasValue(2);

      var activated =
          gameSession.activatePreparedWorldInstance(
              ActivatePreparedWorldInstanceRequest.newBuilder()
                  .setTenantId("42")
                  .setGameInstanceId("101")
                  .setExpectedLifecycleEpoch(1L)
                  .build());
      assertThat(activated.hasError()).isFalse();
      assertThat(activated.getWorldInstance().getStatus())
          .isEqualTo(WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
      assertThat(activated.getWorldInstance().getLifecycleEpoch()).isEqualTo(2L);
      int writesAfterActivation = worldSaves.get();

      var replay =
          gameSession.activatePreparedWorldInstance(
              ActivatePreparedWorldInstanceRequest.newBuilder()
                  .setTenantId("42")
                  .setGameInstanceId("101")
                  .setExpectedLifecycleEpoch(1L)
                  .build());
      assertThat(replay.hasError()).isFalse();
      assertThat(replay.getWorldInstance()).isEqualTo(activated.getWorldInstance());
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);
      assertThat(worldAtOwner.get())
          .isNotNull()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo(WORLD_URI);
      assertThat(worldAtOwner.get().isService("world-management-service")).isTrue();
      assertThat(worldAtOwner.get().isInNamespace("dev")).isTrue();
      assertThat(worldCertificateAtOwner.get())
          .isNotNull()
          .extracting(GrpcPeerCertificateEvidence::leafSha256)
          .isEqualTo(fingerprint(pki.worldManagement().certificate()));
      assertThat(requestAtOwner.get()).isNotNull();
      assertThat(requestAtOwner.get().getRequestId()).isNotBlank();
      assertThat(requestAtOwner.get().getCanonicalTenantId())
          .isEqualTo(fixture.descriptorEvidence().request().canonicalTenantId().toString());
      assertThat(requestAtOwner.get().getWorldSlug())
          .isEqualTo(fixture.descriptorEvidence().worldSlug());
      assertThat(requestAtOwner.get().getControlPlaneRequestId()).isEqualTo(fixture.operationId());
      assertThat(requestAtOwner.get().getExpectedRequestDigest())
          .isEqualTo(fixture.descriptorEvidence().requestDigest());
      assertThat(requestAtOwner.get().getExpectedResultDigest())
          .isEqualTo(fixture.descriptorEvidence().resultDigest());
      assertThat(gameSessionAtWorld.get())
          .isNotNull()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo(GS_URI);

      SessionContext.setContext("42", List.of("tenantAdmin"), java.util.Map.of());
      var ordinaryCaller =
          authenticatedPlayerGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      SessionContext.clear();
      assertThat(ordinaryCaller.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel = channelWithoutClientIdentity("localhost", worldServer.getPort());
      assertThatThrownBy(
              () ->
                  authenticatedGameSessionStub()
                      .activatePreparedWorldInstance(
                          ActivatePreparedWorldInstanceRequest.newBuilder()
                              .setTenantId("42")
                              .setGameInstanceId("101")
                              .setExpectedLifecycleEpoch(1L)
                              .build()))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel =
          channel("localhost", worldServer.getPort(), pki.wrongNamespaceGameSessionClient());
      var wrongPeer =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(wrongPeer.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel =
          channel("localhost", worldServer.getPort(), pki.wrongGameSessionLeafClient());
      var wrongLeaf =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(wrongLeaf.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel =
          channel("localhost", worldServer.getPort(), pki.wrongCaGameSessionClient());
      assertThatThrownBy(
              () ->
                  authenticatedGameSessionStub()
                      .activatePreparedWorldInstance(
                          ActivatePreparedWorldInstanceRequest.newBuilder()
                              .setTenantId("42")
                              .setGameInstanceId("101")
                              .setExpectedLifecycleEpoch(1L)
                              .build()))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel = channel("localhost", worldServer.getPort(), pki.gameSessionClient());
      fixture.reset();
      Fixture.writeFieldMismatch(fixture, "caCertificateSha256", "f".repeat(64));
      var wrongCa =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(wrongCa.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      fixture.reset();
      Fixture.writeFieldMismatch(fixture, "worldManagementLeafSha256", "e".repeat(64));
      var wrongWorldRecipient =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(wrongWorldRecipient.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      fixture.reset();
      Fixture.writeFieldMismatch(
          fixture, "worldManagementUriSan", "spiffe://firemud/ns/dev/sa/other-world-service");
      var wrongWorldUri =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(wrongWorldUri.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      fixture.reset();
      var unknownInstance =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("202")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(unknownInstance.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(4);

      var duplicateOperation =
          authenticatedGameSessionStub()
              .prepareWorldInstance(
                  PrepareWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("202")
                      .setGameTemplateId("17")
                      .setControlPlaneRequestId(fixture.operationId())
                      .setLaunchDescriptorId("launch-descriptor-fixture-11")
                      .setVersionId("11")
                      .setRuntimeFlagsJson("{}")
                      .setGenerationConfigRevision("genrev-11")
                      .setReleaseBundleId("77")
                      .setPublishedReleaseBundleRef(RELEASE_REF)
                      .setVersionStateEpoch(77L)
                      .build());
      assertThat(duplicateOperation.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(5);

      var staleAttempt =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(99L)
                      .build());
      assertThat(staleAttempt.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      Fixture.writeReceiptFieldMismatch(fixture, "sourceGameTenantKey", "43");
      var changedSource =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(changedSource.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      Fixture.writeFieldMismatch(fixture, "runId", "changed-run");
      var changedRun =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(changedRun.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      Fixture.writeFieldMismatch(fixture, "composeProjectName", "different-project");
      var changedProject =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(changedProject.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      Fixture.writeReceiptFieldMismatch(
          fixture, "fixtureOperationId", "11111111-1111-4111-8111-111111111111");
      var changedReceipt =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(changedReceipt.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      Fixture.writeReceiptFieldMismatch(fixture, "launchDescriptorBase64", "AAAA");
      var changedReceiptDescriptor =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      assertThat(changedReceiptDescriptor.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(6);

      fixture.reset();
      returnChangedDescriptor.set(true);
      var changedOwnerDescriptor =
          authenticatedGameSessionStub()
              .activatePreparedWorldInstance(
                  ActivatePreparedWorldInstanceRequest.newBuilder()
                      .setTenantId("42")
                      .setGameInstanceId("101")
                      .setExpectedLifecycleEpoch(1L)
                      .build());
      returnChangedDescriptor.set(false);
      assertThat(changedOwnerDescriptor.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesAfterActivation);
      assertThat(descriptorReads).hasValue(7);

      fixture.reset();
      TerminateWorldInstanceRequest terminationRequest =
          TerminateWorldInstanceRequest.newBuilder()
              .setTenantId("42")
              .setGameInstanceId("101")
              .setExpectedLifecycleEpoch(2L)
              .setTerminationRequestId("term-mtls-fixture-11")
              .setReason("stop")
              .build();
      int writesBeforeTermination = worldSaves.get();
      int readsBeforeDeniedTermination = descriptorReads.get();

      SessionContext.setContext("42", List.of("tenantAdmin"), java.util.Map.of());
      var playerContextTermination =
          authenticatedPlayerGameSessionStub().terminateWorldInstance(terminationRequest);
      SessionContext.clear();
      assertThat(playerContextTermination.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesBeforeTermination);
      assertThat(entityCleanupCalls).hasValue(0);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel =
          channel("localhost", worldServer.getPort(), pki.wrongNamespaceGameSessionClient());
      var wrongPeerTermination =
          authenticatedGameSessionStub().terminateWorldInstance(terminationRequest);
      assertThat(wrongPeerTermination.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesBeforeTermination);
      assertThat(entityCleanupCalls).hasValue(0);
      assertThat(descriptorReads).hasValue(readsBeforeDeniedTermination);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel =
          channel("localhost", worldServer.getPort(), pki.wrongGameSessionLeafClient());
      var wrongLeafTermination =
          authenticatedGameSessionStub().terminateWorldInstance(terminationRequest);
      assertThat(wrongLeafTermination.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesBeforeTermination);
      assertThat(entityCleanupCalls).hasValue(0);
      assertThat(descriptorReads).hasValue(readsBeforeDeniedTermination);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel = channelWithoutClientIdentity("localhost", worldServer.getPort());
      assertThatThrownBy(
              () -> authenticatedGameSessionStub().terminateWorldInstance(terminationRequest))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(worldSaves).hasValue(writesBeforeTermination);
      assertThat(entityCleanupCalls).hasValue(0);

      gameSessionChannel.shutdownNow();
      assertThat(gameSessionChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      gameSessionChannel = channel("localhost", worldServer.getPort(), pki.gameSessionClient());
      var terminated = authenticatedGameSessionStub().terminateWorldInstance(terminationRequest);
      assertThat(terminated.hasError()).isFalse();
      assertThat(terminated.getWorldInstance().getStatus())
          .isEqualTo(WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED);
      assertThat(terminated.getWorldInstance().getLifecycleEpoch()).isEqualTo(4L);
      assertThat(worldSaves).hasValue(writesBeforeTermination + 2);
      assertThat(entityCleanupCalls).hasValue(1);

      var terminalReplay =
          authenticatedGameSessionStub().terminateWorldInstance(terminationRequest);
      assertThat(terminalReplay.hasError()).isFalse();
      assertThat(terminalReplay.getWorldInstance()).isEqualTo(terminated.getWorldInstance());
      assertThat(worldSaves).hasValue(writesBeforeTermination + 2);
      assertThat(entityCleanupCalls).hasValue(1);

      var changedTerminalRequest =
          authenticatedGameSessionStub()
              .terminateWorldInstance(
                  terminationRequest.toBuilder().setTerminationRequestId("term-other").build());
      assertThat(changedTerminalRequest.hasError()).isTrue();
      assertThat(worldSaves).hasValue(writesBeforeTermination + 2);
      assertThat(entityCleanupCalls).hasValue(1);
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
                || !isExpectedReadRequestId(request.getRequestId(), fixture.operationId())
                || !fixture.descriptorEvidence().worldSlug().equals(request.getWorldSlug())
                || !fixture.operationId().equals(request.getControlPlaneRequestId())
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

  private Server startWorldServer(WorldManagementGrpcService service) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.worldManagement().privateKey(), pki.worldManagement().certificate()))
                .trustManager(pki.caCertificateValue())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                service,
                new GrpcPeerIdentityInterceptor(),
                new AuthTokenInterceptor(
                    WORLD_JWT_UTIL, Set.of("world_management.v1.WorldManagementService/Ping"))))
        .build()
        .start();
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub
      authenticatedGameSessionStub() {
    return GrpcClientAuth.attachInternal(
        WorldManagementServiceGrpc.newBlockingStub(gameSessionChannel),
        WORLD_JWT_UTIL,
        GAME_SESSION_RUNTIME_IDENTITY);
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub
      authenticatedPlayerGameSessionStub() {
    return GrpcClientAuth.attach(
        WorldManagementServiceGrpc.newBlockingStub(gameSessionChannel),
        WORLD_JWT_UTIL,
        GAME_SESSION_RUNTIME_IDENTITY);
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

  private ManagedChannel channelWithoutClientIdentity(String host, int port) throws Exception {
    return NettyChannelBuilder.forAddress(host, port)
        .overrideAuthority("localhost")
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forClient().trustManager(pki.caCertificateValue()))
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

  private RunOwnedWorldLaunchEvidenceProvider provider(Path directory, GameDesignClient client) {
    CommonGrpcClientProperties tls = pki.worldClientProperties();
    return new RunOwnedWorldLaunchEvidenceProvider(
        true,
        directory.resolve("capability.json").toString(),
        directory.resolve("source-receipt.json").toString(),
        RUN_ID,
        PROJECT,
        tls,
        client);
  }

  private WorldInstanceRepository mockWorldRepository(
      AtomicReference<WorldInstance> storedWorld, AtomicInteger worldSaves) {
    WorldInstanceRepository repository = mock(WorldInstanceRepository.class);
    when(repository.findByTenantIdAndGameInstanceId(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              WorldInstance instance = storedWorld.get();
              return instance != null
                      && instance.getTenantId().equals(invocation.getArgument(0))
                      && instance.getGameInstanceId().equals(invocation.getArgument(1))
                  ? java.util.Optional.of(instance)
                  : java.util.Optional.empty();
            });
    when(repository.findByTenantIdAndGameInstanceIdForUpdate(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              WorldInstance instance = storedWorld.get();
              return instance != null
                      && instance.getTenantId().equals(invocation.getArgument(0))
                      && instance.getGameInstanceId().equals(invocation.getArgument(1))
                  ? java.util.Optional.of(instance)
                  : java.util.Optional.empty();
            });
    when(repository.findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
            anyLong(), any(String.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance instance = storedWorld.get();
              return instance != null
                      && instance.getTenantId().equals(invocation.getArgument(0))
                      && instance.getControlPlaneRequestId().equals(invocation.getArgument(1))
                  ? java.util.Optional.of(instance)
                  : java.util.Optional.empty();
            });
    when(repository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance instance = invocation.getArgument(0);
              if (instance.getId() == null) {
                instance.setId(101L);
              }
              worldSaves.incrementAndGet();
              storedWorld.set(instance);
              return instance;
            });
    return repository;
  }

  private WorldLifecycleCommandServiceImpl newCommandService(
      WorldInstanceRepository worldRepository,
      RunOwnedWorldLaunchEvidenceProvider evidenceProvider,
      GameDesignClient client,
      AtomicInteger entityCleanupCalls) {
    RegionInstanceRepository regionRepository = mock(RegionInstanceRepository.class);
    ZoneRepository zoneRepository = mock(ZoneRepository.class);
    ZoneInstanceRepository zoneInstanceRepository = mock(ZoneInstanceRepository.class);
    RoomRepository roomRepository = mock(RoomRepository.class);
    RoomExitRepository roomExitRepository = mock(RoomExitRepository.class);
    RoomInstanceRepository roomInstanceRepository = mock(RoomInstanceRepository.class);
    RoomInstanceExitRepository roomInstanceExitRepository = mock(RoomInstanceExitRepository.class);
    when(regionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(anyLong(), anyLong()))
        .thenReturn(List.of());
    when(zoneInstanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(anyLong(), anyLong()))
        .thenReturn(List.of());
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(anyLong(), anyLong()))
        .thenReturn(List.of());
    when(roomInstanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roomInstanceExitRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    EntityManagementClient entityManagementClient = mock(EntityManagementClient.class);
    when(entityManagementClient.cleanupRuntimeInstance(anyLong(), anyLong(), anyString()))
        .thenAnswer(
            invocation -> {
              entityCleanupCalls.incrementAndGet();
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });
    WorldProperties properties = new WorldProperties();
    properties.setLocalShardId(7);
    WorldLifecycleCommandServiceImpl commandService =
        new WorldLifecycleCommandServiceImpl(
            worldRepository,
            regionRepository,
            zoneRepository,
            zoneInstanceRepository,
            roomRepository,
            roomExitRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            mock(WorldEventRepository.class),
            properties,
            client,
            entityManagementClient,
            new SimpleMeterRegistry(),
            new PlatformTransactionManager() {
              @Override
              public SimpleTransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
              }

              @Override
              public void commit(TransactionStatus status) {}

              @Override
              public void rollback(TransactionStatus status) {}
            },
            evidenceProvider);
    commandService.initMetrics();
    return commandService;
  }

  /** Controlled closed local evidence; the launch descriptor is checked against the TLS owner. */
  private static final class Fixture {
    private final TestPki pki;
    private final Path directory;
    private final Path capabilityPath;
    private final Path receiptPath;
    private final String originalCapabilityJson;
    private final String originalReceiptJson;
    private String capabilityJson;
    private String receiptJson;
    private final AuthoredWorldSourceEvidence source;
    private final PublishedReleaseBundle bundle;
    private final VersionAssetArtifactState artifact;
    private final VersionStateSnapshot version;
    private final LaunchDescriptor launchDescriptor;
    private final AuthoredWorldLaunchDescriptorEvidence descriptorEvidence;
    private final String operationId;

    private Fixture(Path parent, TestPki pki) throws Exception {
      this.pki = pki;
      directory = Files.createDirectories(parent.resolve("world-fixture-" + UUID.randomUUID()));
      capabilityPath = directory.resolve("capability.json");
      receiptPath = directory.resolve("source-receipt.json");
      operationId =
          RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                  CLAIM_DIGEST,
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
          RunOwnedWorldLaunchEvidenceMtlsIntegrationTest.descriptorEvidence(
              source, bundle, version);
      launchDescriptor =
          AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptorEvidence);
      var capability = capability(pki, operationId);
      var creationEvidence = creationEvidence(source);
      var launchReadEvidence =
          new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
              RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
              17L,
              bundle,
              artifact,
              version,
              launchDescriptor);
      String policyRequestDigest =
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
              capability, creationEvidence, source, launchReadEvidence);
      var policySeal =
          new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
              RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
              RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
              UUID.fromString(operationId),
              policyRequestDigest,
              RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                  UUID.fromString(operationId), policyRequestDigest));
      capabilityJson = capabilityJson(capability);
      receiptJson =
          receiptJson(
              source,
              bundle,
              artifact,
              version,
              launchDescriptor,
              creationEvidence,
              policySeal,
              pki);
      originalCapabilityJson = capabilityJson;
      originalReceiptJson = receiptJson;
      writeReadonly(capabilityPath, capabilityJson.getBytes(StandardCharsets.UTF_8));
      writeReadonly(receiptPath, receiptJson.getBytes(StandardCharsets.UTF_8));
    }

    static Fixture create(Path parent, TestPki pki) throws Exception {
      return new Fixture(parent, pki);
    }

    RunOwnedWorldLaunchEvidenceProvider provider(GameDesignClient client) {
      CommonGrpcClientProperties tls = pki.worldClientProperties();
      return new RunOwnedWorldLaunchEvidenceProvider(
          true,
          directory.resolve("capability.json").toString(),
          directory.resolve("source-receipt.json").toString(),
          RUN_ID,
          PROJECT,
          tls,
          client);
    }

    LaunchDescriptor launchDescriptor() {
      return launchDescriptor;
    }

    AuthoredWorldLaunchDescriptorEvidence descriptorEvidence() {
      return descriptorEvidence;
    }

    String operationId() {
      return operationId;
    }

    void reset() throws IOException {
      capabilityJson = originalCapabilityJson;
      receiptJson = originalReceiptJson;
      writeReadonly(capabilityPath, capabilityJson.getBytes(StandardCharsets.UTF_8));
      writeReadonly(receiptPath, receiptJson.getBytes(StandardCharsets.UTF_8));
    }

    static void writeFieldMismatch(Fixture fixture, String fieldName, String replacement)
        throws IOException {
      fixture.capabilityJson = replaceField(fixture.capabilityJson, fieldName, replacement);
      fixture.receiptJson = replaceField(fixture.receiptJson, fieldName, replacement);
      writeReadonly(
          fixture.capabilityPath, fixture.capabilityJson.getBytes(StandardCharsets.UTF_8));
      writeReadonly(fixture.receiptPath, fixture.receiptJson.getBytes(StandardCharsets.UTF_8));
    }

    static void writeReceiptFieldMismatch(Fixture fixture, String fieldName, String replacement)
        throws IOException {
      fixture.receiptJson = replaceField(fixture.receiptJson, fieldName, replacement);
      writeReadonly(fixture.receiptPath, fixture.receiptJson.getBytes(StandardCharsets.UTF_8));
    }

    private static String replaceField(String json, String fieldName, String replacement) {
      String marker = "\"" + fieldName + "\":\"";
      int start = json.indexOf(marker);
      if (start < 0) {
        throw new IllegalStateException("Fixture field was not present: " + fieldName);
      }
      int valueStart = start + marker.length();
      int end = json.indexOf('"', valueStart);
      if (end < 0) {
        throw new IllegalStateException("Fixture field value was not terminated: " + fieldName);
      }
      return json.substring(0, valueStart) + replacement + json.substring(end);
    }
  }

  private static AuthoredWorldSourceEvidence sourceEvidence(String operationId) {
    UUID registrationId = UUID.fromString(operationId);
    UUID tenant = UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, tenant, "fixture-tenant", "demo", "Demo World");
    return new AuthoredWorldSourceEvidence(
        1,
        "dev",
        registrationId,
        UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed"),
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
            UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed"),
            requestDigest,
            tenant,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW"));
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
      AuthoredWorldSourceEvidence source) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        "dev",
        source.registrationRequestId().toString(),
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
      VersionStateSnapshot version) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        expectedRequest(source),
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

  private static RunOwnedInitialAdmissionFixtureWireCodec.Capability capability(
      TestPki pki, String operationId) throws Exception {
    return new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
        RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
        RUN_ID,
        PROJECT,
        operationId,
        42L,
        17L,
        9L,
        "demo",
        "Demo World",
        "production",
        "Live Realm",
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        fingerprint(pki.gameSessionClient().certificate()),
        GS_URI,
        fingerprint(pki.caCertificateValue()),
        WORLD_URI,
        fingerprint(pki.worldManagement().certificate()),
        CLAIM_DIGEST);
  }

  private static String capabilityJson(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability) {
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","operationId":"%s","tenantId":%d,"gameTemplateId":%d,"ownerAccountId":%d,"worldSlug":"%s","worldDisplayName":"%s","realmSlug":"%s","realmDisplayName":"%s","visible":%s,"publicProductionRealm":%s,"requiresCharacterSelection":%s,"stateScope":"%s","characterCreationPolicy":"%s","gameSessionLeafSha256":"%s","gameSessionUriSan":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","claimDigest":"%s"}\
        """
        .formatted(
            capability.schema(),
            capability.runId(),
            capability.composeProjectName(),
            capability.operationId(),
            capability.tenantId(),
            capability.gameTemplateId(),
            capability.ownerAccountId(),
            capability.worldSlug(),
            capability.worldDisplayName(),
            capability.realmSlug(),
            capability.realmDisplayName(),
            capability.visible(),
            capability.publicProductionRealm(),
            capability.requiresCharacterSelection(),
            capability.stateScope(),
            capability.characterCreationPolicy(),
            capability.gameSessionLeafSha256(),
            capability.gameSessionUriSan(),
            capability.caCertificateSha256(),
            capability.worldManagementUriSan(),
            capability.worldManagementLeafSha256(),
            capability.claimDigest());
  }

  private static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    UUID requestId = UUID.fromString("21a3b51a-444e-4c08-bbd5-31d40f643ba2");
    UUID operationId = UUID.fromString("3a78e019-c907-49ba-a80a-6d132aeb2135");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            source.targetNamespace(),
            requestId,
            source.sourceGameTenantKey(),
            source.worldDisplayName(),
            null);
    return new FreshTenantCreationEvidence(
        1,
        source.targetNamespace(),
        requestId,
        operationId,
        requestDigest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            requestId,
            operationId,
            requestDigest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()));
  }

  private static String receiptJson(
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifact,
      VersionStateSnapshot version,
      LaunchDescriptor descriptor,
      FreshTenantCreationEvidence creation,
      RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal policySeal,
      TestPki pki)
      throws Exception {
    return """
        {"schema":"firemud.run-owned-authored-world-source-receipt.v1","runId":"%s","composeProjectName":"%s","fixtureOperationId":"%s","ownerService":"game-design-service","gameSessionUriSan":"%s","gameSessionLeafSha256":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","sourceEvidence":{"schemaVersion":1,"targetNamespace":"dev","registrationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","tenantSlug":"%s","worldSlug":"%s","worldDisplayName":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"launchReadEvidence":{"schema":"firemud.run-owned-v1-launch-read-evidence.v1","gameTemplateId":17,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s","launchDescriptorBase64":"%s"},"creationEvidence":{"schemaVersion":%d,"targetNamespace":"%s","creationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"fixturePolicySeal":{"schema":"%s","ownerService":"%s","operationId":"%s","requestDigest":"%s","evidenceDigest":"%s"}}\
        """
        .formatted(
            RUN_ID,
            PROJECT,
            source.registrationRequestId(),
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
            policySeal.schema(),
            policySeal.ownerService(),
            policySeal.operationId(),
            policySeal.requestDigest(),
            policySeal.evidenceDigest());
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String fingerprint(X509Certificate certificate) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static void writeReadonly(Path path, byte[] value) throws IOException {
    if (Files.exists(path)) {
      Files.setPosixFilePermissions(
          path,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.GROUP_READ));
    }
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
      TestIdentity gameSessionClient,
      TestIdentity wrongNamespaceGameSessionClient,
      TestIdentity wrongGameSessionLeafClient,
      TestIdentity wrongCaGameSessionClient) {
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
      Path wrongCaStore = directory.resolve("world-mtls-untrusted-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=Untrusted World mTLS test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          wrongCaStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path wrongCa = directory.resolve("world-mtls-untrusted-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          wrongCaStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          wrongCa.toString(),
          "-rfc");
      return new TestPki(
          caCertificate,
          ca,
          issueIdentity(
              directory, caStore, ca, "world-management", WORLD_URI, "serverAuth,clientAuth"),
          issueIdentity(directory, caStore, ca, "game-design", GDS_URI, "serverAuth"),
          issueIdentity(directory, caStore, ca, "game-session", GS_URI, "clientAuth"),
          issueIdentity(directory, caStore, ca, "wrong-game-session", WRONG_GS_URI, "clientAuth"),
          issueIdentity(directory, caStore, ca, "wrong-game-session-leaf", GS_URI, "clientAuth"),
          issueIdentity(
              directory, wrongCaStore, wrongCa, "wrong-ca-game-session", GS_URI, "clientAuth"));
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
