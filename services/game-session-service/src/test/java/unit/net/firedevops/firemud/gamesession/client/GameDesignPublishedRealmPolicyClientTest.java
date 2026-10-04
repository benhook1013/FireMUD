package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyKind;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryStateScope;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GameDesignPublishedRealmPolicyClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final long VERSION_ID = 42L;
  private static final int VERSION_NUMBER = 7;
  private static final long SOURCE_GAME_ROW_ID = 91L;
  private static final String SOURCE_GAME_TENANT_KEY = "game-design-tenant-91";
  private static final String WORKFLOW_ID = "publish-42";
  private static final String MANIFEST_HASH = "manifest-hash-42";
  private static final String PROVENANCE_KIND = "NEW_GAME_ROW";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void returnsCompleteTypedEvidenceAndSendsExactRequestWithDeadline() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    ListPublishedRealmEntryPoliciesResponse response = validResponse();
    when(stub.listPublishedRealmEntryPolicies(any())).thenReturn(response);
    GameDesignPublishedRealmPolicyClient client = newClient(stub);

    PublishedRealmEntryPolicySetEvidence result =
        client.listPublishedRealmEntryPolicies(TENANT_ID.toString(), VERSION_ID);

    assertThat(result).isEqualTo(validEvidenceSet());
    assertThat(result.policies()).hasSize(2);
    assertThat(result.policies().getFirst().policy().realmSlug()).isEqualTo("playtest");
    assertThat(result.hasValidDigest(OBJECT_MAPPER)).isTrue();
    org.mockito.ArgumentCaptor<ListPublishedRealmEntryPoliciesRequest> requestCaptor =
        org.mockito.ArgumentCaptor.forClass(ListPublishedRealmEntryPoliciesRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).listPublishedRealmEntryPolicies(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(requestCaptor.getValue().getVersionId()).isEqualTo(VERSION_ID);
  }

  @Test
  void rejectsMalformedTenantOrVersionBeforeStubUse() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignPublishedRealmPolicyClient client = newClientWithoutStub(channelFactory);

    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies("bad", VERSION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.listPublishedRealmEntryPolicies(
                    "00000000-0000-0000-0000-000000000000", VERSION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.listPublishedRealmEntryPolicies(
                    "11111111-1111-4111-8111-11111111111", VERSION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(TENANT_ID.toString(), 0L))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsUninitializedClientAndPropagatesUnavailableOwner() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignPublishedRealmPolicyClient uninitialized = newClientWithoutStub(channelFactory);
    assertThatThrownBy(
            () -> uninitialized.listPublishedRealmEntryPolicies(TENANT_ID.toString(), VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);

    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        Status.UNAVAILABLE.withDescription("Game Design unavailable").asRuntimeException();
    when(stub.listPublishedRealmEntryPolicies(any())).thenThrow(unavailable);
    GameDesignPublishedRealmPolicyClient client = newClient(stub);
    assertThatThrownBy(
            () -> client.listPublishedRealmEntryPolicies(TENANT_ID.toString(), VERSION_ID))
        .isSameAs(unavailable);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsIncompleteOrContradictoryTopLevelOwnerEvidence() throws Exception {
    ListPublishedRealmEntryPoliciesResponse valid = validResponse();
    assertRejected(valid.toBuilder().setSchemaVersion(2).build());
    assertRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertRejected(valid.toBuilder().setTargetNamespace("").build());
    assertRejected(
        valid.toBuilder().setCanonicalTenantId("22222222-2222-4222-8222-222222222222").build());
    assertRejected(valid.toBuilder().setVersionId(VERSION_ID + 1).build());
    assertRejected(valid.toBuilder().setVersionNumber(0).build());
    assertRejected(valid.toBuilder().setPolicyCount(1).build());
    assertRejected(valid.toBuilder().setPolicySetDigest("sha256:" + "0".repeat(64)).build());
    assertRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build());
  }

  @Test
  void rejectsChildSchemaUnknownFieldsEnumsProvenanceSelectorsAndDigests() throws Exception {
    ListPublishedRealmEntryPoliciesResponse valid = validResponse();
    ResolvePublishedRealmEntryPolicyResponse child = valid.getPolicies(0);

    assertRejected(replaceFirst(valid, child.toBuilder().setSchemaVersion(2).build()));
    assertRejected(replaceFirst(valid, child.toBuilder().setTargetNamespace("other").build()));
    assertRejected(
        replaceFirst(
            valid,
            child.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                        .build())
                .build()));
    assertRejected(replaceFirst(valid, child.toBuilder().setStateScopeValue(99).build()));
    assertRejected(replaceFirst(valid, child.toBuilder().setEntryPolicyValue(99).build()));
    assertRejected(
        replaceFirst(valid, child.toBuilder().setTenantIdentityProvenanceKind("OTHER").build()));
    assertRejected(replaceFirst(valid, child.toBuilder().setWorldSlug("wrong-world").build()));
    assertRejected(
        replaceFirst(valid, child.toBuilder().setPolicyDigest("sha256:" + "0".repeat(64)).build()));
    assertRejected(replaceFirst(valid, child.toBuilder().setSourceRevisionId(0L).build()));
  }

  @Test
  void rejectsUnsortedDuplicateAndPartialPolicySets() throws Exception {
    ListPublishedRealmEntryPoliciesResponse valid = validResponse();
    ResolvePublishedRealmEntryPolicyResponse first = valid.getPolicies(0);
    ResolvePublishedRealmEntryPolicyResponse second = valid.getPolicies(1);

    assertRejected(
        valid.toBuilder().clearPolicies().addPolicies(second).addPolicies(first).build());
    assertRejected(valid.toBuilder().clearPolicies().addPolicies(first).addPolicies(first).build());
    assertRejected(valid.toBuilder().removePolicies(1).build());
    assertRejected(valid.toBuilder().setPolicyCount(3).build());
  }

  @Test
  void rejectsResponseFromDifferentMtlSWorkloadNamespace() throws Exception {
    ManagedChannel channel = mock(ManagedChannel.class);
    SSLSession sslSession = mock(SSLSession.class);
    X509Certificate peerCertificate = mock(X509Certificate.class);
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {peerCertificate});
    when(peerCertificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/other/sa/game-design-service")));
    Attributes attributes =
        Attributes.newBuilder().set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    @SuppressWarnings("unchecked")
    ClientCall<ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
        call = mock(ClientCall.class);
    when(call.getAttributes()).thenReturn(attributes);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ClientCall.Listener<ListPublishedRealmEntryPoliciesResponse> listener =
                  invocation.getArgument(0);
              listener.onMessage(validResponse());
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel
            .<ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
                newCall(any(), any()))
        .thenReturn(call);

    GameDesignPublishedRealmPolicyClient client = newClientWithChannel(channel);
    assertThatThrownBy(
            () -> client.listPublishedRealmEntryPolicies(TENANT_ID.toString(), VERSION_ID))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  @Test
  void rejectsPlaintextIncompleteClasspathTlsAndInvalidNamespaceBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    CommonGrpcClientProperties classpath = mtlsProperties();
    classpath.setPrivateKey("classpath:certs/client.key");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, "bad.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(channelFactory);
  }

  private static PublishedRealmEntryPolicySetEvidence validEvidenceSet() {
    List<PublishedRealmEntryPolicyEvidence> policies =
        List.of(
            evidence(
                "00000000-0000-4000-8000-000000000002",
                502L,
                policy("production", true, true, RealmEntryPolicy.StateScope.SHARED)),
            evidence(
                "00000000-0000-4000-8000-000000000001",
                501L,
                policy("playtest", true, false, RealmEntryPolicy.StateScope.ISOLATED)));
    return PublishedRealmEntryPolicySetEvidence.create(
        TENANT_ID,
        VERSION_ID,
        VERSION_NUMBER,
        policies.getFirst().releaseBundleIdentity(),
        WORKFLOW_ID,
        MANIFEST_HASH,
        policies,
        OBJECT_MAPPER);
  }

  private static PublishedRealmEntryPolicyEvidence evidence(
      String policyId, long revisionId, RealmEntryPolicy policy) {
    return PublishedRealmEntryPolicyEvidence.create(
        UUID.fromString(policyId),
        TENANT_ID,
        PROVENANCE_KIND,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        VERSION_ID,
        VERSION_NUMBER,
        revisionId,
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            TENANT_ID, VERSION_ID, WORKFLOW_ID, MANIFEST_HASH, OBJECT_MAPPER),
        WORKFLOW_ID,
        MANIFEST_HASH,
        policy,
        OBJECT_MAPPER);
  }

  private static RealmEntryPolicy policy(
      String realmSlug,
      boolean visible,
      boolean publicProduction,
      RealmEntryPolicy.StateScope scope) {
    String json =
        "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"publicProduction\":"
            + publicProduction
            + ",\"realmDisplayName\":"
            + quote(realmSlug.equals("production") ? "Production" : "Playtest")
            + ",\"realmSlug\":"
            + quote(realmSlug)
            + ",\"schemaVersion\":1,\"stateScope\":"
            + quote(scope.name())
            + ",\"visible\":"
            + visible
            + ",\"worldDisplayName\":\"FireMUD\",\"worldSlug\":\"firemud\"}";
    return RealmEntryPolicy.parse(json, OBJECT_MAPPER);
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static ListPublishedRealmEntryPoliciesResponse validResponse() {
    PublishedRealmEntryPolicySetEvidence evidence = validEvidenceSet();
    ListPublishedRealmEntryPoliciesResponse.Builder response =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setCanonicalTenantId(TENANT_ID.toString())
            .setVersionId(VERSION_ID)
            .setVersionNumber(VERSION_NUMBER)
            .setReleaseBundleIdentity(evidence.releaseBundleIdentity())
            .setPublishWorkflowId(evidence.publishWorkflowId())
            .setManifestHash(evidence.manifestHash())
            .setPolicyCount(evidence.policies().size())
            .setPolicySetDigest(evidence.policySetDigest());
    evidence.policies().stream()
        .map(GameDesignPublishedRealmPolicyClientTest::toWire)
        .forEach(response::addPolicies);
    return response.build();
  }

  private static ResolvePublishedRealmEntryPolicyResponse toWire(
      PublishedRealmEntryPolicyEvidence evidence) {
    return ResolvePublishedRealmEntryPolicyResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setVersionId(evidence.versionId())
        .setVersionNumber(evidence.versionNumber())
        .setPolicyId(evidence.policyId().toString())
        .setSourceRevisionId(evidence.sourceRevisionId())
        .setSourceGameRowId(evidence.sourceGameRowId())
        .setSourceGameTenantKey(evidence.sourceGameTenantKey())
        .setTenantIdentityProvenanceKind(evidence.tenantIdentityProvenanceKind())
        .setReleaseBundleIdentity(evidence.releaseBundleIdentity())
        .setPublishWorkflowId(evidence.publishWorkflowId())
        .setManifestHash(evidence.manifestHash())
        .setWorldSlug(evidence.policy().worldSlug())
        .setWorldDisplayName(evidence.policy().worldDisplayName())
        .setRealmSlug(evidence.policy().realmSlug())
        .setRealmDisplayName(evidence.policy().realmDisplayName())
        .setVisible(evidence.policy().visible())
        .setPublicProduction(evidence.policy().publicProduction())
        .setStateScope(
            evidence.policy().stateScope() == RealmEntryPolicy.StateScope.SHARED
                ? PublishedRealmEntryStateScope.PUBLISHED_REALM_ENTRY_STATE_SCOPE_SHARED
                : PublishedRealmEntryStateScope.PUBLISHED_REALM_ENTRY_STATE_SCOPE_ISOLATED)
        .setEntryPolicy(
            PublishedRealmEntryPolicyKind.PUBLISHED_REALM_ENTRY_POLICY_KIND_PRESEEDED_ONLY)
        .setPolicyJson(evidence.policy().canonicalJson())
        .setPolicyDigest(evidence.policyDigest())
        .build();
  }

  private static ListPublishedRealmEntryPoliciesResponse replaceFirst(
      ListPublishedRealmEntryPoliciesResponse response,
      ResolvePublishedRealmEntryPolicyResponse replacement) {
    return response.toBuilder().setPolicies(0, replacement).build();
  }

  private static void assertRejected(ListPublishedRealmEntryPoliciesResponse response)
      throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.listPublishedRealmEntryPolicies(any())).thenReturn(response);
    GameDesignPublishedRealmPolicyClient client = newClient(stub);
    assertThatThrownBy(
            () -> client.listPublishedRealmEntryPolicies(TENANT_ID.toString(), VERSION_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GameDesignPublishedRealmPolicyClient newClient(
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub) throws Exception {
    GameDesignPublishedRealmPolicyClient client =
        newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static GameDesignPublishedRealmPolicyClient newClientWithChannel(ManagedChannel channel)
      throws Exception {
    GameDesignPublishedRealmPolicyClient client =
        newClientWithoutStub(mock(GrpcChannelFactory.class));
    var buildStub =
        GameDesignPublishedRealmPolicyClient.class.getDeclaredMethod(
            "buildStub", ManagedChannel.class);
    buildStub.setAccessible(true);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, buildStub.invoke(client, channel));
    return client;
  }

  private static GameDesignPublishedRealmPolicyClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new GameDesignPublishedRealmPolicyClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub mockStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/game-design-ca.crt");
    return tls;
  }
}
