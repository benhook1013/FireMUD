package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Physical loopback-mTLS proof for the two narrow launch-evidence RPC allowlists.
 *
 * <p>The production gRPC handler, TLS peer interceptor, and JWT interceptor run over ephemeral
 * sockets. The owner service/repository collaborators are deliberately mocked transport fixtures:
 * this proves method identity and exact request forwarding only, not persisted release evidence,
 * provisioning, Entity payload access, or initialization authority.
 */
class CompleteLaunchBindingMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String AUTHORIZATION = "authorization";
  private static final String JWT_SECRET = "test-secret-key-test-secret-key-32-bytes";
  private static final String TEST_ACCOUNT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID VERSION_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String WORLD_SLUG = "silver-march";
  private static final String CONTROL_PLANE_REQUEST_ID = "launch-request-1";
  private static final String SOURCE_EVIDENCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final String MANIFEST_HASH = "sha256:" + "c".repeat(64);
  private static final CompleteLaunchBindingDto TRANSPORT_BINDING = transportOnlyBinding();
  private static final String REQUEST_DIGEST = TRANSPORT_BINDING.descriptor().requestDigest();
  private static final String RESULT_DIGEST = TRANSPORT_BINDING.descriptor().resultDigest();
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
  private static TestPki pki;

  private CompleteLaunchBindingService completeLaunchBindingService;
  private LaunchDescriptorService launchDescriptorService;
  private VersionService versionService;
  private Server server;

  @BeforeAll
  static void generateEphemeralPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Game Design mTLS Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(
            caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null, false);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                "spiffe://firemud/ns/test/sa/game-design-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "entity-management-service",
                "spiffe://firemud/ns/test/sa/entity-management-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-session-service",
                "spiffe://firemud/ns/test/sa/game-session-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-service",
                "spiffe://firemud/ns/test/sa/world-management-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "automation-scripting-service",
                "spiffe://firemud/ns/test/sa/automation-scripting-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "account-service",
                "spiffe://firemud/ns/test/sa/account-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "cross-namespace-entity-management-service",
                "spiffe://firemud/ns/other-test/sa/entity-management-service",
                false));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    completeLaunchBindingService = mock(CompleteLaunchBindingService.class);
    launchDescriptorService = mock(LaunchDescriptorService.class);
    versionService = mock(VersionService.class);
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_UUID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST))
        .thenReturn(TRANSPORT_BINDING);
    when(versionService.getPublishedReleaseBundle("tenant-7", 7L))
        .thenReturn(transportOnlyReleaseBundle());

    GameDesignGrpcService service =
        new GameDesignGrpcService(
            mock(PingService.class),
            mock(RevisionService.class),
            versionService,
            launchDescriptorService,
            completeLaunchBindingService,
            mock(TemplateRemapSetService.class),
            mock(VersionAssetArtifactService.class),
            mock(SettingsAuthorityService.class),
            mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.gameDesignServerCertificate().privateKey(),
                            pki.gameDesignServerCertificate().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    new AuthTokenInterceptor(new JwtUtil(JWT_SECRET, 60_000L), Set.of()),
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void completeLaunchBindingAcceptsOnlyTheExactSameNamespaceOwnerSet() throws Exception {
    for (TestCertificate certificate :
        List.of(
            pki.entityManagementCertificate(),
            pki.gameSessionCertificate(),
            pki.worldManagementCertificate())) {
      ManagedChannel channel = channel(certificate, serviceToken(serviceName(certificate)));
      try {
        GetCompleteLaunchBindingResponse response =
            serviceStub(channel).getCompleteLaunchBinding(exactBindingRequest());
        assertThat(response.getError().getCode()).isEmpty();
        assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
        assertThat(response.hasReleaseAttestation()).isTrue();
      } finally {
        stopChannel(channel);
      }
    }

    verify(completeLaunchBindingService, times(3))
        .getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_UUID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST);
  }

  @Test
  void numericReleaseBundleReadUsesOnlyItsExactSameNamespaceOwnerSet() throws Exception {
    for (TestCertificate certificate :
        List.of(
            pki.gameSessionCertificate(),
            pki.worldManagementCertificate(),
            pki.automationScriptingCertificate())) {
      ManagedChannel channel = channel(certificate, serviceToken(serviceName(certificate)));
      try {
        GetPublishedReleaseBundleResponse response =
            serviceStub(channel)
                .getPublishedReleaseBundle(
                    GetPublishedReleaseBundleRequest.newBuilder()
                        .setTenantId("tenant-7")
                        .setVersionId(7L)
                        .build());
        assertThat(response.getError().getCode()).isEmpty();
        assertThat(response.getBundle().getId()).isEqualTo(11L);
      } finally {
        stopChannel(channel);
      }
    }

    verify(versionService, times(3)).getPublishedReleaseBundle("tenant-7", 7L);
  }

  @Test
  void wrongWorkloadNamespaceAndBearerClaimsCannotSubstituteForPeerAuthorization()
      throws Exception {
    for (TestCertificate certificate :
        List.of(pki.accountServiceCertificate(), pki.crossNamespaceEntityCertificate())) {
      ManagedChannel channel = channel(certificate, serviceToken("account-service"));
      try {
        GetCompleteLaunchBindingResponse completeResponse =
            serviceStub(channel).getCompleteLaunchBinding(exactBindingRequest());
        assertThat(completeResponse.getError().getCode()).isEqualTo("PERMISSION_DENIED");
        GetPublishedReleaseBundleResponse releaseResponse =
            serviceStub(channel)
                .getPublishedReleaseBundle(
                    GetPublishedReleaseBundleRequest.newBuilder()
                        .setTenantId("tenant-7")
                        .setVersionId(7L)
                        .build());
        assertThat(releaseResponse.getError().getCode()).isEqualTo("PERMISSION_DENIED");
      } finally {
        stopChannel(channel);
      }
    }

    ManagedChannel internalClaimChannel =
        channel(pki.accountServiceCertificate(), serviceToken("account-service"));
    try {
      assertThat(
              serviceStub(internalClaimChannel)
                  .getPublishedReleaseBundle(
                      GetPublishedReleaseBundleRequest.newBuilder()
                          .setTenantId("tenant-7")
                          .setVersionId(7L)
                          .build())
                  .getError()
                  .getCode())
          .isEqualTo("PERMISSION_DENIED");
    } finally {
      stopChannel(internalClaimChannel);
    }

    ManagedChannel adminBearerChannel = channel(pki.accountServiceCertificate(), adminToken());
    try {
      assertThat(
              serviceStub(adminBearerChannel)
                  .getPublishedReleaseBundle(
                      GetPublishedReleaseBundleRequest.newBuilder()
                          .setTenantId("tenant-7")
                          .setVersionId(7L)
                          .build())
                  .getError()
                  .getCode())
          .isEqualTo("PERMISSION_DENIED");
    } finally {
      stopChannel(adminBearerChannel);
    }

    verifyNoInteractions(completeLaunchBindingService, versionService);
  }

  @Test
  void missingClientCertificateFailsAtTlsBeforeEitherOwnerRead() throws Exception {
    ManagedChannel channel = channel(null, serviceToken("entity-management-service"));
    try {
      assertTlsDenied(() -> serviceStub(channel).getCompleteLaunchBinding(exactBindingRequest()));
      assertTlsDenied(
          () ->
              serviceStub(channel)
                  .getPublishedReleaseBundle(
                      GetPublishedReleaseBundleRequest.newBuilder()
                          .setTenantId("tenant-7")
                          .setVersionId(7L)
                          .build()));
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(completeLaunchBindingService, versionService);
  }

  @Test
  void completeBindingRejectsMalformedAndSubstitutedRequestEvidenceClosed() throws Exception {
    ManagedChannel channel =
        channel(pki.entityManagementCertificate(), serviceToken("entity-management-service"));
    try {
      GetLaunchDescriptorRequest unknownField =
          exactBindingRequest().toBuilder()
              .setUnknownFields(
                  UnknownFieldSet.newBuilder()
                      .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                      .build())
              .build();
      assertThat(serviceStub(channel).getCompleteLaunchBinding(unknownField).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
      GetLaunchDescriptorRequest nilReadRequestId =
          exactBindingRequest().toBuilder()
              .setRequestId("00000000-0000-0000-0000-000000000000")
              .build();
      assertThat(
              serviceStub(channel).getCompleteLaunchBinding(nilReadRequestId).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
      GetLaunchDescriptorRequest malformedReadRequestId =
          exactBindingRequest().toBuilder().setRequestId("not-a-uuid").build();
      assertThat(
              serviceStub(channel)
                  .getCompleteLaunchBinding(malformedReadRequestId)
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
      GetLaunchDescriptorRequest missingScope =
          exactBindingRequest().toBuilder().setWorldSlug("").build();
      assertThat(serviceStub(channel).getCompleteLaunchBinding(missingScope).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void completeBindingForwardsExactScopeAndDigestForOwnerValidation() throws Exception {
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            any(), any(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              boolean exact =
                  TENANT_UUID.equals(invocation.getArgument(1))
                      && WORLD_SLUG.equals(invocation.getArgument(2))
                      && CONTROL_PLANE_REQUEST_ID.equals(invocation.getArgument(3))
                      && REQUEST_DIGEST.equals(invocation.getArgument(4))
                      && RESULT_DIGEST.equals(invocation.getArgument(5));
              if (!exact) {
                throw new IllegalArgumentException(
                    "complete binding request scope or digest mismatch");
              }
              return TRANSPORT_BINDING;
            });
    ManagedChannel channel =
        channel(pki.entityManagementCertificate(), serviceToken("entity-management-service"));
    List<GetLaunchDescriptorRequest> substitutions =
        List.of(
            exactBindingRequest().toBuilder().setCanonicalTenantId(VERSION_UUID.toString()).build(),
            exactBindingRequest().toBuilder().setWorldSlug("other-world").build(),
            exactBindingRequest().toBuilder().setControlPlaneRequestId("other-request").build(),
            exactBindingRequest().toBuilder()
                .setExpectedRequestDigest("sha256:" + "f".repeat(64))
                .build(),
            exactBindingRequest().toBuilder()
                .setExpectedResultDigest("sha256:" + "e".repeat(64))
                .build());
    try {
      for (GetLaunchDescriptorRequest request : substitutions) {
        GetCompleteLaunchBindingResponse response =
            serviceStub(channel).getCompleteLaunchBinding(request);
        assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.hasLaunchDescriptor()).isFalse();
        assertThat(response.hasReleaseAttestation()).isFalse();
      }
    } finally {
      stopChannel(channel);
    }
    verify(completeLaunchBindingService, times(substitutions.size()))
        .getCompleteLaunchBinding(any(), any(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void completeBindingUsesFreshReadRequestIdAsCorrelationAndEchoesIt() throws Exception {
    UUID freshReadRequestId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            freshReadRequestId,
            TENANT_UUID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST))
        .thenReturn(TRANSPORT_BINDING);

    ManagedChannel channel =
        channel(pki.entityManagementCertificate(), serviceToken("entity-management-service"));
    try {
      GetCompleteLaunchBindingResponse response =
          serviceStub(channel)
              .getCompleteLaunchBinding(
                  exactBindingRequest().toBuilder()
                      .setRequestId(freshReadRequestId.toString())
                      .build());

      assertThat(response.getError().getCode()).isEmpty();
      assertThat(response.getRequestId()).isEqualTo(freshReadRequestId.toString());
      assertThat(response.hasReleaseAttestation()).isTrue();
    } finally {
      stopChannel(channel);
    }

    verify(completeLaunchBindingService)
        .getCompleteLaunchBinding(
            freshReadRequestId,
            TENANT_UUID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST);
  }

  @Test
  void entityPeerRemainsDeniedFromDescriptorOnlyRead() throws Exception {
    ManagedChannel channel =
        channel(pki.entityManagementCertificate(), serviceToken("entity-management-service"));
    try {
      GetLaunchDescriptorResponse response =
          serviceStub(channel)
              .getLaunchDescriptor(
                  GetLaunchDescriptorRequest.newBuilder()
                      .setRequestId(READ_REQUEST_ID.toString())
                      .setCanonicalTenantId(TENANT_UUID.toString())
                      .setWorldSlug(WORLD_SLUG)
                      .setControlPlaneRequestId(CONTROL_PLANE_REQUEST_ID)
                      .setExpectedRequestDigest(REQUEST_DIGEST)
                      .setExpectedResultDigest(RESULT_DIGEST)
                      .build());
      assertThat(response.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
  }

  private ManagedChannel channel(TestCertificate clientCertificate, String jwt) throws Exception {
    var builder =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .sslContext(clientSslContext(clientCertificate));
    if (jwt != null) {
      Metadata headers = new Metadata();
      headers.put(
          Metadata.Key.of(AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER), "Bearer " + jwt);
      builder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    return builder.build();
  }

  private static io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSslContext(
      TestCertificate clientCertificate) throws Exception {
    var builder = GrpcSslContexts.forClient().trustManager(pki.caCertificate());
    if (clientCertificate != null) {
      builder.keyManager(clientCertificate.privateKey(), clientCertificate.certificate());
    }
    return builder.build();
  }

  private static GameDesignServiceGrpc.GameDesignServiceBlockingStub serviceStub(
      ManagedChannel channel) {
    return GameDesignServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private static GetLaunchDescriptorRequest exactBindingRequest() {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(READ_REQUEST_ID.toString())
        .setCanonicalTenantId(TENANT_UUID.toString())
        .setWorldSlug(WORLD_SLUG)
        .setControlPlaneRequestId(CONTROL_PLANE_REQUEST_ID)
        .setExpectedRequestDigest(REQUEST_DIGEST)
        .setExpectedResultDigest(RESULT_DIGEST)
        .build();
  }

  private static CompleteLaunchBindingDto transportOnlyBinding() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            CONTROL_PLANE_REQUEST_ID,
            TENANT_UUID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_EVIDENCE_DIGEST,
            9L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-1",
            7L,
            false,
            null,
            "{}",
            "generation-1",
            11L,
            11L,
            "release-bundle-1",
            false,
            null);
    String appliedCommitId = "commit-1";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                key ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        key,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        appliedCommitId,
                        "d".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            key),
                        "GAME_LOGIC".equals(key),
                        "GAME_LOGIC".equals(key) ? "sha256:" + "e".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            VERSION_UUID,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "workflow-1",
            appliedCommitId,
            participants,
            MANIFEST_HASH,
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingDto(descriptor, release);
  }

  private static PublishedReleaseBundleDto transportOnlyReleaseBundle() {
    return new PublishedReleaseBundleDto(
        11L,
        "tenant-7",
        7L,
        1,
        "v1",
        "workflow-1",
        MANIFEST_HASH,
        List.of("manifest.json"),
        List.of(),
        List.of(),
        "generation-1",
        false,
        null,
        LocalDateTime.parse("2026-04-14T12:00:00"),
        TENANT_UUID,
        VERSION_UUID,
        "release-bundle-1",
        1,
        List.of());
  }

  private static String serviceName(TestCertificate certificate) {
    if (certificate == pki.entityManagementCertificate()) return "entity-management-service";
    if (certificate == pki.gameSessionCertificate()) return "game-session-service";
    if (certificate == pki.worldManagementCertificate()) return "world-management-service";
    if (certificate == pki.automationScriptingCertificate()) return "automation-scripting-service";
    return "account-service";
  }

  private static String serviceToken(String serviceName) {
    return new JwtUtil(JWT_SECRET, 60_000L)
        .generateToken(
            "service:" + serviceName,
            Map.of(
                "globalRoles",
                List.of(),
                "scopedRoles",
                Map.of(),
                "internalService",
                true,
                "serviceName",
                serviceName,
                "serviceInstanceId",
                "test-instance"));
  }

  private static String adminToken() {
    return new JwtUtil(JWT_SECRET, 60_000L)
        .generateToken(
            TEST_ACCOUNT_ID,
            Map.of(
                "accountId",
                TEST_ACCOUNT_ID,
                "globalRoles",
                List.of("platformAdmin"),
                "scopedRoles",
                Map.of(),
                "internalService",
                false));
  }

  private static void assertTlsDenied(Runnable call) {
    Throwable failure;
    try {
      call.run();
      failure = null;
    } catch (Throwable thrown) {
      failure = thrown;
    }
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue();
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static TestCertificate issueLeaf(
      X500Name caName,
      PrivateKey caPrivateKey,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralName[] names =
        new GeneralName[] {
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
            new GeneralNames(names),
            serverCertificate);
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames,
      boolean serverCertificate)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60L);
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
              serverCertificate ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
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

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate gameDesignServerCertificate,
      TestCertificate entityManagementCertificate,
      TestCertificate gameSessionCertificate,
      TestCertificate worldManagementCertificate,
      TestCertificate automationScriptingCertificate,
      TestCertificate accountServiceCertificate,
      TestCertificate crossNamespaceEntityCertificate) {}
}
