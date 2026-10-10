package net.firedevops.firemud.gamesession.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit Game Session mTLS client for World initial-admission hold identity operations. */
public final class WorldCanonicalInitialAdmissionHoldClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldCanonicalInitialAdmissionHoldServiceGrpc
            .WorldCanonicalInitialAdmissionHoldServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldCanonicalInitialAdmissionHoldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        WorldCanonicalInitialAdmissionHoldClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Opens its managed channel only when the owning Game Session workflow explicitly starts it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World initial-admission hold client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("World initial-admission hold client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Acquires the exact request once and returns only World's immutable issued identity. This call
   * does not retry or create a new initial-admission request identity.
   */
  public HoldIdentity acquire(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    requireNoAmbientOwnerTransaction();
    requireConfiguredNamespace(holdRequest);
    requireLifecycleRequestMatches(holdRequest, lifecycleRequest);

    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acquireCanonicalInitialAdmissionHold(
                AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
                    .setCanonicalHoldRequestBytes(
                        ByteString.copyFrom(holdRequest.canonicalRequestBytes()))
                    .setCanonicalLifecycleReadRequestBytes(
                        ByteString.copyFrom(lifecycleRequest.canonicalBytes()))
                    .build());
    try {
      requireNoUnknownFields(response, "AcquireCanonicalInitialAdmissionHoldResponse");
      HoldIdentity identity = decodeIdentity(response.getHoldIdentityBytes().toByteArray());
      requireExactRequest(holdRequest, identity);
      return identity;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid canonical initial-admission hold identity", invalid);
    }
  }

  /**
   * Reads the historical immutable acquisition identity using a fresh correlation UUID. The result
   * is not evidence of a live hold, terminal hold state, or gameplay admission.
   */
  public HoldIdentity readIdentity(Request holdRequest) {
    requireNoAmbientOwnerTransaction();
    requireConfiguredNamespace(holdRequest);
    UUID readRequestId = UUID.randomUUID();
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readCanonicalInitialAdmissionHoldIdentity(
                ReadCanonicalInitialAdmissionHoldIdentityRequest.newBuilder()
                    .setReadRequestId(readRequestId.toString())
                    .setCanonicalHoldRequestBytes(
                        ByteString.copyFrom(holdRequest.canonicalRequestBytes()))
                    .build());
    try {
      requireNoUnknownFields(response, "ReadCanonicalInitialAdmissionHoldIdentityResponse");
      if (!readRequestId.toString().equals(response.getReadRequestId())) {
        throw new IllegalArgumentException(
            "World initial-admission identity read changed its correlation UUID");
      }
      HoldIdentity identity = decodeIdentity(response.getHoldIdentityBytes().toByteArray());
      requireExactRequest(holdRequest, identity);
      return identity;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid historical initial-admission hold identity", invalid);
    }
  }

  /**
   * Reads one authenticated World snapshot for the exact immutable hold and lifecycle request. The
   * returned state is observational only; it does not prove continuing protection or authorize an
   * admission-pointer commit.
   */
  public WorldCanonicalInitialAdmissionHoldState readState(
      HoldIdentity holdIdentity, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    requireNoAmbientOwnerTransaction();
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    Request holdRequest = holdIdentity.request();
    requireConfiguredNamespace(holdRequest);
    requireLifecycleStateReadRequestMatches(holdRequest, lifecycleRequest);

    UUID readRequestId = lifecycleRequest.readRequestId();
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readCanonicalInitialAdmissionHoldState(
                ReadCanonicalInitialAdmissionHoldStateRequest.newBuilder()
                    .setReadRequestId(readRequestId.toString())
                    .setHoldIdentityBytes(ByteString.copyFrom(holdIdentity.canonicalBytes()))
                    .setCanonicalLifecycleReadRequestBytes(
                        ByteString.copyFrom(lifecycleRequest.canonicalBytes()))
                    .build());
    try {
      requireNoUnknownFields(response, "ReadCanonicalInitialAdmissionHoldStateResponse");
      if (!readRequestId.toString().equals(response.getReadRequestId())) {
        throw new IllegalArgumentException(
            "World initial-admission state read changed its correlation UUID");
      }
      WorldCanonicalInitialAdmissionHoldState state =
          WorldCanonicalInitialAdmissionHoldState.fromStored(
              response.getHoldStateBytes().toByteArray());
      if (!holdIdentity.equals(state.holdIdentity())) {
        throw new IllegalArgumentException(
            "World initial-admission state changed the exact immutable hold identity");
      }
      if (!lifecycleRequest.equals(state.lifecycleEvidence().request())) {
        throw new IllegalArgumentException(
            "World initial-admission state changed the complete lifecycle read request");
      }
      return state;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid canonical initial-admission hold state", invalid);
    }
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
  protected WorldCanonicalInitialAdmissionHoldServiceGrpc
          .WorldCanonicalInitialAdmissionHoldServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldCanonicalInitialAdmissionHoldServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private WorldCanonicalInitialAdmissionHoldServiceGrpc
          .WorldCanonicalInitialAdmissionHoldServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World initial-admission hold client is not initialized and available");
    }
    return currentStub;
  }

  private void requireConfiguredNamespace(Request request) {
    Objects.requireNonNull(request, "holdRequest");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "World initial-admission hold request must use the configured workload namespace");
    }
  }

  private void requireLifecycleRequestMatches(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    if (!lifecycleRequest.publicProduction()) {
      throw new IllegalArgumentException(
          "World initial-admission hold acquisition requires public-production lifecycle evidence");
    }
    if (!workloadNamespace.equals(lifecycleRequest.targetNamespace())
        || !holdRequest.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !holdRequest.worldSlug().equals(lifecycleRequest.worldSlug())
        || !holdRequest.canonicalGameInstanceId().equals(lifecycleRequest.canonicalGameInstanceId())
        || !holdRequest
            .playableStateNamespaceId()
            .equals(lifecycleRequest.playableStateNamespaceId())
        || !holdRequest.playableStateScope().equals(lifecycleRequest.playableStateScope())
        || !holdRequest.canonicalVersionId().equals(lifecycleRequest.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "World lifecycle request must bind the exact initial-admission target");
    }
  }

  private void requireLifecycleStateReadRequestMatches(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    if (!lifecycleRequest.publicProduction()) {
      throw new IllegalArgumentException(
          "World initial-admission hold-state reads require public-production lifecycle evidence");
    }
    if (!workloadNamespace.equals(lifecycleRequest.targetNamespace())
        || !holdRequest.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !holdRequest.worldSlug().equals(lifecycleRequest.worldSlug())
        || !holdRequest.canonicalGameInstanceId().equals(lifecycleRequest.canonicalGameInstanceId())
        || !holdRequest
            .playableStateNamespaceId()
            .equals(lifecycleRequest.playableStateNamespaceId())
        || !holdRequest.playableStateScope().equals(lifecycleRequest.playableStateScope())
        || !holdRequest.canonicalVersionId().equals(lifecycleRequest.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "World lifecycle request must bind the exact initial-admission target");
    }
  }

  private static HoldIdentity decodeIdentity(byte[] encoded) {
    if (encoded.length == 0) {
      throw new IllegalArgumentException(
          "World initial-admission hold identity bytes are required");
    }
    return WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(encoded);
  }

  private static void requireExactRequest(Request request, HoldIdentity identity) {
    if (!request.equals(identity.request())) {
      throw new IllegalArgumentException(
          "World initial-admission hold identity changed the exact request");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World initial-admission hold calls require no ambient owner transaction");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "World initial-admission hold calls require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "World initial-admission hold calls require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "World initial-admission hold calls require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World initial-admission hold " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "World initial-admission hold " + label + " must be an existing readable file");
    }
  }
}
