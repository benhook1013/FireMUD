package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyKind;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryStateScope;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import tools.jackson.databind.ObjectMapper;

/** Unwired Game Session receiver for complete immutable Game Design realm-policy sets. */
public final class GameDesignPublishedRealmPolicyClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;

  public GameDesignPublishedRealmPolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProps),
        channelFactory,
        GameDesignPublishedRealmPolicyClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the owner client only when an explicit caller owns this handoff. */
  public void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return TenantIdentityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service"))
        .withCompression("gzip");
  }

  /** Reads and verifies the complete bounded owner set for an exact published tenant version. */
  public PublishedRealmEntryPolicySetEvidence listPublishedRealmEntryPolicies(
      String canonicalTenantId, long versionId) {
    UUID requestedTenantId = parseCanonicalNonNilUuid(canonicalTenantId, "canonical tenant ID");
    if (versionId <= 0) {
      throw new IllegalArgumentException("Published version ID must be positive");
    }
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException(
          "Game Design published realm policy client is not initialized");
    }

    ListPublishedRealmEntryPoliciesResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .listPublishedRealmEntryPolicies(
                ListPublishedRealmEntryPoliciesRequest.newBuilder()
                    .setCanonicalTenantId(requestedTenantId.toString())
                    .setVersionId(versionId)
                    .build());
    if (response == null) {
      throw invalidResponse("is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidResponse("contains unsupported fields");
    }
    if (response.getSchemaVersion() != RealmEntryPolicy.SCHEMA_VERSION
        || !workloadNamespace.equals(response.getTargetNamespace())
        || !requestedTenantId.toString().equals(response.getCanonicalTenantId())
        || response.getVersionId() != versionId
        || response.getVersionNumber() <= 0) {
      throw invalidResponse("does not match the exact schema, owner namespace, tenant, or version");
    }
    String expectedReleaseBundleIdentity;
    try {
      expectedReleaseBundleIdentity =
          PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
              requestedTenantId,
              versionId,
              response.getPublishWorkflowId(),
              response.getManifestHash(),
              OBJECT_MAPPER);
    } catch (IllegalArgumentException exception) {
      throw invalidResponse("has incomplete release-bundle workflow or manifest identity");
    }
    if (!expectedReleaseBundleIdentity.equals(response.getReleaseBundleIdentity())) {
      throw invalidResponse("release-bundle identity does not match its owner evidence");
    }

    int policyCount = response.getPolicyCount();
    if (policyCount <= 0
        || policyCount > PublishedRealmEntryPolicySetEvidence.MAX_POLICIES
        || response.getPoliciesCount() != policyCount) {
      throw invalidResponse("does not contain the declared complete bounded policy set");
    }

    List<PublishedRealmEntryPolicyEvidence> policies = new ArrayList<>(policyCount);
    String previousWorldSlug = null;
    String previousRealmSlug = null;
    for (ResolvePublishedRealmEntryPolicyResponse child : response.getPoliciesList()) {
      if (!child.getUnknownFields().asMap().isEmpty()) {
        throw invalidResponse("contains a policy with unsupported fields");
      }
      PublishedRealmEntryPolicyEvidence evidence = toEvidence(child, response, requestedTenantId);
      String worldSlug = evidence.policy().worldSlug();
      String realmSlug = evidence.policy().realmSlug();
      if (previousWorldSlug != null
          && (previousWorldSlug.compareTo(worldSlug) > 0
              || (previousWorldSlug.equals(worldSlug)
                  && previousRealmSlug.compareTo(realmSlug) >= 0))) {
        throw invalidResponse("policy selectors are not in canonical unique order");
      }
      previousWorldSlug = worldSlug;
      previousRealmSlug = realmSlug;
      policies.add(evidence);
    }

    try {
      PublishedRealmEntryPolicySetEvidence setEvidence =
          PublishedRealmEntryPolicySetEvidence.create(
              requestedTenantId,
              versionId,
              response.getVersionNumber(),
              response.getReleaseBundleIdentity(),
              response.getPublishWorkflowId(),
              response.getManifestHash(),
              policies,
              OBJECT_MAPPER);
      if (!setEvidence.policySetDigest().equals(response.getPolicySetDigest())) {
        throw invalidResponse("policy-set digest does not match the complete owner evidence");
      }
      return setEvidence.requireValidDigest(OBJECT_MAPPER);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design published realm policy response is invalid", exception);
    }
  }

  private PublishedRealmEntryPolicyEvidence toEvidence(
      ResolvePublishedRealmEntryPolicyResponse child,
      ListPublishedRealmEntryPoliciesResponse response,
      UUID requestedTenantId) {
    if (child.getSchemaVersion() != RealmEntryPolicy.SCHEMA_VERSION
        || !workloadNamespace.equals(child.getTargetNamespace())
        || !requestedTenantId.toString().equals(child.getCanonicalTenantId())
        || child.getVersionId() != response.getVersionId()
        || child.getVersionNumber() != response.getVersionNumber()
        || !response.getReleaseBundleIdentity().equals(child.getReleaseBundleIdentity())
        || !response.getPublishWorkflowId().equals(child.getPublishWorkflowId())
        || !response.getManifestHash().equals(child.getManifestHash())
        || child.getSourceRevisionId() <= 0
        || child.getSourceGameRowId() <= 0) {
      throw invalidResponse("contains a policy with contradictory owner or version evidence");
    }

    PublishedRealmEntryStateScope wireStateScope =
        PublishedRealmEntryStateScope.forNumber(child.getStateScopeValue());
    RealmEntryPolicy.StateScope stateScope =
        switch (wireStateScope == null
            ? PublishedRealmEntryStateScope.UNRECOGNIZED
            : wireStateScope) {
          case PUBLISHED_REALM_ENTRY_STATE_SCOPE_SHARED -> RealmEntryPolicy.StateScope.SHARED;
          case PUBLISHED_REALM_ENTRY_STATE_SCOPE_ISOLATED -> RealmEntryPolicy.StateScope.ISOLATED;
          default -> throw invalidResponse("contains an unsupported state-scope enum");
        };
    PublishedRealmEntryPolicyKind wireEntryPolicy =
        PublishedRealmEntryPolicyKind.forNumber(child.getEntryPolicyValue());
    if (wireEntryPolicy
        != PublishedRealmEntryPolicyKind.PUBLISHED_REALM_ENTRY_POLICY_KIND_PRESEEDED_ONLY) {
      throw invalidResponse("contains an unsupported entry-policy enum");
    }

    RealmEntryPolicy policy;
    try {
      policy = RealmEntryPolicy.parseCanonical(child.getPolicyJson(), OBJECT_MAPPER);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design published realm policy response contains invalid canonical policy JSON",
          exception);
    }
    if (!policy.worldSlug().equals(child.getWorldSlug())
        || !policy.worldDisplayName().equals(child.getWorldDisplayName())
        || !policy.realmSlug().equals(child.getRealmSlug())
        || !policy.realmDisplayName().equals(child.getRealmDisplayName())
        || policy.visible() != child.getVisible()
        || policy.publicProduction() != child.getPublicProduction()
        || policy.stateScope() != stateScope
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY) {
      throw invalidResponse("contains selector or enum fields that contradict its policy JSON");
    }

    PublishedRealmEntryPolicyEvidence evidence;
    try {
      evidence =
          PublishedRealmEntryPolicyEvidence.create(
              parseCanonicalNonNilUuid(child.getPolicyId(), "policy ID"),
              requestedTenantId,
              child.getTenantIdentityProvenanceKind(),
              child.getSourceGameRowId(),
              child.getSourceGameTenantKey(),
              response.getVersionId(),
              response.getVersionNumber(),
              child.getSourceRevisionId(),
              response.getReleaseBundleIdentity(),
              response.getPublishWorkflowId(),
              response.getManifestHash(),
              policy,
              OBJECT_MAPPER);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design published realm policy response contains invalid owner provenance",
          exception);
    }
    if (!evidence.policyDigest().equals(child.getPolicyDigest())) {
      throw invalidResponse("policy digest does not match the owner and selector evidence");
    }
    return evidence.requireValidDigest(OBJECT_MAPPER);
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
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static IllegalStateException invalidResponse(String detail) {
    return new IllegalStateException("Game Design published realm policy response " + detail);
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Published realm policy reads require Game Session workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Published realm policy reads require Game Session certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Published realm policy reads require file-backed Game Session workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
