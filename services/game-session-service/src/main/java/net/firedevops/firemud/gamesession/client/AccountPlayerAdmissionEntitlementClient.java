package net.firedevops.firemud.gamesession.client;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.account.v1.AccountPlayerAdmissionEntitlementReadServiceGrpc;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;

/**
 * Manually composed Account PLAYER_ADMISSION read. The caller supplies a verified first-party
 * connect context and live LOGIN custody; this client contributes no admission decision or hold.
 */
public final class AccountPlayerAdmissionEntitlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountPlayerAdmissionEntitlementReadServiceGrpc
            .AccountPlayerAdmissionEntitlementReadServiceBlockingStub> {
  private final String namespace;
  private final String expectedAccountPeer;
  private final Clock clock;

  public AccountPlayerAdmissionEntitlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String workloadNamespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Player-admission entitlement read"),
        channels,
        AccountPlayerAdmissionEntitlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = workloadNamespace;
    this.expectedAccountPeer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    this.clock = Objects.requireNonNull(clock, "Clock required");
  }

  /** Initializes this workflow-owned client outside any ambient SQL transaction. */
  public void init() throws IOException {
    requireNoAmbientSql(
        "Player-admission entitlement client initialization requires no ambient SQL");
    initOnce("Player-admission entitlement client is closed", "Account read unavailable");
  }

  /**
   * Reads the exact current Account value for one verified first-party scope and LOGIN result. The
   * token is disclosed only by the generated request after exact same-namespace Account mTLS peer
   * verification. This read supplies no World hold and never persists LOGIN custody.
   */
  public RuntimePlayerAdmissionEntitlementEvidence read(
      FirstPartyConnectContext verifiedContext,
      UUID readRequestId,
      CanonicalGameplayLoginResult loginCustody,
      BooleanSupplier loginCustodyCurrent) {
    requireNoAmbientSql("Player-admission entitlement RPC requires no ambient SQL");
    Deadline overall =
        Deadline.after(
            RuntimePlayerAdmissionEntitlementGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    if (Context.current().getDeadline() != null) {
      overall = overall.minimum(Context.current().getDeadline());
    }
    if (!v4(readRequestId)) {
      throw invalid();
    }
    if (!current(loginCustodyCurrent) || Context.current().isCancelled()) {
      throw failure(Status.Code.CANCELLED);
    }
    if (overall.isExpired()) {
      throw failure(Status.Code.DEADLINE_EXCEEDED);
    }

    var request = request(verifiedContext, readRequestId);
    var login = requireLiveLogin(loginCustody, request.target().accountId());
    byte[] compactJwt = null;
    try {
      compactJwt = loginCustody.compactJwtBytes();
      if (!current(loginCustodyCurrent) || Context.current().isCancelled()) {
        throw failure(Status.Code.CANCELLED);
      }
      if (overall.isExpired()) {
        throw failure(Status.Code.DEADLINE_EXCEEDED);
      }
      if (!isBoundedAsciiJwt(compactJwt) || !matchesSha256(compactJwt, login.tokenSha256())) {
        throw invalid();
      }
      String compact = new String(compactJwt, StandardCharsets.US_ASCII);
      var wireRequest = RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(request, compact);
      var stub =
          requireInitializedStub("Account player-admission entitlement client is unavailable")
              .withDeadline(overall)
              .withMaxOutboundMessageSize(
                  RuntimePlayerAdmissionEntitlementGrpcCodec.MAX_REQUEST_WIRE_BYTES)
              .withMaxInboundMessageSize(
                  RuntimePlayerAdmissionEntitlementGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
      // Recheck immediately before invoking gRPC; the call credentials independently gate body
      // release on the exact authenticated Account peer.
      if (!current(loginCustodyCurrent) || Context.current().isCancelled()) {
        throw failure(Status.Code.CANCELLED);
      }
      if (overall.isExpired()) {
        throw failure(Status.Code.DEADLINE_EXCEEDED);
      }
      var response = stub.readPlayerAdmissionEntitlement(wireRequest);
      if (!current(loginCustodyCurrent) || Context.current().isCancelled()) {
        throw failure(Status.Code.CANCELLED);
      }
      var evidence = RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(request, response);
      requireTargetBinding(evidence, verifiedContext);
      requireLoginBinding(evidence, login);
      if (!current(loginCustodyCurrent) || Context.current().isCancelled()) {
        throw failure(Status.Code.CANCELLED);
      }
      evidence.requireCurrentAt(clock.instant());
      return evidence;
    } catch (StatusRuntimeException transport) {
      throw failure(transport.getStatus().getCode());
    } finally {
      if (compactJwt != null) {
        Arrays.fill(compactJwt, (byte) 0);
      }
    }
  }

  private RuntimePlayerAdmissionEntitlementGrpcCodec.Request request(
      FirstPartyConnectContext context, UUID readRequestId) {
    try {
      if (context == null || !context.hasCompleteRoutingScope()) {
        throw invalid();
      }
      var claims = context.claims();
      var target = context.resolvedTarget();
      if (target == null
          || !namespace.equals(target.targetNamespace())
          || !"SHARED".equals(target.playableStateScope())
          || !"PRESEEDED_ONLY".equals(target.characterCreationPolicy())
          || target.positiveDurableAbort()) {
        throw invalid();
      }
      UUID accountId = UUID.fromString(context.accountId());
      if (!v4(accountId)
          || claims.expiresAtMillis() <= clock.millis()
          || context.connectScopeId() == null
          || context.connectScopeId().isBlank()) {
        throw invalid();
      }
      var selector =
          new RetainedTargetSelector(
              readRequestId,
              accountId,
              target.targetNamespace(),
              target.canonicalTenantId(),
              target.tenantSlug(),
              target.worldSlug(),
              target.realmId(),
              target.realmSlug(),
              target.playableStateNamespaceId(),
              target.playableStateScope(),
              target.canonicalGameInstanceId(),
              target.catalogRevision(),
              target.pointerVersion());
      return new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
          selector, context.connectScopeId());
    } catch (RuntimeException invalidContext) {
      throw invalid();
    }
  }

  private CanonicalGameplayLoginResult.NonSecretEvidence requireLiveLogin(
      CanonicalGameplayLoginResult custody, UUID expectedAccount) {
    try {
      if (custody == null) {
        throw invalid();
      }
      var evidence = custody.evidence();
      long now = clock.instant().getEpochSecond();
      if (evidence == null
          || !expectedAccount.equals(evidence.accountId())
          || !GameSessionAccountDelegationProfile.PROFILE.equals(evidence.tokenProfile())
          || evidence.issuedAtEpochSecond() > now
          || evidence.notBeforeEpochSecond() > now
          || evidence.expiresAtEpochSecond() <= now) {
        throw invalid();
      }
      return evidence;
    } catch (RuntimeException invalidCustody) {
      throw invalid();
    }
  }

  private static void requireLoginBinding(
      RuntimePlayerAdmissionEntitlementEvidence evidence,
      CanonicalGameplayLoginResult.NonSecretEvidence login) {
    Object rawSources = evidence.fields().get("sourceReferences");
    if (!(rawSources instanceof Map<?, ?> sources)
        || !login.tokenJti().toString().equals(sources.get("callerTokenJti"))
        || !login.tokenSha256().equals(sources.get("callerTokenSha256"))
        || !login.callerContextId().toString().equals(sources.get("callerContextId"))) {
      throw invalid();
    }
  }

  private static void requireTargetBinding(
      RuntimePlayerAdmissionEntitlementEvidence evidence, FirstPartyConnectContext context) {
    CanonicalPlayableTarget target = context.resolvedTarget();
    Object rawTarget = evidence.fields().get("target");
    Object rawSources = evidence.fields().get("sourceReferences");
    if (!(rawTarget instanceof Map<?, ?> actual)
        || !(rawSources instanceof Map<?, ?> sources)
        || !"PUBLIC_PRODUCTION".equals(actual.get("targetClass"))
        || !"PRESEEDED_ONLY".equals(actual.get("characterCreationPolicy"))
        || !target.targetNamespace().equals(actual.get("targetNamespace"))
        || !target.canonicalTenantId().toString().equals(actual.get("tenantId"))
        || !target.tenantSlug().equals(actual.get("tenantSlug"))
        || !target.worldSlug().equals(actual.get("worldSlug"))
        || !target.realmId().toString().equals(actual.get("realmId"))
        || !target.realmSlug().equals(actual.get("realmSlug"))
        || !target
            .playableStateNamespaceId()
            .toString()
            .equals(actual.get("playableStateNamespaceId"))
        || !target.playableStateScope().equals(actual.get("playableStateScope"))
        || !target.canonicalGameInstanceId().toString().equals(actual.get("gameInstanceId"))
        || !target.canonicalVersionId().toString().equals(actual.get("canonicalVersionId"))
        || !Long.toString(target.catalogRevision()).equals(actual.get("catalogRevision"))
        || !Long.toString(target.pointerVersion()).equals(actual.get("pointerVersion"))
        || !Long.toString(target.activeWorldEpoch()).equals(actual.get("activeWorldEpoch"))
        || !Instant.ofEpochMilli(context.claims().expiresAtMillis())
            .toString()
            .equals(actual.get("connectScopeExpiresAt"))
        || !target
            .admissionPointerSnapshotDigest()
            .equals(sources.get("admissionPointerSnapshotDigest"))
        || !target.ownerProofDigest().equals(sources.get("gameSessionOwnerProofDigest"))) {
      throw invalid();
    }
  }

  private boolean isBoundedAsciiJwt(byte[] bytes) {
    if (bytes == null
        || bytes.length == 0
        || bytes.length > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES) {
      return false;
    }
    for (byte value : bytes) {
      if (value < 0x21 || value > 0x7e) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesSha256(byte[] value, String expected) {
    byte[] digest = null;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(value);
      return HexFormat.of().formatHex(digest).equals(expected);
    } catch (NoSuchAlgorithmException unavailable) {
      return false;
    } finally {
      if (digest != null) {
        Arrays.fill(digest, (byte) 0);
      }
    }
  }

  private static boolean current(BooleanSupplier custodyCurrent) {
    try {
      return custodyCurrent != null && custodyCurrent.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static boolean v4(UUID value) {
    return value != null && value.version() == 4 && value.variant() == 2;
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
  protected AccountPlayerAdmissionEntitlementReadServiceGrpc
          .AccountPlayerAdmissionEntitlementReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return AccountPlayerAdmissionEntitlementReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedAccountPeer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedAccountPeer))
        .withCompression("gzip");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Exact live LOGIN custody and verified player-admission scope required");
  }

  private static StatusRuntimeException failure(Status.Code code) {
    return Status.fromCode(code)
        .withDescription("Account player-admission entitlement read did not complete")
        .asRuntimeException();
  }
}
