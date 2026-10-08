package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionProvisionalDecision;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionStatus;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Account client for reading the Game Session-owned provisional decision. */
public final class GameSessionGameplayAdmissionDecisionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;
  private final GrpcServerPeerIdentityCallCredentials serverPeerIdentityCallCredentials;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GameSessionGameplayAdmissionDecisionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireAccountMtls(tlsProps),
        channelFactory,
        GameSessionGameplayAdmissionDecisionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    String gameSessionPeerUri = gameSessionServerPeerUri(workloadNamespace);
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(gameSessionPeerUri);
    this.serverPeerIdentityCallCredentials =
        new GrpcServerPeerIdentityCallCredentials(gameSessionPeerUri);
  }

  /** Initializes the file-backed Account workload mTLS client when its owner enables it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Game Session admission decision client is closed");
    }
    if (initialized) {
      return;
    }
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
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return GameSessionControlPlaneServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCallCredentials(serverPeerIdentityCallCredentials)
        .withCompression("gzip");
  }

  /**
   * Reads one exact PROVISIONAL observation without authorizing, finalizing, or persisting it. The
   * read does not prove durable Account state or current Account/World authority.
   */
  public AccountGameplayAdmissionProvisionalDecision read(
      UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence original) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session admission decision reads cannot run inside an Account transaction");
    }
    requireReady();
    requireCanonicalDecisionId(bindingDecisionId);
    if (original == null) {
      throw new IllegalArgumentException("Original Account admission lease evidence is required");
    }
    if (!workloadNamespace.equals(original.carrier().get("targetNamespace"))) {
      throw new IllegalArgumentException(
          "Account admission lease evidence does not match the configured workload namespace");
    }

    ByteString originalBytes =
        ByteString.copyFrom(original.canonicalJson(), StandardCharsets.UTF_8);
    GetCanonicalGameplayAdmissionDecisionResponse response =
        stub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalGameplayAdmissionDecision(
                GetCanonicalGameplayAdmissionDecisionRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setBindingDecisionUuid(bindingDecisionId.toString())
                    .setLeaseEvidence(originalBytes)
                    .build());

    validateResponse(response, bindingDecisionId, originalBytes, original.sha256());
    return new AccountGameplayAdmissionProvisionalDecision(bindingDecisionId, original);
  }

  private void requireReady() {
    if (closed || !initialized || stub() == null) {
      throw new IllegalStateException(
          "Game Session admission decision client is not initialized or is closed");
    }
  }

  private static void validateResponse(
      GetCanonicalGameplayAdmissionDecisionResponse response,
      UUID expectedDecisionId,
      ByteString expectedEvidence,
      String expectedEvidenceSha256) {
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || !expectedDecisionId.equals(parseCanonicalNonNilV4Uuid(response.getBindingDecisionUuid()))
        || !expectedEvidence.equals(response.getLeaseEvidence())
        || !expectedEvidenceSha256.equals(response.getLeaseEvidenceSha256())
        || response.getStatus()
            != CanonicalGameplayAdmissionDecisionStatus
                .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_PROVISIONAL) {
      throw new IllegalStateException("Game Session admission decision response is invalid");
    }
  }

  private static UUID parseCanonicalNonNilV4Uuid(String value) {
    if (value == null) {
      throw invalidResponse();
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed)
          || parsed.version() != 4
          || parsed.variant() != 2
          || !parsed.toString().equals(value)) {
        throw invalidResponse();
      }
      return parsed;
    } catch (RuntimeException failure) {
      throw invalidResponse();
    }
  }

  private static void requireCanonicalDecisionId(UUID value) {
    if (value == null || NIL_UUID.equals(value) || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical nonnil UUIDv4 decision ID is required");
    }
  }

  private static IllegalStateException invalidResponse() {
    return new IllegalStateException("Game Session admission decision response is invalid");
  }

  private static String gameSessionServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
  }

  private static CommonGrpcClientProperties requireAccountMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Account gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Gameplay admission decision reads require Account workload mTLS");
    }
    if (!isReadableFile(tlsProps.getCertChain())
        || !isReadableFile(tlsProps.getPrivateKey())
        || !isReadableFile(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Gameplay admission decision reads require readable file-backed Account certificate, "
              + "key, and CA files");
    }
    return tlsProps;
  }

  private static boolean isReadableFile(String value) {
    if (value == null || value.isBlank() || value.trim().startsWith("classpath:")) {
      return false;
    }
    try {
      Path path = Path.of(value.trim());
      return Files.isRegularFile(path) && Files.isReadable(path);
    } catch (RuntimeException failure) {
      return false;
    }
  }
}
