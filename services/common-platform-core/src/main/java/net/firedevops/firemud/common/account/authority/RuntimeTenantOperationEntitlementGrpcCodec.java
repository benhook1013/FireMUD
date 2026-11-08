package net.firedevops.firemud.common.account.authority;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed wire mapping only; canonical content and selectors do not authenticate their source. */
public final class RuntimeTenantOperationEntitlementGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      RuntimeTenantOperationEntitlementEvidence.MAX_CANONICAL_BYTES + MAX_REQUEST_WIRE_BYTES + 32;
  public static final long CALL_DEADLINE_SECONDS = 5L;

  private RuntimeTenantOperationEntitlementGrpcCodec() {}

  public enum SourceKind {
    PAID_HOSTING,
    NON_PAID_DEMO
  }

  /** Comparison selectors, never an assertion of authenticated caller identity. */
  public record Request(
      String targetNamespace, UUID tenantId, UUID requestId, SourceKind sourceKind) {
    public Request {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical workload namespace required");
      }
      nonNil(tenantId);
      nonNil(requestId);
      Objects.requireNonNull(sourceKind, "Explicit entitlement source required");
    }

    public String expectedWorldWorkload() {
      return "spiffe://firemud/ns/" + targetNamespace + "/sa/world-management-service";
    }
  }

  public static ReadTenantOperationEntitlementRequest toRequest(Request request) {
    Objects.requireNonNull(request);
    return ReadTenantOperationEntitlementRequest.newBuilder()
        .addSchemaVersion(1)
        .addTargetNamespace(request.targetNamespace())
        .addTenantId(request.tenantId().toString())
        .addRequestId(request.requestId().toString())
        .addSourceKind(request.sourceKind().name())
        .build();
  }

  public static Request parseRequest(ReadTenantOperationEntitlementRequest wire) {
    if (wire == null
        || wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()
        || one(wire.getSchemaVersionList()) != 1) {
      throw new IllegalArgumentException("Exact bounded tenant-operation request required");
    }
    return new Request(
        one(wire.getTargetNamespaceList()), uuid(one(wire.getTenantIdList())),
        uuid(one(wire.getRequestIdList())), SourceKind.valueOf(one(wire.getSourceKindList())));
  }

  public static ReadTenantOperationEntitlementResponse toResponse(
      Request request, RuntimeTenantOperationEntitlementEvidence evidence) {
    requireBinding(request, evidence);
    var response =
        ReadTenantOperationEntitlementResponse.newBuilder()
            .addRequest(toRequest(request))
            .addCanonicalEvidence(ByteString.copyFrom(evidence.canonicalBytes()))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalArgumentException("Tenant-operation response exceeds wire bound");
    }
    return response;
  }

  public static RuntimeTenantOperationEntitlementEvidence requireResponse(
      Request expected, ReadTenantOperationEntitlementResponse wire) {
    Objects.requireNonNull(expected);
    if (wire == null
        || wire.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()
        || !expected.equals(parseRequest(one(wire.getRequestList())))) {
      throw new IllegalArgumentException("Exact bounded tenant-operation response required");
    }
    var evidence =
        RuntimeTenantOperationEntitlementEvidence.parseCanonical(
            one(wire.getCanonicalEvidenceList()).toByteArray());
    requireBinding(expected, evidence);
    return evidence;
  }

  private static void requireBinding(
      Request request, RuntimeTenantOperationEntitlementEvidence evidence) {
    Objects.requireNonNull(request);
    Map<String, Object> fields = Objects.requireNonNull(evidence).fields();
    if (!request.tenantId().toString().equals(fields.get("tenantId"))
        || !request.requestId().toString().equals(fields.get("requestId"))
        || !request.sourceKind().name().equals(fields.get("sourceKind"))
        || !request.expectedWorldWorkload().equals(fields.get("callerWorkload"))) {
      throw new IllegalArgumentException(
          "Tenant-operation evidence differs from exact request/workload");
    }
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1)
      throw new IllegalArgumentException("Exactly one transport member required");
    return values.getFirst();
  }

  private static UUID uuid(String text) {
    UUID value = UUID.fromString(text);
    nonNil(value);
    if (!value.toString().equals(text))
      throw new IllegalArgumentException("Canonical UUID required");
    return value;
  }

  private static void nonNil(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Non-nil UUID required");
    }
  }
}
