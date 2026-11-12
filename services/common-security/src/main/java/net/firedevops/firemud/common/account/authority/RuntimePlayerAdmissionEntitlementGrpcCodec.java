package net.firedevops.firemud.common.account.authority;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.account.v1.PlayerAdmissionEntitlementReadResponseBinding;
import net.firedevops.firemud.account.v1.PlayerAdmissionEntitlementReadSelectors;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/** Closed comparison and content mapping; this codec authenticates neither callers nor sources. */
public final class RuntimePlayerAdmissionEntitlementGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 40 * 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      RuntimePlayerAdmissionEntitlementEvidence.MAX_CANONICAL_BYTES + MAX_REQUEST_WIRE_BYTES + 32;
  public static final long CALL_DEADLINE_SECONDS = 45L;

  private RuntimePlayerAdmissionEntitlementGrpcCodec() {}

  public record Request(RetainedTargetSelector target, String connectScopeId) {
    public Request {
      Objects.requireNonNull(target, "Exact retained target selectors required");
      if (connectScopeId == null
          || connectScopeId.isBlank()
          || connectScopeId.length()
              > RuntimePlayerAdmissionEntitlementEvidence.MAX_CONNECT_SCOPE_CHARS) throw invalid();
    }

    public String expectedGameSessionWorkload() {
      return "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service";
    }

    @Override
    public String toString() {
      return "PlayerAdmissionEntitlementSelectors[redacted]";
    }
  }

  /** Process-local credential carrier only; never include in logs or a persisted operation. */
  public record PresentedRequest(Request request, String initialLoginJwt) {
    public PresentedRequest {
      Objects.requireNonNull(request);
      if (initialLoginJwt == null
          || initialLoginJwt.isBlank()
          || initialLoginJwt.length() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          || !initialLoginJwt.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
        throw invalid();
    }

    @Override
    public String toString() {
      return "PlayerAdmissionEntitlementCredential[redacted]";
    }
  }

  public static ReadPlayerAdmissionEntitlementRequest toRequest(
      Request request, String initialLoginJwt) {
    new PresentedRequest(request, initialLoginJwt);
    var wire =
        ReadPlayerAdmissionEntitlementRequest.newBuilder()
            .addSelectors(selectors(request))
            .addInitialLoginJwt(initialLoginJwt)
            .build();
    if (wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) throw invalid();
    return wire;
  }

  public static PresentedRequest parseRequest(ReadPlayerAdmissionEntitlementRequest wire) {
    if (wire == null
        || wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    return new PresentedRequest(
        parseSelectors(one(wire.getSelectorsList())), one(wire.getInitialLoginJwtList()));
  }

  public static ReadPlayerAdmissionEntitlementResponse toResponse(
      Request request, RuntimePlayerAdmissionEntitlementEvidence evidence) {
    requireBinding(request, evidence);
    var wire =
        ReadPlayerAdmissionEntitlementResponse.newBuilder()
            .addBinding(
                PlayerAdmissionEntitlementReadResponseBinding.newBuilder()
                    .addSchemaVersion(2)
                    .addTarget(
                        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(
                            request.target())))
            .addCanonicalEvidence(ByteString.copyFrom(evidence.canonicalBytes()))
            .build();
    if (wire.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) throw invalid();
    return wire;
  }

  public static RuntimePlayerAdmissionEntitlementEvidence requireResponse(
      Request expected, ReadPlayerAdmissionEntitlementResponse wire) {
    Objects.requireNonNull(expected);
    if (wire == null
        || wire.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    var binding = one(wire.getBindingList());
    if (!binding.getUnknownFields().asMap().isEmpty()
        || one(binding.getSchemaVersionList()) != 2
        || !expected
            .target()
            .equals(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
                    one(binding.getTargetList())))) throw invalid();
    var evidence =
        RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(
            one(wire.getCanonicalEvidenceList()).toByteArray());
    requireBinding(expected, evidence);
    return evidence;
  }

  private static PlayerAdmissionEntitlementReadSelectors selectors(Request request) {
    return PlayerAdmissionEntitlementReadSelectors.newBuilder()
        .addSchemaVersion(1)
        .addTarget(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(
                request.target()))
        .addConnectScopeId(request.connectScopeId())
        .build();
  }

  private static Request parseSelectors(PlayerAdmissionEntitlementReadSelectors wire) {
    if (wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()
        || one(wire.getSchemaVersionList()) != 1) throw invalid();
    return new Request(
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
            one(wire.getTargetList())),
        one(wire.getConnectScopeIdList()));
  }

  private static void requireBinding(
      Request request, RuntimePlayerAdmissionEntitlementEvidence evidence) {
    Map<String, Object> fields = Objects.requireNonNull(evidence).fields();
    var target = request.target();
    if (!target.accountId().toString().equals(fields.get("callerAccountId"))
        || !target.tenantId().toString().equals(fields.get("tenantId"))
        || !target.readRequestId().toString().equals(fields.get("requestId"))
        || !request.expectedGameSessionWorkload().equals(fields.get("callerWorkload"))
        || !RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(
                request.connectScopeId())
            .equals(evidence.verifiedConnectScopeTokenHash())) throw invalid();
    if (!(fields.get("target") instanceof Map<?, ?> actual)
        || !target.targetNamespace().equals(actual.get("targetNamespace"))
        || !target.tenantId().toString().equals(actual.get("tenantId"))
        || !target.tenantSlug().equals(actual.get("tenantSlug"))
        || !target.worldSlug().equals(actual.get("worldSlug"))
        || !target.realmId().toString().equals(actual.get("realmId"))
        || !target.realmSlug().equals(actual.get("realmSlug"))
        || !target
            .playableStateNamespaceId()
            .toString()
            .equals(actual.get("playableStateNamespaceId"))
        || !target.playableStateScope().equals(actual.get("playableStateScope"))
        || !target.gameInstanceId().toString().equals(actual.get("gameInstanceId"))
        || !Long.toString(target.catalogRevision()).equals(actual.get("catalogRevision"))
        || !Long.toString(target.pointerVersion()).equals(actual.get("pointerVersion")))
      throw invalid();
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Exact bounded player-admission entitlement evidence required");
  }
}
