package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;

/** Closed wire mapping for exact, complete published realm-entry policy owner reads. */
public final class PublishedRealmEntryPolicyReadGrpcCodec {
  private static final int SCHEMA_VERSION = 1;

  private PublishedRealmEntryPolicyReadGrpcCodec() {}

  public record ResolveRequest(
      String targetNamespace,
      UUID readRequestId,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      String realmSlug) {
    public ResolveRequest {
      requireNamespace(targetNamespace);
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireSlug(worldSlug, "worldSlug");
      requireSlug(realmSlug, "realmSlug");
    }
  }

  public record ListRequest(
      String targetNamespace, UUID readRequestId, UUID canonicalTenantId, UUID canonicalVersionId) {
    public ListRequest {
      requireNamespace(targetNamespace);
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
    }
  }

  public static ResolvePublishedRealmEntryPolicyRequest toRequest(ResolveRequest request) {
    Objects.requireNonNull(request, "request");
    return ResolvePublishedRealmEntryPolicyRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setCanonicalVersionId(request.canonicalVersionId().toString())
        .setWorldSlug(request.worldSlug())
        .setRealmSlug(request.realmSlug())
        .build();
  }

  public static ResolveRequest fromRequest(ResolvePublishedRealmEntryPolicyRequest wire) {
    Objects.requireNonNull(wire, "wire");
    if (!wire.getUnknownFields().asMap().isEmpty() || wire.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Closed schema-1 realm policy resolve request required");
    }
    return new ResolveRequest(
        wire.getTargetNamespace(),
        canonicalUuid(wire.getReadRequestId(), "readRequestId"),
        canonicalUuid(wire.getCanonicalTenantId(), "canonicalTenantId"),
        canonicalUuid(wire.getCanonicalVersionId(), "canonicalVersionId"),
        wire.getWorldSlug(),
        wire.getRealmSlug());
  }

  public static ListPublishedRealmEntryPoliciesRequest toRequest(ListRequest request) {
    Objects.requireNonNull(request, "request");
    return ListPublishedRealmEntryPoliciesRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setCanonicalVersionId(request.canonicalVersionId().toString())
        .build();
  }

  public static ListRequest fromRequest(ListPublishedRealmEntryPoliciesRequest wire) {
    Objects.requireNonNull(wire, "wire");
    if (!wire.getUnknownFields().asMap().isEmpty() || wire.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Closed schema-1 realm policy list request required");
    }
    return new ListRequest(
        wire.getTargetNamespace(),
        canonicalUuid(wire.getReadRequestId(), "readRequestId"),
        canonicalUuid(wire.getCanonicalTenantId(), "canonicalTenantId"),
        canonicalUuid(wire.getCanonicalVersionId(), "canonicalVersionId"));
  }

  public static ResolvePublishedRealmEntryPolicyResponse toResponse(
      ResolveRequest request, PublishedRealmEntryPolicySetEvidence evidence) {
    PublishedRealmEntryPolicySetEvidence complete = verifiedFor(request, evidence);
    requireSelector(complete, request.worldSlug(), request.realmSlug());
    return ResolvePublishedRealmEntryPolicyResponse.newBuilder()
        .setRequest(toRequest(request))
        .setPolicySetEvidence(ByteString.copyFrom(complete.canonicalBytes()))
        .build();
  }

  public static PublishedRealmEntryPolicySetEvidence fromResponse(
      ResolveRequest request, ResolvePublishedRealmEntryPolicyResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getPolicySetEvidence().isEmpty()) {
      throw new IllegalArgumentException("Closed complete realm policy resolve response required");
    }
    ResolveRequest echoed = fromRequest(response.getRequest());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException("Realm policy resolve response changed the exact request");
    }
    PublishedRealmEntryPolicySetEvidence complete =
        PublishedRealmEntryPolicySetEvidence.fromStored(
            response.getPolicySetEvidence().toByteArray());
    requireTarget(request.canonicalTenantId(), request.canonicalVersionId(), complete);
    requireSelector(complete, request.worldSlug(), request.realmSlug());
    return complete;
  }

  public static ListPublishedRealmEntryPoliciesResponse toResponse(
      ListRequest request, PublishedRealmEntryPolicySetEvidence evidence) {
    PublishedRealmEntryPolicySetEvidence complete = verifiedFor(request, evidence);
    return ListPublishedRealmEntryPoliciesResponse.newBuilder()
        .setRequest(toRequest(request))
        .setPolicySetEvidence(ByteString.copyFrom(complete.canonicalBytes()))
        .build();
  }

  public static PublishedRealmEntryPolicySetEvidence fromResponse(
      ListRequest request, ListPublishedRealmEntryPoliciesResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getPolicySetEvidence().isEmpty()) {
      throw new IllegalArgumentException("Closed complete realm policy list response required");
    }
    ListRequest echoed = fromRequest(response.getRequest());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException("Realm policy list response changed the exact request");
    }
    PublishedRealmEntryPolicySetEvidence complete =
        PublishedRealmEntryPolicySetEvidence.fromStored(
            response.getPolicySetEvidence().toByteArray());
    requireTarget(request.canonicalTenantId(), request.canonicalVersionId(), complete);
    return complete;
  }

  private static PublishedRealmEntryPolicySetEvidence verifiedFor(
      ResolveRequest request, PublishedRealmEntryPolicySetEvidence evidence) {
    Objects.requireNonNull(request, "request");
    return verifiedFor(request.canonicalTenantId(), request.canonicalVersionId(), evidence);
  }

  private static PublishedRealmEntryPolicySetEvidence verifiedFor(
      ListRequest request, PublishedRealmEntryPolicySetEvidence evidence) {
    Objects.requireNonNull(request, "request");
    return verifiedFor(request.canonicalTenantId(), request.canonicalVersionId(), evidence);
  }

  private static PublishedRealmEntryPolicySetEvidence verifiedFor(
      UUID tenantId, UUID versionId, PublishedRealmEntryPolicySetEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    PublishedRealmEntryPolicySetEvidence complete =
        PublishedRealmEntryPolicySetEvidence.fromStored(evidence.canonicalBytes());
    requireTarget(tenantId, versionId, complete);
    return complete;
  }

  private static void requireTarget(
      UUID tenantId, UUID versionId, PublishedRealmEntryPolicySetEvidence evidence) {
    if (!evidence.target().canonicalTenantId().equals(tenantId)
        || !evidence.target().canonicalVersionId().equals(versionId)) {
      throw new IllegalArgumentException(
          "Published policy evidence changed the exact owner target");
    }
  }

  private static void requireSelector(
      PublishedRealmEntryPolicySetEvidence evidence, String worldSlug, String realmSlug) {
    if (evidence.policies().stream()
        .noneMatch(
            policy ->
                policy.policy().worldSlug().equals(worldSlug)
                    && policy.policy().realmSlug().equals(realmSlug))) {
      throw new IllegalArgumentException(
          "Published policy set does not contain the exact selector");
    }
  }

  private static UUID canonicalUuid(String value, String name) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException("Canonical non-nil " + name + " required");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      if (invalid instanceof IllegalArgumentException argument
          && argument.getMessage() != null
          && argument.getMessage().startsWith("Canonical non-nil")) {
        throw argument;
      }
      throw new IllegalArgumentException("Canonical non-nil " + name + " required", invalid);
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + name + " required");
    }
  }

  private static void requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical target namespace required");
    }
  }

  private static void requireSlug(String slug, String name) {
    if (!RealmEntryPolicy.isCanonicalSlug(slug)) {
      throw new IllegalArgumentException("Canonical " + name + " required");
    }
  }
}
