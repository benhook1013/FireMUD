package net.firedevops.firemud.common.security.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeCommandServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for Entity's unregistered selected-source command. */
public final class GrpcEntitySelectedSourceIntakeCommandClient
    extends AbstractReloadingBlockingGrpcClient<
        EntitySelectedSourceIntakeCommandServiceGrpc
            .EntitySelectedSourceIntakeCommandServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcEntitySelectedSourceIntakeCommandClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcEntitySelectedSourceIntakeCommandClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Entity selected-source command client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Entity selected-source command stub unavailable");
    }
    initialized = true;
  }

  /** Returns only the exact immutable Entity receipt echoed for this complete original request. */
  public EntitySelectedSourceIntakeCommandEvidence retain(
      EntitySelectedSourceIntakeCommandEvidence.Request request) {
    requireWorkloadOnlyContext();
    Objects.requireNonNull(request, "selected-source command request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Entity selected-source command must use the configured workload namespace");
    }
    var wireRequest = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    var current = requireStub();
    try {
      var response =
          current
              .withMaxOutboundMessageSize(
                  EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .retainSelectedEntitySource(wireRequest);
      return EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException denied) {
      throw Status.fromCode(denied.getStatus().getCode()).asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Entity returned invalid selected-source receipt evidence");
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntitySelectedSourceIntakeCommandServiceGrpc
          .EntitySelectedSourceIntakeCommandServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = entityServerPeerUri(workloadNamespace);
    return EntitySelectedSourceIntakeCommandServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            EntitySelectedSourceIntakeCommandGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
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

  private EntitySelectedSourceIntakeCommandServiceGrpc
          .EntitySelectedSourceIntakeCommandServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Entity selected-source command client is not initialized and available");
    }
    return currentStub;
  }

  private static void requireWorkloadOnlyContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Entity selected-source command requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || AccountSelectedPublicationOrderCredentials.CONTEXT_KEY.get() != null) {
      throw new IllegalStateException(
          "Entity selected-source command requires workload-only context");
    }
  }

  private static String entityServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Entity selected-source command requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Entity selected-source command requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Invalid Entity selected-source command " + label + " path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Entity selected-source command " + label + " must be an existing readable file");
    }
  }
}
