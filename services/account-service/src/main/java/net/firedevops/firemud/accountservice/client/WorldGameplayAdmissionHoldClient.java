package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalPlayerAdmissionHoldServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account mTLS acquire/read consumer for the distinct World player-serving hold.
 * Returned evidence authenticates original hold readback only; it supplies no current Account
 * authority, admission, release, source finalization, or physical Account COMMIT proof.
 */
public final class WorldGameplayAdmissionHoldClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldCanonicalPlayerAdmissionHoldServiceGrpc
            .WorldCanonicalPlayerAdmissionHoldServiceBlockingStub> {
  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;
  private final GrpcServerPeerIdentityCallCredentials serverPeerIdentityCallCredentials;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldGameplayAdmissionHoldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireAccountMtls(tlsProps),
        channelFactory,
        WorldGameplayAdmissionHoldClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    this.workloadNamespace = workloadNamespace;
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    serverPeerIdentityInterceptor = new GrpcServerPeerIdentityClientInterceptor(peer);
    serverPeerIdentityCallCredentials = new GrpcServerPeerIdentityCallCredentials(peer);
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("World admission hold client is closed");
    if (initialized) return;
    try {
      initReloadingClient();
      initialized = true;
    } catch (IOException | RuntimeException failure) {
      closed = true;
      throw failure;
    }
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected WorldCanonicalPlayerAdmissionHoldServiceGrpc
          .WorldCanonicalPlayerAdmissionHoldServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return WorldCanonicalPlayerAdmissionHoldServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCallCredentials(serverPeerIdentityCallCredentials)
        .withCompression("gzip");
  }

  /** Acquires the original lease-bound hold; no retry creates or extends an admission lease. */
  public WorldCanonicalPlayerAdmissionHoldEvidence acquire(Request original) {
    requireCall(original);
    UUID correlation = freshCorrelation(original);
    var response =
        stub()
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .acquireCanonicalPlayerAdmissionHold(
                AcquireCanonicalPlayerAdmissionHoldRequest.newBuilder()
                    .setRequestId(correlation.toString())
                    .setOriginalLeaseJson(leaseBytes(original))
                    .setOriginalLeaseSha256(original.lease().sha256())
                    .setExpectedLifecycleEpoch(Long.toString(original.expectedLifecycleEpoch()))
                    .setExpectedRowVersion(Long.toString(original.expectedRowVersion()))
                    .build());
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) throw invalid();
    return checked(
        original,
        correlation,
        response.getRequestId(),
        response.getOriginalLeaseSha256(),
        response.getHoldEvidenceJson(),
        response.getHoldEvidenceSha256());
  }

  /** Recovers authenticated original evidence; a missing response remains unresolved. */
  public WorldCanonicalPlayerAdmissionHoldEvidence read(Request original) {
    requireCall(original);
    UUID correlation = freshCorrelation(original);
    var response =
        stub()
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .readCanonicalPlayerAdmissionHold(
                ReadCanonicalPlayerAdmissionHoldRequest.newBuilder()
                    .setRequestId(correlation.toString())
                    .setOriginalLeaseJson(leaseBytes(original))
                    .setOriginalLeaseSha256(original.lease().sha256())
                    .setExpectedLifecycleEpoch(Long.toString(original.expectedLifecycleEpoch()))
                    .setExpectedRowVersion(Long.toString(original.expectedRowVersion()))
                    .build());
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) throw invalid();
    return checked(
        original,
        correlation,
        response.getRequestId(),
        response.getOriginalLeaseSha256(),
        response.getHoldEvidenceJson(),
        response.getHoldEvidenceSha256());
  }

  /** An already retained hold cannot be replaced by another valid hold for the same request. */
  public WorldCanonicalPlayerAdmissionHoldEvidence readExact(
      WorldCanonicalPlayerAdmissionHoldEvidence original) {
    Objects.requireNonNull(original, "Original World hold is required");
    var observed = read(original.request());
    if (!original.holdId().equals(observed.holdId())
        || !original.holdFence().equals(observed.holdFence())
        || !Arrays.equals(original.canonicalBytes(), observed.canonicalBytes())) throw invalid();
    return observed;
  }

  private void requireCall(Request original) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "World hold calls cannot run inside Account transaction or synchronization");
    Objects.requireNonNull(original, "Original Account lease request is required");
    if (!workloadNamespace.equals(original.targetNamespace()))
      throw new IllegalArgumentException(
          "Original lease does not match configured workload namespace");
    if (closed || !initialized || stub() == null)
      throw new IllegalStateException(
          "World admission hold client is not initialized or is closed");
  }

  private static UUID freshCorrelation(Request original) {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(original.attemptId()) || id.equals(original.leaseId()));
    return id;
  }

  private static ByteString leaseBytes(Request original) {
    return ByteString.copyFrom(original.lease().canonicalJson(), StandardCharsets.UTF_8);
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence checked(
      Request original,
      UUID correlation,
      String echo,
      String leaseSha,
      ByteString bytes,
      String sha) {
    try {
      UUID parsed = UUID.fromString(echo);
      if (!parsed.equals(correlation)
          || !parsed.toString().equals(echo)
          || parsed.version() != 4
          || parsed.variant() != 2
          || !original.lease().sha256().equals(leaseSha)
          || bytes.isEmpty()
          || bytes.size() > WorldCanonicalPlayerAdmissionHoldEvidence.MAX_EVIDENCE_BYTES)
        throw invalid();
      var result =
          WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes.toByteArray(), sha);
      if (!original.sameBinding(result.request())
          || !original.lease().canonicalJson().equals(result.request().lease().canonicalJson()))
        throw invalid();
      original.requireExactActiveWorld(result.worldEvidence());
      return result;
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("World admission hold response is invalid", failure);
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("World admission hold response is invalid or unresolved");
  }

  private static CommonGrpcClientProperties requireAccountMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null || tlsProps.isPlaintext())
      throw new IllegalArgumentException(
          "World admission hold calls require Account workload mTLS");
    if (!isReadableFile(tlsProps.getCertChain())
        || !isReadableFile(tlsProps.getPrivateKey())
        || !isReadableFile(tlsProps.getCaCert()))
      throw new IllegalArgumentException(
          "World admission hold calls require readable file-backed Account certificate, key and CA");
    return tlsProps;
  }

  private static boolean isReadableFile(String value) {
    if (value == null || value.isBlank() || value.trim().startsWith("classpath:")) return false;
    try {
      Path path = Path.of(value.trim());
      return Files.isRegularFile(path) && Files.isReadable(path);
    } catch (RuntimeException failure) {
      return false;
    }
  }
}
