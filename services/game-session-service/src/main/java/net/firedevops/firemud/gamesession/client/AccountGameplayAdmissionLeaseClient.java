package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OperationEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Manual Game Session to Account transport for the four exact NEW-binding lease operations. */
public final class AccountGameplayAdmissionLeaseClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameplayAdmissionLeaseServiceGrpc.AccountGameplayAdmissionLeaseServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AccountGameplayAdmissionLeaseClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Gameplay admission lease"),
        channelFactory,
        AccountGameplayAdmissionLeaseClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes this manually composed client outside SQL transactions and synchronizations. */
  public void init() throws SSLException, IOException {
    requireNoAmbientSql("Gameplay admission lease client initialization requires no ambient SQL");
    initOnce("Gameplay admission lease client is closed", "Account lease service unavailable");
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
  protected AccountGameplayAdmissionLeaseServiceGrpc
          .AccountGameplayAdmissionLeaseServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountGameplayAdmissionLeaseServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(AccountGameplayAdmissionLeaseGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  /** Acquires the exact Account-owned lease for the supplied NEW-binding comparison values. */
  public OperationEvidence acquire(
      UUID requestId,
      String presentedInitialGsACompactJwt,
      Candidate expectedCandidate,
      Target expectedTarget,
      BigInteger bindingGeneration) {
    requireNoAmbientSql("Gameplay admission lease acquisition requires no ambient SQL");
    var wireRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
            requestId,
            presentedInitialGsACompactJwt,
            expectedCandidate,
            expectedTarget,
            bindingGeneration);
    AcquireRequest expected =
        AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(wireRequest);
    try {
      OperationEvidence response =
          AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
              expected,
              requireInitializedStub("Account lease service is not initialized")
                  .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                  .acquireGameplayAdmissionLease(wireRequest));
      requireLeaseCorrelation(response.originalLeaseEvidence());
      return response;
    } catch (StatusRuntimeException unavailable) {
      throw new IllegalStateException("Account gameplay admission lease acquisition failed");
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Account returned invalid gameplay admission lease evidence");
    }
  }

  /** Finalizes one unchanged original lease against its exact Game Session decision UUID. */
  public OperationEvidence finalizeLease(
      String presentedInitialGsACompactJwt,
      byte[] originalLeaseEvidenceUtf8,
      UUID bindingDecisionId) {
    requireNoAmbientSql("Gameplay admission lease finalization requires no ambient SQL");
    var wireRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(
            presentedInitialGsACompactJwt, originalLeaseEvidenceUtf8, bindingDecisionId);
    OriginalLeaseRequest expected =
        AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(wireRequest);
    requireLeaseCorrelation(expected.originalLeaseEvidence());
    try {
      OperationEvidence response =
          AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(
              expected,
              requireInitializedStub("Account lease service is not initialized")
                  .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                  .finalizeGameplayAdmissionLease(wireRequest));
      requireLeaseCorrelation(response.originalLeaseEvidence());
      return response;
    } catch (StatusRuntimeException unavailable) {
      throw new IllegalStateException("Account gameplay admission lease finalization failed");
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Account returned invalid gameplay admission lease evidence");
    }
  }

  /** Reads exact-operation recovery state without renewing or recreating the original lease. */
  public OperationEvidence read(
      String presentedInitialGsACompactJwt, byte[] originalLeaseEvidenceUtf8) {
    requireNoAmbientSql("Gameplay admission lease read requires no ambient SQL");
    var wireRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(
            presentedInitialGsACompactJwt, originalLeaseEvidenceUtf8);
    OriginalLeaseRequest expected =
        AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(wireRequest);
    requireLeaseCorrelation(expected.originalLeaseEvidence());
    try {
      OperationEvidence response =
          AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(
              expected,
              requireInitializedStub("Account lease service is not initialized")
                  .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                  .readGameplayAdmissionLease(wireRequest));
      requireLeaseCorrelation(response.originalLeaseEvidence());
      return response;
    } catch (StatusRuntimeException unavailable) {
      throw new IllegalStateException("Account gameplay admission lease read failed");
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Account returned invalid gameplay admission lease evidence");
    }
  }

  /** Aborts the exact lease; a null decision UUID retains the protocol's UNKNOWN meaning. */
  public OperationEvidence abort(
      String presentedInitialGsACompactJwt,
      byte[] originalLeaseEvidenceUtf8,
      UUID bindingDecisionId) {
    requireNoAmbientSql("Gameplay admission lease abort requires no ambient SQL");
    var wireRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(
            presentedInitialGsACompactJwt, originalLeaseEvidenceUtf8, bindingDecisionId);
    OriginalLeaseRequest expected =
        AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(wireRequest);
    requireLeaseCorrelation(expected.originalLeaseEvidence());
    try {
      OperationEvidence response =
          AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(
              expected,
              requireInitializedStub("Account lease service is not initialized")
                  .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                  .abortGameplayAdmissionLease(wireRequest));
      requireLeaseCorrelation(response.originalLeaseEvidence());
      return response;
    } catch (StatusRuntimeException unavailable) {
      throw new IllegalStateException("Account gameplay admission lease abort failed");
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Account returned invalid gameplay admission lease evidence");
    }
  }

  private void requireLeaseCorrelation(AccountGameplayAdmissionLeaseEvidence lease) {
    Objects.requireNonNull(lease, "lease");
    Object targetNamespace = lease.carrier().get("targetNamespace");
    Object callerWorkload = lease.carrier().get("callerWorkload");
    String expectedCaller = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    if (!workloadNamespace.equals(targetNamespace) || !expectedCaller.equals(callerWorkload)) {
      throw new IllegalArgumentException(
          "Account lease scope does not correlate to this Game Session workload");
    }
  }
}
