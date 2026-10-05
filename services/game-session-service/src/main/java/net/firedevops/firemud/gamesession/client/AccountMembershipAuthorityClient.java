package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Unwired Game Session client for Account-authenticated runtime membership readback. */
public final class AccountMembershipAuthorityClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long MAX_FRESHNESS_SECONDS = 15L;
  private static final String EXPECTED_ACCOUNT_SERVICE = "account-service";

  private final String workloadNamespace;

  /**
   * Creates a source-only client. Construction does not open a channel; an explicit owner must call
   * {@link #init()} before a read. Workload mTLS authenticates this route, while the typed player
   * context selects the exact recipient snapshot and is not itself a credential.
   */
  public AccountMembershipAuthorityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProps),
        channelFactory,
        stubCustomizer,
        AccountMembershipAuthorityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    if (stubCustomizer == null) {
      throw new IllegalArgumentException("Game Session internal gRPC stub customizer is required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes this client only when an explicit owner activates the membership read. */
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
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(ManagedChannel channel) {
    return applyStubCustomizer(
        AccountServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                new GrpcServerPeerIdentityClientInterceptor(
                    "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + EXPECTED_ACCOUNT_SERVICE))
            .withCompression("gzip"));
  }

  /** Reads the exact Account recipient snapshot and fails closed on any incomplete evidence. */
  public GetTenantMembershipForRuntimeResponse getTenantMembershipForRuntime(
      PlayerExecutionContext playerContext) {
    validatePlayerContext(playerContext);
    AccountServiceGrpc.AccountServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Account membership authority client is not initialized");
    }

    GetTenantMembershipForRuntimeRequest request =
        GetTenantMembershipForRuntimeRequest.newBuilder().setPlayerContext(playerContext).build();
    try {
      GetTenantMembershipForRuntimeResponse response =
          currentStub
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .getTenantMembershipForRuntime(request);
      return verifyResponse(response, playerContext);
    } catch (StatusRuntimeException exception) {
      throw new IllegalStateException("Account runtime membership read failed closed", exception);
    }
  }

  private GetTenantMembershipForRuntimeResponse verifyResponse(
      GetTenantMembershipForRuntimeResponse response, PlayerExecutionContext request) {
    if (response == null) {
      throw invalidResponse("is absent");
    }
    if (response.hasError()) {
      throw invalidResponse("contains an Account error");
    }
    if (!"AVAILABLE".equals(response.getAuthorityAvailability())
        || !request.getAccountId().equals(response.getAccountId())
        || !request.getTenantId().equals(response.getTenantId())
        || !request.getAccountId().equals(response.getRequestAccountId())
        || !request.getTenantId().equals(response.getRequestTenantId())
        || !request.getRequestId().equals(response.getRequestId())) {
      throw invalidResponse("availability or exact recipient/request echoes changed");
    }

    final Instant evaluatedAt;
    try {
      evaluatedAt = Instant.parse(response.getEvaluatedAt());
    } catch (RuntimeException exception) {
      throw invalidResponse("evaluation time is malformed", exception);
    }
    Instant now = Instant.now();
    if (evaluatedAt.isAfter(now)
        || evaluatedAt.isBefore(now.minus(MAX_FRESHNESS_SECONDS, ChronoUnit.SECONDS))) {
      throw invalidResponse("evaluation time is stale or in the future");
    }

    try {
      RuntimeMembershipResponseValidator.validateContent(response);
    } catch (IllegalArgumentException exception) {
      throw invalidResponse("content evidence is incomplete or contradictory", exception);
    }
    return response;
  }

  private static void validatePlayerContext(PlayerExecutionContext context) {
    if (context == null || !context.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Complete typed PlayerExecutionContext is required");
    }
    if (!isCanonicalNonNilUuid(context.getAccountId())
        || !isCanonicalNonNilUuid(context.getTenantId())
        || !isCanonicalNonNilUuid(context.getPlayableStateNamespaceId())
        || !isCanonicalNonNilUuid(context.getRealmId())
        || !isCanonicalNonNilUuid(context.getGameInstanceId())
        || !isPositiveCanonicalDecimal(context.getSessionId())
        || (!context.getCharacterId().isEmpty()
            && !isPositiveCanonicalDecimal(context.getCharacterId()))
        || (!"SHARED".equals(context.getPlayableStateScope())
            && !"ISOLATED".equals(context.getPlayableStateScope()))
        || context.getRequestId().isBlank()) {
      throw new IllegalArgumentException(
          "PlayerExecutionContext selectors are incomplete or invalid");
    }
  }

  private static boolean isCanonicalNonNilUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value)
          && !new UUID(0L, 0L).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isPositiveCanonicalDecimal(String value) {
    return value != null && value.matches("[1-9][0-9]*");
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Runtime membership read requires Game Session workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Runtime membership read requires Game Session certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Runtime membership read requires file-backed Game Session workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException("Account runtime membership response " + message);
  }

  private static IllegalStateException invalidResponse(String message, Throwable cause) {
    return new IllegalStateException("Account runtime membership response " + message, cause);
  }
}
