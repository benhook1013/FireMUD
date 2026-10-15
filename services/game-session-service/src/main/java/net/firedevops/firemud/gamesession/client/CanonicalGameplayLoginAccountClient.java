package net.firedevops.firemud.gamesession.client;

import com.google.protobuf.Any;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.shared.v1.ErrorDetail;

/**
 * Unregistered canonical Authenticate client. One invocation owns only its transient request and
 * one unchanged deadline. It supplies no ingress attestation, retained retry, context installation,
 * registry/currentness, retained disposal ownership or gameplay authority.
 */
public final class CanonicalGameplayLoginAccountClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private final String expectedAccountPeer;
  private final Clock clock;
  private final long invocationBudgetNanos;
  private final AccountAsymmetricJwtVerifier verifier;

  public CanonicalGameplayLoginAccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      String workloadNamespace,
      Clock clock,
      Duration invocationBudget,
      AccountAsymmetricJwtVerifier verifier) {
    super(
        endpoints,
        requireMutualTls(tlsProps),
        channelFactory,
        stubCustomizer,
        CanonicalGameplayLoginAccountClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Exact workload namespace required");
    this.clock = Objects.requireNonNull(clock, "Clock required");
    this.verifier = Objects.requireNonNull(verifier, "Asymmetric Account verifier required");
    invocationBudgetNanos =
        Objects.requireNonNull(invocationBudget, "Invocation budget required").toNanos();
    if (invocationBudgetNanos <= 0)
      throw new IllegalArgumentException("Positive finite invocation budget required");
    expectedAccountPeer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
  }

  /** Explicit workflow-owned initialization; there is no Spring lifecycle registration. */
  public void initialize() throws SSLException, IOException {
    initReloadingClient();
  }

  public CanonicalGameplayLoginResult authenticate(
      AuthenticateRequest originalRequest, BooleanSupplier originalCustodyCurrent) {
    CanonicalGameplayLoginResult.requireOriginalRequest(originalRequest);
    Objects.requireNonNull(originalCustodyCurrent, "Original custody guard required");
    return invokeWithBoundedRetry(
        (currentStub, overall) -> {
          var response = currentStub.withDeadline(overall).authenticate(originalRequest);
          return CanonicalGameplayLoginResult.fromAuthenticatedResponse(
              response, originalRequest, verifier, clock);
        },
        () -> custodyCurrent(originalCustodyCurrent));
  }

  private static boolean custodyCurrent(BooleanSupplier originalCustodyCurrent) {
    try {
      return originalCustodyCurrent.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** Lifecycle-only call: the workflow retains the exact original nonsecret request identity. */
  public InitialGameplayLoginDisposalResult retireInitialGameplayLogin(
      RetireInitialGameplayLoginRequest originalRequest) {
    InitialGameplayLoginDisposalResult.requireOriginalRequest(originalRequest);
    return invokeWithBoundedRetry(
        (currentStub, overall) -> {
          var response =
              currentStub.withDeadline(overall).retireInitialGameplayLogin(originalRequest);
          return InitialGameplayLoginDisposalResult.fromAuthenticatedResponse(
              response, originalRequest);
        },
        () -> true);
  }

  private <T> T invokeWithBoundedRetry(
      BiFunction<AccountServiceGrpc.AccountServiceBlockingStub, Deadline, T> call,
      BooleanSupplier operationCurrent) {
    Objects.requireNonNull(operationCurrent, "Operation currentness guard required");
    Deadline overall = Deadline.after(invocationBudgetNanos, TimeUnit.NANOSECONDS);
    if (Context.current().getDeadline() != null)
      overall = overall.minimum(Context.current().getDeadline());
    for (int attempt = 0; attempt < 2; attempt++) {
      if (overall.isExpired()) throw failure(Status.Code.DEADLINE_EXCEEDED);
      if (Context.current().isCancelled() || !custodyCurrent(operationCurrent))
        throw failure(Status.Code.CANCELLED);
      var currentStub = stub();
      if (currentStub == null) throw failure(Status.Code.UNAVAILABLE);
      try {
        return call.apply(currentStub, overall);
      } catch (StatusRuntimeException transport) {
        Status.Code code = transport.getStatus().getCode();
        if (attempt != 0
            || (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED))
          throw boundedTransportFailure(transport);
        if (overall.isExpired()) throw failure(Status.Code.DEADLINE_EXCEEDED);
        if (Context.current().isCancelled() || !custodyCurrent(operationCurrent))
          throw failure(Status.Code.CANCELLED);
        try {
          reloadChannel();
        } catch (SSLException reloadFailure) {
          throw failure(Status.Code.UNAVAILABLE);
        }
      }
    }
    throw failure(Status.Code.UNAVAILABLE);
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
    return applyStubCustomizer(AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip"))
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedAccountPeer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedAccountPeer));
  }

  private static CommonGrpcClientProperties requireMutualTls(
      CommonGrpcClientProperties properties) {
    Objects.requireNonNull(properties, "TLS properties required");
    if (properties.isPlaintext()
        || blank(properties.getCertChain())
        || blank(properties.getPrivateKey())
        || blank(properties.getCaCert())) {
      throw new IllegalArgumentException("Canonical LOGIN requires mutual TLS material");
    }
    return properties;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static StatusRuntimeException boundedTransportFailure(StatusRuntimeException original) {
    Status.Code outer = original.getStatus().getCode();
    String code = outer.name();
    var detail = StatusProto.fromThrowable(original);
    if (outer == Status.Code.UNAVAILABLE && detail != null && detail.getDetailsCount() == 1) {
      try {
        String ownerCode = detail.getDetails(0).unpack(ErrorDetail.class).getCode();
        if (AuthenticationErrorCodes.UNAVAILABLE.equals(ownerCode)
            || AuthenticationErrorCodes.ABUSE_CONTROL_UNAVAILABLE.equals(ownerCode))
          code = ownerCode;
      } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
        // Only bounded owner codes are preserved; original detail/messages are never exposed.
      }
    }
    String message = "Canonical LOGIN transport did not complete";
    return StatusProto.toStatusRuntimeException(
        com.google.rpc.Status.newBuilder()
            .setCode(outer.value())
            .setMessage(message)
            .addDetails(
                Any.pack(ErrorDetail.newBuilder().setCode(code).setMessage(message).build()))
            .build());
  }

  private static StatusRuntimeException failure(Status.Code code) {
    return Status.fromCode(code)
        .withDescription("Canonical LOGIN transport did not complete")
        .asRuntimeException();
  }
}
