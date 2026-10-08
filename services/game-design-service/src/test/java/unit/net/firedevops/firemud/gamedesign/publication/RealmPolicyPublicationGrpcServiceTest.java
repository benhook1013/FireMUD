package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationGrpcService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter tests use isolated owner evidence and context identities, not DB or physical mTLS proof.
 */
class RealmPolicyPublicationGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void rejectsAbsentWrongNamespaceAndEndUserPeersBeforeDecodeOrOwnerAccess() {
    var owner = mock(RealmPolicyPublicationService.class);
    var handler = new RealmPolicyPublicationGrpcService(owner, NAMESPACE);
    var malformed = ListPublishedRealmEntryPoliciesRequest.getDefaultInstance();

    assertThat(callList(handler, malformed, null).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(callList(handler, malformed, peer("account-service", NAMESPACE)).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(callList(handler, malformed, peer("game-session-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    try {
      assertThat(callList(handler, malformed, peer("game-session-service", NAMESPACE)).error)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void malformedAndWrongRequestNamespacesAreDeniedBeforeOwnerAccess() {
    var owner = mock(RealmPolicyPublicationService.class);
    var handler = new RealmPolicyPublicationGrpcService(owner, NAMESPACE);
    var peer = peer("game-session-service", NAMESPACE);
    var malformed =
        ListPublishedRealmEntryPoliciesRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId("00000000-0000-0000-0000-000000000000")
            .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
            .setCanonicalVersionId("22222222-2222-4222-8222-222222222222")
            .build();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var unknownRequest =
        ListPublishedRealmEntryPoliciesRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId("99999999-9999-4999-8999-999999999999")
            .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
            .setCanonicalVersionId("22222222-2222-4222-8222-222222222222")
            .setUnknownFields(unknown)
            .build();
    var wrongNamespace =
        ListPublishedRealmEntryPoliciesRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace("other")
            .setReadRequestId("99999999-9999-4999-8999-999999999999")
            .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
            .setCanonicalVersionId("22222222-2222-4222-8222-222222222222")
            .build();

    assertThat(callList(handler, malformed, peer).error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(callList(handler, unknownRequest, peer).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(callList(handler, wrongNamespace, peer).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void readsAndReturnsTheCompleteSetAndDoesNotFallBackForAnAbsentSelector() throws Exception {
    var fixture = publishedSet();
    var owner = mock(RealmPolicyPublicationService.class);
    when(owner.readPublishedSet(
            fixture.target().canonicalTenantId(), fixture.target().canonicalVersionId()))
        .thenReturn(Optional.of(fixture.set()));
    var handler = new RealmPolicyPublicationGrpcService(owner, NAMESPACE);
    var peer = peer("game-session-service", NAMESPACE);

    var listRequest = listRequest(fixture);
    var listedWire =
        callList(handler, PublishedRealmEntryPolicyReadGrpcCodec.toRequest(listRequest), peer);
    assertThat(listedWire.error).isNull();
    var listed =
        PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(listRequest, listedWire.response);
    assertThat(listed.target()).isEqualTo(fixture.target());
    assertThat(listed.policyCount()).isEqualTo(1);
    assertThat(listed.policies()).hasSize(1);
    assertThat(listed.policySetDigest()).isEqualTo(fixture.set().policySetDigest());
    assertThat(listed.operationBytes()).isEqualTo(fixture.set().operationBytes());
    assertThat(listed.captureBytes()).isEqualTo(fixture.set().captureBytes());
    assertThat(listed.terminalEvidenceBytes()).isEqualTo(fixture.set().terminalEvidenceBytes());

    var resolveRequest = resolveRequest(fixture, "main");
    var resolvedWire =
        callResolve(
            handler, PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolveRequest), peer);
    assertThat(resolvedWire.error).isNull();
    var resolved =
        PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(resolveRequest, resolvedWire.response);
    assertThat(resolved).isEqualTo(listed);

    var missingSelector = resolveRequest(fixture, "absent");
    var missing =
        callResolve(
            handler, PublishedRealmEntryPolicyReadGrpcCodec.toRequest(missingSelector), peer);
    assertThat(missing.error).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.response).isNull();
    verify(owner, org.mockito.Mockito.times(3))
        .readPublishedSet(
            fixture.target().canonicalTenantId(), fixture.target().canonicalVersionId());
  }

  @Test
  void absentOwnerEvidenceUnavailableStorageAndContradictoryEvidenceHaveDistinctStatuses()
      throws Exception {
    var fixture = publishedSet();
    var request = PublishedRealmEntryPolicyReadGrpcCodec.toRequest(listRequest(fixture));
    var peer = peer("game-session-service", NAMESPACE);
    var owner = mock(RealmPolicyPublicationService.class);
    var handler = new RealmPolicyPublicationGrpcService(owner, NAMESPACE);

    when(owner.readPublishedSet(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());
    assertThat(callList(handler, request, peer).error).isEqualTo(Status.Code.NOT_FOUND);

    when(owner.readPublishedSet(any(UUID.class), any(UUID.class)))
        .thenThrow(new org.jooq.exception.DataAccessException("ISOLATED storage unavailable"));
    assertThat(callList(handler, request, peer).error).isEqualTo(Status.Code.UNAVAILABLE);

    when(owner.readPublishedSet(any(UUID.class), any(UUID.class)))
        .thenThrow(new IllegalStateException("ISOLATED contradictory owner evidence"));
    assertThat(callList(handler, request, peer).error).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  static PublishedRealmEntryPolicyReadGrpcCodec.ListRequest listRequest(Fixture fixture) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
        NAMESPACE,
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId());
  }

  static PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest resolveRequest(
      Fixture fixture, String realmSlug) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest(
        NAMESPACE,
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId(),
        "earth",
        realmSlug);
  }

  static Fixture publishedSet() throws Exception {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            42L,
            "tenant-key",
            7L,
            "tenant-key",
            "NEW_GAME_ROW");
    GameDesignPublicationOperation operation = IsolatedPublicationOperationFixtures.fresh(target);
    var selection = operation.account().input().selection();
    var source =
        new RealmPolicySource.Policy(
            selection.selectedCommit().commitId(),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "main-policy-v1",
            RealmEntryPolicy.parse(
                "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                    + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                    + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                    + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                JSON));
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, release(operation), 6L);
    byte[] capture = capture(operation, source, "43");
    String policyDigest =
        RealmPolicyPublishedEvidence.policyDigest(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            target,
            terminal.releaseContent().versionNumber(),
            terminal.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            terminal.releaseContent().publishWorkflowId(),
            terminal.releaseContent().manifestHash(),
            source);
    var policy =
        new RealmPolicyPublishedEvidence.Policy(
            UUID.fromString("44444444-4444-4444-8444-444444444444"), source, policyDigest);
    var policies = List.of(policy);
    String setDigest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            target,
            terminal.releaseContent().versionNumber(),
            selection.selectedCommit().commitId(),
            "43",
            terminal.releaseContent().publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            terminal.releaseContent().publishWorkflowId(),
            terminal.releaseContent().manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies);
    var ownerSet =
        new RealmPolicyPublishedEvidence.PublishedSet(
            target,
            terminal.releaseContent().versionNumber(),
            selection.selectedCommit().commitId(),
            "43",
            terminal.releaseContent().publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            terminal.releaseContent().publishWorkflowId(),
            terminal.releaseContent().manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            1,
            setDigest,
            policies);
    return new Fixture(target, ownerSet);
  }

  private static byte[] capture(
      GameDesignPublicationOperation operation, RealmPolicySource.Policy source, String epoch)
      throws IOException {
    var policy = JSON.readTree(source.policy().canonicalJson());
    var snapshot =
        Map.of(
            "schema", PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA,
            "bindingJson", operation.account().input().selection().selectedCommit().canonicalJson(),
            "bindingDigest", operation.account().input().selection().selectedCommit().digest(),
            "sourceEpoch", epoch,
            "policies",
                List.of(
                    Map.of(
                        "commitId", source.commitId().toString(),
                        "revisionId", source.revisionId().toString(),
                        "logicalRevisionId", source.logicalRevisionId(),
                        "policy", policy)));
    byte[] snapshotBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot));
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(capture, snapshotBytes);
    return capture.toByteArray();
  }

  private static ReleaseContent release(GameDesignPublicationOperation operation) {
    var request = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        request.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? request.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        owner.equals("GAME_LOGIC") ? DIGEST : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        "bundle:exact",
        12,
        "v2",
        request.publishWorkflowId(),
        DIGEST,
        1,
        List.of(
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "asset-é", "FILE", "artifacts/sha256/" + "a".repeat(64), DIGEST, "text/plain", 1)),
        List.of("asset-é"),
        participants,
        List.of("LOOK"),
        "generation-1",
        operation.world());
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static ListCollector callList(
      RealmPolicyPublicationGrpcService handler,
      ListPublishedRealmEntryPoliciesRequest request,
      GrpcPeerIdentity peer) {
    var observer = new ListCollector();
    withPeer(peer, () -> handler.listPublishedRealmEntryPolicies(request, observer));
    return observer;
  }

  private static ResolveCollector callResolve(
      RealmPolicyPublicationGrpcService handler,
      ResolvePublishedRealmEntryPolicyRequest request,
      GrpcPeerIdentity peer) {
    var observer = new ResolveCollector();
    withPeer(peer, () -> handler.resolvePublishedRealmEntryPolicy(request, observer));
    return observer;
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  record Fixture(
      DraftCommitBinding.TargetProof target, RealmPolicyPublishedEvidence.PublishedSet set) {}

  private static final class ListCollector
      implements StreamObserver<ListPublishedRealmEntryPoliciesResponse> {
    private ListPublishedRealmEntryPoliciesResponse response;
    private Status.Code error;

    @Override
    public void onNext(ListPublishedRealmEntryPoliciesResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  private static final class ResolveCollector
      implements StreamObserver<ResolvePublishedRealmEntryPolicyResponse> {
    private ResolvePublishedRealmEntryPolicyResponse response;
    private Status.Code error;

    @Override
    public void onNext(ResolvePublishedRealmEntryPolicyResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }
}
