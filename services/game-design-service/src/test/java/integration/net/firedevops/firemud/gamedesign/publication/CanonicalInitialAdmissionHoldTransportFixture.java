package net.firedevops.firemud.accountservice.service.session;

import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS transport for the native original-admission integration proof. */
final class CanonicalInitialAdmissionHoldTransportFixture implements AutoCloseable {
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;
  private static final long DEADLINE_SECONDS = 5L;

  private final ManagedChannel channel;
  private final WorldCanonicalInitialAdmissionHoldServiceGrpc
          .WorldCanonicalInitialAdmissionHoldServiceBlockingStub
      stub;
  private final String workloadNamespace;
  private boolean closed;

  CanonicalInitialAdmissionHoldTransportFixture(
      String endpoint,
      int port,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace)
      throws SSLException {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("World Management endpoint is required");
    }
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("World Management port must be valid");
    }
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = namespace;
    this.channel =
        Objects.requireNonNull(channels, "gRPC channel factory required")
            .buildChannel(endpoint, port, requireFileBackedMutualTls(tls), false);
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    this.stub =
        WorldCanonicalInitialAdmissionHoldServiceGrpc.newBlockingStub(channel)
            .withMaxInboundMessageSize(MAX_RESPONSE_BYTES)
            .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
            .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri));
  }

  HoldIdentity acquire(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleSelector) {
    if (closed) {
      throw new IllegalStateException("World initial-admission hold transport is closed");
    }
    Objects.requireNonNull(request, "complete World initial-admission hold request required");
    Objects.requireNonNull(lifecycleSelector, "complete World lifecycle read selector required");
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "World initial-admission hold transport requires a workload-only caller context");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World initial-admission hold acquisition must run outside ambient SQL");
    }
    requireConfiguredTarget(request, lifecycleSelector);

    var wireRequest =
        AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
            .setCanonicalHoldRequestBytes(
                com.google.protobuf.ByteString.copyFrom(request.canonicalRequestBytes()))
            .setCanonicalLifecycleReadRequestBytes(
                com.google.protobuf.ByteString.copyFrom(lifecycleSelector.canonicalBytes()))
            .build();
    requireBound(wireRequest.getSerializedSize(), MAX_REQUEST_BYTES, "hold request");
    var response =
        stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acquireCanonicalInitialAdmissionHold(wireRequest);
    requireBound(response.getSerializedSize(), MAX_RESPONSE_BYTES, "hold response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || response.getHoldIdentityBytes().isEmpty()) {
      throw new IllegalStateException("World returned an incomplete canonical hold response");
    }

    byte[] retainedBytes = response.getHoldIdentityBytes().toByteArray();
    HoldIdentity identity;
    try {
      identity = HoldIdentity.fromStored(retainedBytes);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("World returned an invalid canonical hold identity", invalid);
    }
    if (!request.equals(identity.request())
        || !Arrays.equals(request.canonicalRequestBytes(), identity.canonicalRequestBytes())
        || !workloadNamespace.equals(identity.request().targetNamespace())) {
      throw new IllegalStateException(
          "World returned a hold identity substituted from the exact request or namespace");
    }
    return identity;
  }

  @Override
  public void close() throws InterruptedException {
    if (closed) {
      return;
    }
    closed = true;
    channel.shutdownNow();
    if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("World initial-admission hold channel did not terminate");
    }
  }

  private void requireConfiguredTarget(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleSelector) {
    if (!workloadNamespace.equals(request.targetNamespace())
        || !workloadNamespace.equals(lifecycleSelector.targetNamespace())) {
      throw new IllegalArgumentException(
          "World hold and lifecycle selectors must use the configured namespace");
    }
    if (!request.canonicalTenantId().equals(lifecycleSelector.canonicalTenantId())
        || !request.worldSlug().equals(lifecycleSelector.worldSlug())
        || !request.playableStateNamespaceId().equals(lifecycleSelector.playableStateNamespaceId())
        || !request.playableStateScope().equals(lifecycleSelector.playableStateScope())
        || !request.canonicalGameInstanceId().equals(lifecycleSelector.canonicalGameInstanceId())
        || !request.canonicalVersionId().equals(lifecycleSelector.canonicalVersionId())
        || !lifecycleSelector.publicProduction()) {
      throw new IllegalArgumentException(
          "World lifecycle selector differs from the complete initial-admission hold target");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMutualTls(
      CommonGrpcClientProperties tls) {
    if (tls == null || tls.isPlaintext()) {
      throw new IllegalArgumentException("World initial-admission hold transport requires mTLS");
    }
    requireReadableFile(tls.getCertChain(), "certificate chain");
    requireReadableFile(tls.getPrivateKey(), "private key");
    requireReadableFile(tls.getCaCert(), "CA certificate");
    return tls;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "World initial-admission hold transport requires file-backed TLS material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "World initial-admission hold transport requires file-backed TLS material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World initial-admission hold " + label + " must be file-backed", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "World initial-admission hold " + label + " must be an existing readable file");
    }
  }

  private static void requireBound(int size, int maximum, String field) {
    if (size <= 0 || size > maximum) {
      throw new IllegalArgumentException(field + " is empty or exceeds its byte bound");
    }
  }
}
