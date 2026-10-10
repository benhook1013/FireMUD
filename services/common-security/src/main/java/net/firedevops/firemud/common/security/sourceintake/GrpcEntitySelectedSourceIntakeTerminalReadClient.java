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
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for Entity's exact original selected-source terminal read. */
public final class GrpcEntitySelectedSourceIntakeTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        EntitySelectedSourceIntakeTerminalReadServiceGrpc
            .EntitySelectedSourceIntakeTerminalReadServiceBlockingStub>
    implements EntitySelectedSourceIntakeTerminalReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcEntitySelectedSourceIntakeTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcEntitySelectedSourceIntakeTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Entity terminal-read client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Entity terminal-read stub unavailable");
    }
    initialized = true;
  }

  /** Returns only an exact original COMMITTED_EMPTY receipt, outside owner SQL. */
  @Override
  public EntitySelectedSourceIntakeTerminalReadEvidence read(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request) {
    requireWorkloadOnlyContext();
    Objects.requireNonNull(request, "terminal-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Entity terminal read must use the configured workload namespace");
    }
    var wireRequest = EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request);
    var current = requireStub();
    try {
      var response =
          current
              .withMaxOutboundMessageSize(
                  EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .readSelectedSourceIntakeTerminal(wireRequest);
      return EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException denied) {
      throw Status.fromCode(denied.getStatus().getCode()).asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Entity returned invalid terminal evidence", malformed);
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
  protected EntitySelectedSourceIntakeTerminalReadServiceGrpc
          .EntitySelectedSourceIntakeTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = entityServerPeerUri(workloadNamespace);
    return EntitySelectedSourceIntakeTerminalReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
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

  private EntitySelectedSourceIntakeTerminalReadServiceGrpc
          .EntitySelectedSourceIntakeTerminalReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Entity terminal-read client is not initialized and available");
    }
    return currentStub;
  }

  private static void requireWorkloadOnlyContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Entity terminal read requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException("Entity terminal read requires workload-only context");
    }
  }

  private static String entityServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Entity terminal read requires workload mTLS");
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
          "Entity terminal read requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Entity terminal read " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Entity terminal read " + label + " must be an existing readable file");
    }
  }
}
