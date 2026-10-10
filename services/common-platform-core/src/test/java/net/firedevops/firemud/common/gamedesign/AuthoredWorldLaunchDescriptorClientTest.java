package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthoredWorldLaunchDescriptorClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");

  @Test
  void canonicalDescriptorRejectsRetainedLegacyTenantSelector() {
    var response =
        net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(
                net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                    .setTenantId("legacy-tenant"))
            .build();

    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request(NAMESPACE), response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("legacy tenant selector");
  }

  @Test
  void completeBindingResponseRoundTripsTheExactDescriptorAndAttestationPair() {
    AuthoredWorldLaunchDescriptorEvidence.Request sourceRequest = request(NAMESPACE);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            sourceRequest,
            "launch-descriptor-7",
            7L,
            false,
            null,
            "{}",
            "generation-revision-1",
            3L,
            11L,
            "release-ref-11",
            false,
            null);
    String commitId = "commit-7";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participantKey ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participantKey,
                        "7",
                        false,
                        null,
                        commitId,
                        "d".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participantKey),
                        "GAME_LOGIC".equals(participantKey),
                        "GAME_LOGIC".equals(participantKey) ? "sha256:" + "e".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence releaseAttestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT_ID,
            UUID.fromString("82345678-1234-4234-8234-123456789abc"),
            sourceRequest.worldSlug(),
            SOURCE_OPERATION_ID,
            sourceRequest.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-workflow-7",
            commitId,
            participants,
            "sha256:" + "c".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    CompleteLaunchBindingEvidence evidence =
        new CompleteLaunchBindingEvidence(descriptor, releaseAttestation);
    GetLaunchDescriptorRequest readRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_REQUEST_ID.toString())
            .setCanonicalTenantId(TENANT_ID.toString())
            .setWorldSlug(sourceRequest.worldSlug())
            .setControlPlaneRequestId(sourceRequest.controlPlaneRequestId())
            .setExpectedRequestDigest(descriptor.requestDigest())
            .setExpectedResultDigest(descriptor.resultDigest())
            .build();

    var response = AuthoredWorldLaunchDescriptorGrpcCodec.toCompleteResponse(readRequest, evidence);

    org.assertj.core.api.Assertions.assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(readRequest, response))
        .isEqualTo(evidence);
  }

  @Test
  void rejectsPlaintextMissingClasspathAndUnreadableTlsMaterialBeforeChannelCreation(
      @TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    CommonGrpcClientProperties plaintext = fileBackedMtls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    assertThatThrownBy(() -> newClient(new CommonGrpcClientProperties(), channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties classpath = fileBackedMtls(directory);
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(() -> newClient(classpath, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(directory.resolve("missing-client.crt").toString());
    missingFiles.setPrivateKey(directory.resolve("missing-client.key").toString());
    missingFiles.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missingFiles, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void deniesResolveAndReadUntilExplicitInitializationAndAfterClose(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AuthoredWorldLaunchDescriptorClient client =
        newClient(fileBackedMtls(directory), channelFactory);
    var request = request(NAMESPACE);
    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, "sha256:" + "b".repeat(64));
    var completeRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_REQUEST_ID.toString())
            .setCanonicalTenantId(TENANT_ID.toString())
            .setWorldSlug("copper-coast")
            .setControlPlaneRequestId("launch-operation-7")
            .setExpectedRequestDigest("sha256:" + "a".repeat(64))
            .setExpectedResultDigest("sha256:" + "b".repeat(64))
            .build();

    assertThatThrownBy(() -> client.resolve(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.get(getRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.getComplete(completeRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    verifyNoInteractions(channelFactory);

    client.close();
    assertThatThrownBy(() -> client.resolve(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initializesConfiguredTargetWhenFileBackedTlsFilesExist(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    CommonGrpcClientProperties tls = fileBackedMtls(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    AuthoredWorldLaunchDescriptorClient client =
        new AuthoredWorldLaunchDescriptorClient(endpoints, tls, channelFactory, NAMESPACE);

    try {
      // The mocked channel factory covers explicit target wiring only; socket peer proof is
      // separate.
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsARequestOutsideTheConfiguredNamespace(@TempDir Path directory) throws Exception {
    AuthoredWorldLaunchDescriptorClient client =
        newClient(fileBackedMtls(directory), mock(GrpcChannelFactory.class));
    try {
      assertThatThrownBy(() -> client.resolve(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
    } finally {
      client.close();
    }
  }

  private static AuthoredWorldLaunchDescriptorClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new AuthoredWorldLaunchDescriptorClient(
        new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request(String namespace) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        namespace,
        "launch-operation-7",
        TENANT_ID,
        "copper-coast",
        SOURCE_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        19L,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }
}
