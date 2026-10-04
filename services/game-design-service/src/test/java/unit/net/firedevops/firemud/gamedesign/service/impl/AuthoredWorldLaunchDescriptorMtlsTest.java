package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
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
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
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
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Physical socket proof for authored-source-bound launch descriptor peer authorization. The
 * descriptor producer is mocked; this does not exercise owner persistence or runtime activation.
 */
class AuthoredWorldLaunchDescriptorMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String WORLD_MANAGEMENT_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String RESOLVE_METHOD =
      "gamedesign.v1.GameDesignService/ResolveLaunchDescriptor";
  private static final String READ_METHOD = "gamedesign.v1.GameDesignService/GetLaunchDescriptor";
  private static final String COMPLETE_READ_METHOD =
      "gamedesign.v1.GameDesignService/GetCompleteLaunchBinding";
  private static final Set<String> DESCRIPTOR_METHODS =
      Set.of(RESOLVE_METHOD, READ_METHOD, COMPLETE_READ_METHOD);
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final UUID TENANT_ID = UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static final AuthoredWorldLaunchDescriptorEvidence.Request BOUND_REQUEST =
      new AuthoredWorldLaunchDescriptorEvidence.Request(
          NAMESPACE,
          "launch-request-17",
          TENANT_ID,
          "harbor-world",
          SOURCE_OPERATION_ID,
          "sha256:" + "a".repeat(64),
          17L,
          true,
          "requested-patch-2",
          true,
          9L,
          true,
          12L,
          true,
          "{\"requested\":true}");
  private static final AuthoredWorldLaunchDescriptorEvidence BOUND_EVIDENCE =
      AuthoredWorldLaunchDescriptorEvidence.create(
          BOUND_REQUEST,
          "launch-descriptor-17",
          12L,
          true,
          "resolved-patch-2",
          "{\"runtime\":true}",
          "generation-revision-3",
          6L,
          29L,
          "release-bundle:" + TENANT_ID + ":12:29",
          true,
          "remap-set-4");
  private static final AuthoredWorldReleaseAttestationEvidence RELEASE_ATTESTATION =
      releaseAttestation(BOUND_EVIDENCE);
  private static final AuthoredWorldLaunchDescriptorEvidence SWAPPED_DESCRIPTOR =
      swappedDescriptor();
  private static final AuthoredWorldReleaseAttestationEvidence SWAPPED_RELEASE_ATTESTATION =
      releaseAttestation(SWAPPED_DESCRIPTOR);
  private static TestPki pki;

  private LaunchDescriptorService launchDescriptorService;
  private CompleteLaunchBindingService completeLaunchBindingService;
  private Server server;
  private AtomicReference<ResponseMutation> responseMutation;

  @BeforeAll
  static void generateEphemeralPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Authored Launch Test CA, O=FireMUD Test");
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
                "wrong-game-design-server-workload",
                "spiffe://firemud/ns/test/sa/world-management-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-game-design-server-namespace",
                "spiffe://firemud/ns/other-test/sa/game-design-service",
                true),
            issueLeaf(
                caName, caKeyPair.getPrivate(), "game-session-service", GAME_SESSION_URI, false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-service",
                WORLD_MANAGEMENT_URI,
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
                "cross-namespace-game-session-service",
                "spiffe://firemud/ns/other-test/sa/game-session-service",
                false),
            issueLeaf(caName, caKeyPair.getPrivate(), "shared-workload-certificate", null, false));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    launchDescriptorService = Mockito.mock(LaunchDescriptorService.class);
    completeLaunchBindingService = Mockito.mock(CompleteLaunchBindingService.class);
    responseMutation = new AtomicReference<>(ResponseMutation.NONE);
    server = startReceiver(pki.serverCertificate());
  }

  private Server startReceiver(TestCertificate serverCertificate) throws Exception {
    return startReceiver(serverCertificate, null);
  }

  private Server startReceiver(
      TestCertificate serverCertificate, InboundRpcCapture inboundRpcCapture) throws Exception {
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            Mockito.mock(VersionService.class),
            launchDescriptorService,
            completeLaunchBindingService,
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);

    Set<String> unauthenticatedMethods = publicMethods("application.yml");
    ServerInterceptor[] interceptors =
        inboundRpcCapture == null
            ? new ServerInterceptor[] {
              responseMutationInterceptor(responseMutation),
              new AuthTokenInterceptor(
                  new JwtUtil("test-secret-key-test-secret-key-32-bytes", 60_000),
                  unauthenticatedMethods),
              new GrpcPeerIdentityInterceptor()
            }
            : new ServerInterceptor[] {
              inboundRpcCapture,
              responseMutationInterceptor(responseMutation),
              new AuthTokenInterceptor(
                  new JwtUtil("test-secret-key-test-secret-key-32-bytes", 60_000),
                  unauthenticatedMethods),
              new GrpcPeerIdentityInterceptor()
            };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        serverCertificate.privateKey(), serverCertificate.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, interceptors))
        .build()
        .start();
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      stopServer(server);
      server = null;
    }
  }

  @Test
  void exactGameSessionPeerResolvesAndEchoesTheFullCanonicalDescriptor() throws Exception {
    when(launchDescriptorService.resolveLaunchDescriptor(any())).thenReturn(resolvedDescriptor());
    ManagedChannel channel = channel(pki.gameSessionCertificate(), false);
    try {
      ResolveLaunchDescriptorResponse response =
          stub(channel).resolveLaunchDescriptor(resolveRequest());

      assertThat(response.getError().getCode()).isEmpty();
      assertThat(response.getLaunchDescriptor()).isEqualTo(expectedProtoDescriptor());
      ArgumentCaptor<AuthoredWorldLaunchDescriptorEvidence.Request> requestCaptor =
          ArgumentCaptor.forClass(AuthoredWorldLaunchDescriptorEvidence.Request.class);
      verify(launchDescriptorService).resolveLaunchDescriptor(requestCaptor.capture());
      assertThat(requestCaptor.getValue()).isEqualTo(BOUND_REQUEST);
    } finally {
      stopChannel(channel);
    }
  }

  @Test
  void gameSessionAndWorldPeersReadExactDescriptorAndEchoReadRequestIdentity() throws Exception {
    when(launchDescriptorService.getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(resolvedDescriptor());

    for (TestCertificate certificate :
        List.of(pki.gameSessionCertificate(), pki.worldManagementCertificate())) {
      ManagedChannel channel = channel(certificate, false);
      try {
        GetLaunchDescriptorResponse response = stub(channel).getLaunchDescriptor(readRequest());

        assertThat(response.getError().getCode()).isEmpty();
        assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
        assertThat(response.getLaunchDescriptor()).isEqualTo(expectedProtoDescriptor());
      } finally {
        stopChannel(channel);
      }
    }

    verify(launchDescriptorService, Mockito.times(2))
        .getLaunchDescriptor(
            READ_REQUEST_ID,
            TENANT_ID,
            BOUND_REQUEST.worldSlug(),
            BOUND_REQUEST.controlPlaneRequestId(),
            BOUND_EVIDENCE.requestDigest(),
            BOUND_EVIDENCE.resultDigest());
  }

  @Test
  void gameSessionAndWorldPeersReadExactCompleteBindingAndEchoReadRequestIdentity()
      throws Exception {
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(completeBinding());

    for (TestCertificate certificate :
        List.of(pki.gameSessionCertificate(), pki.worldManagementCertificate())) {
      ManagedChannel channel = channel(certificate, false);
      try {
        GetCompleteLaunchBindingResponse response =
            stub(channel).getCompleteLaunchBinding(readRequest());

        assertThat(response.getError().getCode()).isEmpty();
        assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
        assertThat(response.hasLaunchDescriptor()).isTrue();
        assertThat(response.getLaunchDescriptor()).isEqualTo(expectedProtoDescriptor());
        assertThat(response.hasReleaseAttestation()).isTrue();
        assertThat(response.getReleaseAttestation().getEvidenceDigest())
            .isEqualTo(RELEASE_ATTESTATION.evidenceDigest());
        assertThat(response.getReleaseAttestation().getDescriptorResultDigest())
            .isEqualTo(BOUND_EVIDENCE.resultDigest());
      } finally {
        stopChannel(channel);
      }
    }

    verify(completeLaunchBindingService, Mockito.times(2))
        .getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_ID,
            BOUND_REQUEST.worldSlug(),
            BOUND_REQUEST.controlPlaneRequestId(),
            BOUND_EVIDENCE.requestDigest(),
            BOUND_EVIDENCE.resultDigest());
  }

  @Test
  void sharedClientResolvesAndReadsExactDescriptorOverPhysicalMtlsSocket(@TempDir Path directory)
      throws Exception {
    when(launchDescriptorService.resolveLaunchDescriptor(any())).thenReturn(resolvedDescriptor());
    when(launchDescriptorService.getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(resolvedDescriptor());
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(completeBinding());

    AuthoredWorldLaunchDescriptorClient client = newSharedClient(directory, server);
    try {
      client.init();

      AuthoredWorldLaunchDescriptorEvidence resolved = client.resolve(BOUND_REQUEST);
      assertThat(resolved).isEqualTo(BOUND_EVIDENCE);

      AuthoredWorldLaunchDescriptorEvidence readBack = client.get(readClientRequest());
      assertThat(readBack).isEqualTo(BOUND_EVIDENCE);

      var completeReadBack = client.getComplete(readRequest());
      assertThat(completeReadBack.descriptor()).isEqualTo(BOUND_EVIDENCE);
      assertThat(completeReadBack.releaseAttestation()).isEqualTo(RELEASE_ATTESTATION);

      ArgumentCaptor<AuthoredWorldLaunchDescriptorEvidence.Request> requestCaptor =
          ArgumentCaptor.forClass(AuthoredWorldLaunchDescriptorEvidence.Request.class);
      verify(launchDescriptorService).resolveLaunchDescriptor(requestCaptor.capture());
      assertThat(requestCaptor.getValue()).isEqualTo(BOUND_REQUEST);
      verify(launchDescriptorService)
          .getLaunchDescriptor(
              READ_REQUEST_ID,
              TENANT_ID,
              BOUND_REQUEST.worldSlug(),
              BOUND_REQUEST.controlPlaneRequestId(),
              BOUND_EVIDENCE.requestDigest(),
              BOUND_EVIDENCE.resultDigest());
      verify(completeLaunchBindingService)
          .getCompleteLaunchBinding(
              READ_REQUEST_ID,
              TENANT_ID,
              BOUND_REQUEST.worldSlug(),
              BOUND_REQUEST.controlPlaneRequestId(),
              BOUND_EVIDENCE.requestDigest(),
              BOUND_EVIDENCE.resultDigest());
    } finally {
      client.close();
    }
  }

  @Test
  void sharedClientRejectsWrongGameDesignServerWorkloadAndNamespaceBeforeReturningEvidence(
      @TempDir Path directory) throws Exception {
    when(launchDescriptorService.resolveLaunchDescriptor(any())).thenReturn(resolvedDescriptor());
    when(launchDescriptorService.getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(resolvedDescriptor());

    for (TestCertificate serverCertificate :
        List.of(pki.wrongServerWorkloadCertificate(), pki.wrongServerNamespaceCertificate())) {
      InboundRpcCapture inboundRpcCapture = new InboundRpcCapture();
      Server wrongIdentityServer = startReceiver(serverCertificate, inboundRpcCapture);
      AuthoredWorldLaunchDescriptorClient client =
          newSharedClient(directory.resolve(UUID.randomUUID().toString()), wrongIdentityServer);
      try {
        client.init();
        assertServerPeerRefused(() -> client.resolve(BOUND_REQUEST));
        assertServerPeerRefused(() -> client.get(readClientRequest()));
        assertServerPeerRefused(() -> client.getComplete(readRequest()));
        inboundRpcCapture.assertNoInboundRequests();
      } finally {
        client.close();
        stopServer(wrongIdentityServer);
      }
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void sharedClientCodecRejectsChangedDescriptorReadEchoAndDigestOverPhysicalSocket(
      @TempDir Path directory) throws Exception {
    when(launchDescriptorService.resolveLaunchDescriptor(any())).thenReturn(resolvedDescriptor());
    when(launchDescriptorService.getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(resolvedDescriptor());
    when(completeLaunchBindingService.getCompleteLaunchBinding(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(completeBinding());

    AuthoredWorldLaunchDescriptorClient client = newSharedClient(directory, server);
    try {
      client.init();

      responseMutation.set(ResponseMutation.CHANGE_DESCRIPTOR_DUPLICATE);
      assertClientCodecRejected(
          () -> client.resolve(BOUND_REQUEST), "Flat launch descriptor launchDescriptorId");

      responseMutation.set(ResponseMutation.CHANGE_READ_REQUEST_ID);
      assertClientCodecRejected(
          () -> client.get(readClientRequest()), "read request ID echo changed");

      responseMutation.set(ResponseMutation.CHANGE_RESULT_DIGEST);
      assertClientCodecRejected(
          () -> client.get(readClientRequest()), "Authored-world launch evidence is invalid");

      responseMutation.set(ResponseMutation.CHANGE_COMPLETE_READ_REQUEST_ID);
      assertCompleteClientCodecRejected(() -> client.getComplete(readRequest()));

      responseMutation.set(ResponseMutation.SWAP_COMPLETE_RELEASE_ATTESTATION);
      assertCompleteClientCodecRejected(() -> client.getComplete(readRequest()));

      responseMutation.set(ResponseMutation.MISSING_COMPLETE_RELEASE_ATTESTATION);
      assertCompleteClientCodecRejected(() -> client.getComplete(readRequest()));

      responseMutation.set(ResponseMutation.MISSING_COMPLETE_DESCRIPTOR);
      assertCompleteClientCodecRejected(() -> client.getComplete(readRequest()));
    } finally {
      client.close();
    }
  }

  @Test
  void worldPeerCannotResolveBeforeDescriptorProducerAccess() throws Exception {
    ManagedChannel channel = channel(pki.worldManagementCertificate(), false);
    try {
      ResolveLaunchDescriptorResponse response =
          stub(channel).resolveLaunchDescriptor(resolveRequest());
      assertThat(response.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void missingClientCertificateFailsWithTlsHandshakeEvidenceBeforeProducerAccess()
      throws Exception {
    ManagedChannel channel = channel(null, false);
    try {
      var stub = stub(channel);
      assertTlsDenied(() -> stub.resolveLaunchDescriptor(resolveRequest()), RESOLVE_METHOD);
      assertTlsDenied(() -> stub.getLaunchDescriptor(readRequest()), READ_METHOD);
      assertTlsDenied(() -> stub.getCompleteLaunchBinding(readRequest()), COMPLETE_READ_METHOD);
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void jwtOnlyCallerWithoutClientCertificateFailsWithTlsHandshakeEvidence() throws Exception {
    ManagedChannel channel = channel(null, true);
    try {
      var stub = stub(channel);
      assertTlsDenied(() -> stub.resolveLaunchDescriptor(resolveRequest()), RESOLVE_METHOD);
      assertTlsDenied(() -> stub.getLaunchDescriptor(readRequest()), READ_METHOD);
      assertTlsDenied(() -> stub.getCompleteLaunchBinding(readRequest()), COMPLETE_READ_METHOD);
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void wrongServiceCertificateCannotResolveOrReadEvenWithBearerMetadata() throws Exception {
    assertWrongPeerDenied(pki.wrongServiceCertificate());
  }

  @Test
  void wrongNamespaceCertificateCannotResolveOrReadEvenWithBearerMetadata() throws Exception {
    assertWrongPeerDenied(pki.wrongNamespaceCertificate());
  }

  @Test
  void sharedCertificateWithoutWorkloadUriCannotResolveOrRead() throws Exception {
    assertWrongPeerDenied(pki.noWorkloadUriCertificate());
  }

  @Test
  void malformedAndUnknownClosedRequestsFailBeforeDescriptorProducerAccess() throws Exception {
    ManagedChannel channel = channel(pki.gameSessionCertificate(), false);
    ResolveLaunchDescriptorRequest malformed =
        resolveRequest().toBuilder().setCanonicalTenantId("not-a-canonical-uuid").build();
    ResolveLaunchDescriptorRequest unknownField =
        resolveRequest().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    GetLaunchDescriptorRequest malformedCompleteRequest =
        readRequest().toBuilder().setCanonicalTenantId("not-a-canonical-uuid").build();
    GetLaunchDescriptorRequest unknownCompleteRequest =
        readRequest().toBuilder().setUnknownFields(unknownField.getUnknownFields()).build();
    try {
      assertThat(stub(channel).resolveLaunchDescriptor(malformed).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
      assertThat(stub(channel).resolveLaunchDescriptor(unknownField).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
      assertThat(
              stub(channel).getCompleteLaunchBinding(malformedCompleteRequest).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
      assertThat(
              stub(channel).getCompleteLaunchBinding(unknownCompleteRequest).getError().getCode())
          .isEqualTo("INVALID_ARGUMENT");
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  @Test
  void bothProductionProfilesKeepOnlyExactLaunchDescriptorMethodsInGameDesignJwtBypass()
      throws Exception {
    for (String profile : List.of("application.yml", "application-prod.yml")) {
      Set<String> configuredMethods = publicMethods(profile);
      Set<String> gameDesignMethods =
          configuredMethods.stream()
              .filter(method -> method.startsWith("gamedesign.v1.GameDesignService/"))
              .collect(java.util.stream.Collectors.toSet());
      assertThat(gameDesignMethods).containsExactlyInAnyOrderElementsOf(DESCRIPTOR_METHODS);
    }
  }

  private void assertWrongPeerDenied(TestCertificate certificate) throws Exception {
    ManagedChannel channel = channel(certificate, true);
    try {
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub = stub(channel);
      assertThat(stub.resolveLaunchDescriptor(resolveRequest()).getError().getCode())
          .isEqualTo("PERMISSION_DENIED");
      assertThat(stub.getLaunchDescriptor(readRequest()).getError().getCode())
          .isEqualTo("PERMISSION_DENIED");
      assertThat(stub.getCompleteLaunchBinding(readRequest()).getError().getCode())
          .isEqualTo("PERMISSION_DENIED");
    } finally {
      stopChannel(channel);
    }
    verifyNoInteractions(launchDescriptorService);
    verifyNoInteractions(completeLaunchBindingService);
  }

  private static ServerInterceptor responseMutationInterceptor(
      AtomicReference<ResponseMutation> pendingMutation) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> forwardingCall =
            new SimpleForwardingServerCall<>(call) {
              @Override
              public void sendMessage(RespT message) {
                super.sendMessage(
                    mutateResponse(message, pendingMutation.getAndSet(ResponseMutation.NONE)));
              }
            };
        return next.startCall(forwardingCall, headers);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <Response> Response mutateResponse(Response response, ResponseMutation mutation) {
    if (mutation == ResponseMutation.NONE) {
      return response;
    }
    if (mutation == ResponseMutation.CHANGE_DESCRIPTOR_DUPLICATE
        && response instanceof ResolveLaunchDescriptorResponse resolve) {
      var changedDescriptor =
          resolve.getLaunchDescriptor().toBuilder()
              .setLaunchDescriptorId("changed-flat-launch-descriptor-id")
              .build();
      return (Response) resolve.toBuilder().setLaunchDescriptor(changedDescriptor).build();
    }
    if (mutation == ResponseMutation.CHANGE_READ_REQUEST_ID
        && response instanceof GetLaunchDescriptorResponse read) {
      return (Response)
          read.toBuilder().setRequestId("44444444-4444-4444-8444-444444444444").build();
    }
    if (mutation == ResponseMutation.CHANGE_RESULT_DIGEST
        && response instanceof GetLaunchDescriptorResponse read) {
      var descriptor = read.getLaunchDescriptor();
      var changedBinding =
          descriptor.getAuthoredWorldBinding().toBuilder()
              .setResultDigest("sha256:" + "f".repeat(64))
              .build();
      var changedDescriptor =
          descriptor.toBuilder().setAuthoredWorldBinding(changedBinding).build();
      return (Response) read.toBuilder().setLaunchDescriptor(changedDescriptor).build();
    }
    if (mutation == ResponseMutation.CHANGE_COMPLETE_READ_REQUEST_ID
        && response instanceof GetCompleteLaunchBindingResponse read) {
      return (Response)
          read.toBuilder().setRequestId("44444444-4444-4444-8444-444444444444").build();
    }
    if (mutation == ResponseMutation.SWAP_COMPLETE_RELEASE_ATTESTATION
        && response instanceof GetCompleteLaunchBindingResponse read) {
      var swappedEvidence =
          AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(SWAPPED_RELEASE_ATTESTATION);
      return (Response) read.toBuilder().setReleaseAttestation(swappedEvidence).build();
    }
    if (mutation == ResponseMutation.MISSING_COMPLETE_RELEASE_ATTESTATION
        && response instanceof GetCompleteLaunchBindingResponse read) {
      return (Response) read.toBuilder().clearReleaseAttestation().build();
    }
    if (mutation == ResponseMutation.MISSING_COMPLETE_DESCRIPTOR
        && response instanceof GetCompleteLaunchBindingResponse read) {
      return (Response) read.toBuilder().clearLaunchDescriptor().build();
    }
    throw new IllegalStateException("Response mutation does not match the physical RPC response");
  }

  private static void assertServerPeerRefused(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  private static final class InboundRpcCapture implements ServerInterceptor {
    private final AtomicInteger rpcCount = new AtomicInteger();
    private final AtomicInteger requestMessageCount = new AtomicInteger();
    private final AtomicReference<String> methodName = new AtomicReference<>();
    private final AtomicReference<Metadata> requestMetadata = new AtomicReference<>();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      rpcCount.incrementAndGet();
      methodName.set(call.getMethodDescriptor().getFullMethodName());
      requestMetadata.set(headers);
      ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
      return new SimpleForwardingServerCallListener<>(listener) {
        @Override
        public void onMessage(ReqT message) {
          requestMessageCount.incrementAndGet();
          super.onMessage(message);
        }
      };
    }

    private void assertNoInboundRequests() {
      assertThat(rpcCount.get()).isZero();
      assertThat(requestMessageCount.get()).isZero();
      assertThat(methodName.get()).isNull();
      assertThat(requestMetadata.get()).isNull();
    }
  }

  private static void assertClientCodecRejected(Runnable call, String expectedCauseMessage) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure.getCause())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(expectedCauseMessage);
  }

  private static void assertCompleteClientCodecRejected(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure.getCause()).isInstanceOf(IllegalArgumentException.class);
  }

  private static AuthoredWorldLaunchDescriptorClient newSharedClient(
      Path directory, Server receiver) throws Exception {
    Files.createDirectories(directory);
    Path caCertificate =
        writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    Path clientCertificate =
        writePem(
            directory.resolve("game-session-service.crt"),
            "CERTIFICATE",
            pki.gameSessionCertificate().certificate().getEncoded());
    Path clientPrivateKey =
        writePem(
            directory.resolve("game-session-service.key"),
            "PRIVATE KEY",
            pki.gameSessionCertificate().privateKey().getEncoded());

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + receiver.getPort());
    return new AuthoredWorldLaunchDescriptorClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private static Path writePem(Path path, String label, byte[] encoded) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private static void stopServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static void assertTlsDenied(Runnable call, String method) {
    Throwable failure = catchFailure(call);
    assertThat(failure)
        .as(
            "%s must fail with a client-certificate handshake rejection: %s",
            method, diagnostics(failure))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode())
        .as("%s must be a TLS transport rejection, not a timeout: %s", method, diagnostics(failure))
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
        .as("%s cause chain must identify certificate rejection: %s", method, diagnostics(failure))
        .isTrue();
  }

  private static Set<String> publicMethods(String resourceName) throws IOException {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    for (var propertySource :
        new YamlPropertySourceLoader()
            .load(resourceName, new FileSystemResource("src/main/resources/" + resourceName))) {
      environment.getPropertySources().addFirst(propertySource);
    }
    return Set.copyOf(
        Binder.get(environment)
            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
            .orElse(List.of()));
  }

  private static ResolveLaunchDescriptorRequest resolveRequest() {
    return ResolveLaunchDescriptorRequest.newBuilder()
        .setCanonicalTenantId(BOUND_REQUEST.canonicalTenantId().toString())
        .setGameTemplateId(BOUND_REQUEST.gameTemplateId())
        .setControlPlaneRequestId(BOUND_REQUEST.controlPlaneRequestId())
        .setRequestedScriptPatchVersion(BOUND_REQUEST.requestedScriptPatchVersion())
        .setSourceVersionId(BOUND_REQUEST.sourceVersionId())
        .setTargetVersionId(BOUND_REQUEST.targetVersionId())
        .setRequestedRuntimeFlagsJson(BOUND_REQUEST.requestedRuntimeFlagsJson())
        .setWorldSlug(BOUND_REQUEST.worldSlug())
        .setAuthoredWorldSourceOperationId(
            BOUND_REQUEST.authoredWorldSourceOperationId().toString())
        .setExpectedAuthoredWorldSourceEvidenceDigest(
            BOUND_REQUEST.authoredWorldSourceEvidenceDigest())
        .build();
  }

  private static GetLaunchDescriptorRequest readRequest() {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(READ_REQUEST_ID.toString())
        .setCanonicalTenantId(BOUND_REQUEST.canonicalTenantId().toString())
        .setWorldSlug(BOUND_REQUEST.worldSlug())
        .setControlPlaneRequestId(BOUND_REQUEST.controlPlaneRequestId())
        .setExpectedRequestDigest(BOUND_EVIDENCE.requestDigest())
        .setExpectedResultDigest(BOUND_EVIDENCE.resultDigest())
        .build();
  }

  private static AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest readClientRequest() {
    return new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
        READ_REQUEST_ID, BOUND_REQUEST, BOUND_EVIDENCE.resultDigest());
  }

  private static CompleteLaunchBindingDto completeBinding() {
    return new CompleteLaunchBindingDto(BOUND_EVIDENCE, RELEASE_ATTESTATION);
  }

  private static AuthoredWorldReleaseAttestationEvidence releaseAttestation(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participantKey -> {
                  boolean abilityPresent = "GAME_LOGIC".equals(participantKey);
                  return new AuthoredWorldReleaseAttestationEvidence.Participant(
                      participantKey,
                      Long.toString(descriptor.versionId()),
                      false,
                      null,
                      "commit-17",
                      "b".repeat(64),
                      AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                          participantKey),
                      abilityPresent,
                      abilityPresent ? "sha256:" + "c".repeat(64) : null);
                })
            .toList();
    AuthoredWorldReleaseAttestationEvidence.Artifact artifact =
        new AuthoredWorldReleaseAttestationEvidence.Artifact(
            "world.fixture",
            "WORLD_FIXTURE",
            "artifacts/sha256/" + "d".repeat(64),
            "sha256:" + "d".repeat(64),
            "application/octet-stream",
            1);
    return AuthoredWorldReleaseAttestationEvidence.create(
        descriptor.targetNamespace(),
        descriptor.resultDigest(),
        descriptor.canonicalTenantId(),
        UUID.fromString("d7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21"),
        descriptor.worldSlug(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        "publish-workflow-opaque-17",
        "commit-17",
        participants,
        "sha256:" + "e".repeat(64),
        1,
        List.of("world.fixture"),
        List.of(artifact),
        List.of("LOOK"),
        descriptor.generationConfigRevision());
  }

  private static AuthoredWorldLaunchDescriptorEvidence swappedDescriptor() {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "launch-request-swapped",
            TENANT_ID,
            "other-harbor-world",
            SOURCE_OPERATION_ID,
            "sha256:" + "a".repeat(64),
            17L,
            true,
            "requested-patch-2",
            true,
            9L,
            true,
            12L,
            true,
            "{\"requested\":true}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "launch-descriptor-swapped",
        12L,
        true,
        "resolved-patch-2",
        "{\"runtime\":true}",
        "generation-revision-3",
        6L,
        29L,
        "release-bundle:" + TENANT_ID + ":12:29",
        true,
        "remap-set-4");
  }

  private static ResolvedLaunchDescriptorDto resolvedDescriptor() {
    return new ResolvedLaunchDescriptorDto(
        BOUND_EVIDENCE.launchDescriptorId(),
        BOUND_EVIDENCE.canonicalTenantId().toString(),
        BOUND_EVIDENCE.gameTemplateId(),
        BOUND_EVIDENCE.controlPlaneRequestId(),
        BOUND_EVIDENCE.versionId(),
        BOUND_EVIDENCE.scriptPatchVersion(),
        BOUND_EVIDENCE.runtimeFlagsJson(),
        BOUND_EVIDENCE.generationConfigRevision(),
        BOUND_EVIDENCE.versionStateEpoch(),
        BOUND_EVIDENCE.releaseBundleId(),
        BOUND_EVIDENCE.publishedReleaseBundleRef(),
        BOUND_EVIDENCE.remapSetId(),
        BOUND_EVIDENCE);
  }

  private static net.firedevops.firemud.gamedesign.v1.LaunchDescriptor expectedProtoDescriptor() {
    return net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
        .setLaunchDescriptorId(BOUND_EVIDENCE.launchDescriptorId())
        .setCanonicalTenantId(BOUND_EVIDENCE.canonicalTenantId().toString())
        .setGameTemplateId(BOUND_EVIDENCE.gameTemplateId())
        .setControlPlaneRequestId(BOUND_EVIDENCE.controlPlaneRequestId())
        .setVersionId(BOUND_EVIDENCE.versionId())
        .setScriptPatchVersion(BOUND_EVIDENCE.scriptPatchVersion())
        .setRuntimeFlagsJson(BOUND_EVIDENCE.runtimeFlagsJson())
        .setGenerationConfigRevision(BOUND_EVIDENCE.generationConfigRevision())
        .setVersionStateEpoch(BOUND_EVIDENCE.versionStateEpoch())
        .setReleaseBundleId(BOUND_EVIDENCE.releaseBundleId())
        .setPublishedReleaseBundleRef(BOUND_EVIDENCE.publishedReleaseBundleRef())
        .setRemapSetId(BOUND_EVIDENCE.remapSetId())
        .setAuthoredWorldBinding(expectedProtoEvidence())
        .build();
  }

  private static net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence
      expectedProtoEvidence() {
    var builder =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.newBuilder()
            .setSchemaVersion(BOUND_EVIDENCE.schemaVersion())
            .setTargetNamespace(BOUND_EVIDENCE.targetNamespace())
            .setControlPlaneRequestId(BOUND_EVIDENCE.controlPlaneRequestId())
            .setCanonicalTenantId(BOUND_EVIDENCE.canonicalTenantId().toString())
            .setWorldSlug(BOUND_EVIDENCE.worldSlug())
            .setAuthoredWorldSourceOperationId(
                BOUND_EVIDENCE.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(
                BOUND_EVIDENCE.authoredWorldSourceEvidenceDigest())
            .setGameTemplateId(BOUND_EVIDENCE.gameTemplateId())
            .setRequestDigest(BOUND_EVIDENCE.requestDigest())
            .setLaunchDescriptorId(BOUND_EVIDENCE.launchDescriptorId())
            .setVersionId(BOUND_EVIDENCE.versionId())
            .setRuntimeFlagsJson(BOUND_EVIDENCE.runtimeFlagsJson())
            .setGenerationConfigRevision(BOUND_EVIDENCE.generationConfigRevision())
            .setVersionStateEpoch(BOUND_EVIDENCE.versionStateEpoch())
            .setReleaseBundleId(BOUND_EVIDENCE.releaseBundleId())
            .setPublishedReleaseBundleRef(BOUND_EVIDENCE.publishedReleaseBundleRef())
            .setResultDigest(BOUND_EVIDENCE.resultDigest());
    if (BOUND_EVIDENCE.requestedScriptPatchVersionPresent()) {
      builder.setRequestedScriptPatchVersion(BOUND_EVIDENCE.requestedScriptPatchVersion());
    }
    if (BOUND_EVIDENCE.sourceVersionIdPresent()) {
      builder.setSourceVersionId(BOUND_EVIDENCE.sourceVersionId());
    }
    if (BOUND_EVIDENCE.targetVersionIdPresent()) {
      builder.setTargetVersionId(BOUND_EVIDENCE.targetVersionId());
    }
    if (BOUND_EVIDENCE.requestedRuntimeFlagsJsonPresent()) {
      builder.setRequestedRuntimeFlagsJson(BOUND_EVIDENCE.requestedRuntimeFlagsJson());
    }
    if (BOUND_EVIDENCE.scriptPatchVersionPresent()) {
      builder.setScriptPatchVersion(BOUND_EVIDENCE.scriptPatchVersion());
    }
    if (BOUND_EVIDENCE.remapSetIdPresent()) {
      builder.setRemapSetId(BOUND_EVIDENCE.remapSetId());
    }
    return builder.build();
  }

  private static GameDesignServiceGrpc.GameDesignServiceBlockingStub stub(ManagedChannel channel) {
    return GameDesignServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private ManagedChannel channel(TestCertificate clientCertificate, boolean attachBearer)
      throws Exception {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .sslContext(clientSslContext(clientCertificate));
    if (attachBearer) {
      Metadata headers = new Metadata();
      headers.put(AUTHORIZATION, "Bearer header.payload.signature");
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

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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

  private static TestCertificate issueLeaf(
      X500Name caName,
      PrivateKey caPrivateKey,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
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
            new GeneralNames(subjectAltNames),
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

  private enum ResponseMutation {
    NONE,
    CHANGE_DESCRIPTOR_DUPLICATE,
    CHANGE_READ_REQUEST_ID,
    CHANGE_RESULT_DIGEST,
    CHANGE_COMPLETE_READ_REQUEST_ID,
    SWAP_COMPLETE_RELEASE_ATTESTATION,
    MISSING_COMPLETE_RELEASE_ATTESTATION,
    MISSING_COMPLETE_DESCRIPTOR
  }

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate serverCertificate,
      TestCertificate wrongServerWorkloadCertificate,
      TestCertificate wrongServerNamespaceCertificate,
      TestCertificate gameSessionCertificate,
      TestCertificate worldManagementCertificate,
      TestCertificate wrongServiceCertificate,
      TestCertificate wrongNamespaceCertificate,
      TestCertificate noWorkloadUriCertificate) {}
}
