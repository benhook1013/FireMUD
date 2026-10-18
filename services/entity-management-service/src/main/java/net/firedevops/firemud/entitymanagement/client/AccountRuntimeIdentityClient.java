package net.firedevops.firemud.entitymanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.account.v1.RuntimeAccountIdentityServiceGrpc;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Unwired Entity Management candidate for exact Account-owned runtime identity reads. */
public final class AccountRuntimeIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AccountRuntimeIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireEntityManagementMtls(tlsProps),
        channelFactory,
        AccountRuntimeIdentityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the client only when an explicit caller owns activation of this handoff. */
  public void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return RuntimeAccountIdentityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service"))
        .withCompression("gzip");
  }

  /** Reads Account's exact persisted identity provenance without granting gameplay authority. */
  public RuntimeAccountIdentityEvidence resolveRuntimeAccountIdentity(
      String canonicalAccountId, String requestId) {
    UUID accountUuid = parseCanonicalNonNilUuid(canonicalAccountId, "canonical account ID");
    UUID requestUuid = parseCanonicalNonNilUuid(requestId, "request ID");
    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub currentStub =
        stub();
    if (currentStub == null) {
      throw new IllegalStateException("Account runtime identity client is not initialized");
    }

    ResolveRuntimeAccountIdentityResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveRuntimeAccountIdentity(
                ResolveRuntimeAccountIdentityRequest.newBuilder()
                    .setCanonicalAccountId(accountUuid.toString())
                    .setRequestId(requestUuid.toString())
                    .build());
    if (response == null) {
      throw new IllegalStateException("Account runtime identity response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException(
          "Account runtime identity response contains unsupported fields");
    }

    RuntimeAccountIdentityEvidence evidence;
    try {
      evidence =
          new RuntimeAccountIdentityEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getRequestId(), "response request ID"),
              parseCanonicalNonNilUuid(
                  response.getCanonicalAccountId(), "response canonical account ID"),
              response.getSourceAccountRowId(),
              response.getAccountUuidProvenance());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Account runtime identity response is invalid", exception);
    }

    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !requestUuid.equals(evidence.requestId())
        || !accountUuid.equals(evidence.canonicalAccountId())) {
      throw new IllegalStateException(
          "Account runtime identity response does not match the exact request");
    }
    return evidence;
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static CommonGrpcClientProperties requireEntityManagementMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Entity Management gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Runtime account identity reads require Entity Management workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Runtime account identity reads require Entity Management certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Runtime account identity reads require file-backed Entity Management workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
