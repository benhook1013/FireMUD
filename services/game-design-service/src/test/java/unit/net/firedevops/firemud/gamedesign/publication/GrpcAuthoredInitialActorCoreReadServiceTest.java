package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.publication.AuthoredInitialActorCoreReadService;
import net.firedevops.firemud.gamedesign.publication.GrpcAuthoredInitialActorCoreReadService;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreRequest;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;
import org.junit.jupiter.api.Test;

class GrpcAuthoredInitialActorCoreReadServiceTest {
  private final AuthoredInitialActorCoreReadService owner =
      mock(AuthoredInitialActorCoreReadService.class);
  private final GrpcAuthoredInitialActorCoreReadService service =
      new GrpcAuthoredInitialActorCoreReadService(owner, "world-runtime");

  @Test
  void rejectsMissingWorkloadIdentityBeforeDecodingOrCallingOwner() {
    var observer = new Collector();
    service.readAuthoredInitialActorCore(
        ReadAuthoredInitialActorCoreRequest.getDefaultInstance(), observer);
    assertThat(observer.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsOtherWorkloadsBeforeDecodingOrCallingOwner() {
    var observer = invokeAs("account-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void permitsOnlyExactSameNamespaceGameSessionAndEntityIdentities() {
    for (String workload : new String[] {"game-session-service", "entity-management-service"}) {
      var observer = invokeAs(workload);
      assertThat(observer.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void mapsInvalidOwnerResultToSanitizedInternalStatus() throws Exception {
    when(owner.read(any(ReadRequest.class)))
        .thenThrow(new IllegalArgumentException("private database and identity details"));

    var observer = invokeAs("game-session-service", validRequest());

    assertThat(observer.error).isEqualTo(Status.Code.INTERNAL);
    assertThat(observer.errorDescription)
        .isEqualTo("Game Design returned invalid authored-core owner evidence")
        .doesNotContain("private database", "identity details");
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verify(owner, times(1)).read(any(ReadRequest.class));
  }

  private Collector invokeAs(String workload) {
    return invokeAs(workload, ReadAuthoredInitialActorCoreRequest.getDefaultInstance());
  }

  private Collector invokeAs(String workload, ReadAuthoredInitialActorCoreRequest request) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/world-runtime/sa/" + workload, "world-runtime", workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      var observer = new Collector();
      service.readAuthoredInitialActorCore(request, observer);
      return observer;
    } finally {
      context.detach(previous);
    }
  }

  private static ReadAuthoredInitialActorCoreRequest validRequest() throws Exception {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID actor = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    UUID issuanceId = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
    UUID tokenJti = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
    UUID version = UUID.fromString("02222222-2222-4222-8222-222222222222");
    UUID commit = UUID.fromString("03333333-3333-4333-8333-333333333333");
    UUID readId = UUID.fromString("01111111-1111-4111-8111-111111111111");
    UUID attempt = UUID.fromString("04444444-4444-4444-8444-444444444444");
    String namespace = "world-runtime";
    String digest = "sha256:" + "a".repeat(64);
    String publishWorkflow = "selected-publication-workflow";
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "actor-core-original-start-session",
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(19L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact original selected authored actor core"));
    byte[] authorityBundle =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "bundleVersion",
                        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                        "authorityScope",
                        Map.of(
                            "scope",
                                Map.of("tenantId", tenant.toString(), "targetNamespace", namespace),
                            "actionFamily", pre.actionFamily(),
                            "applicableAccountId", actor.toString(),
                            "applicableTenantId", tenant.toString()),
                        "accountProjectionEvidence",
                        Map.of(
                            "sourceType", "ACCOUNT",
                            "sourceEvidenceId", digest,
                            "sourceEvidenceVersion", "17",
                            "projectionStatus", "CURRENT",
                            "evaluatedAt", "2026-10-09T00:00:00Z",
                            "expiresAt", "2026-10-09T00:05:00Z"),
                        "issuanceOperationIdentity",
                        Map.of(
                            "issuanceOperationId",
                            issuanceId.toString(),
                            "controlPlaneRequestId",
                            pre.controlPlaneRequestId(),
                            "actionFamilyRequestIdentity",
                            Map.of(
                                "requestIdentityKind",
                                "controlPlaneRequestId",
                                "requestId",
                                pre.controlPlaneRequestId()),
                            "mutationDigest",
                            pre.mutationDigest()),
                        "issuanceKind",
                        "human_operator",
                        "authorityTuple",
                        Map.of(
                            "issuerAuthGeneration", 1L,
                            "accountAuthorityGeneration", 2L,
                            "tenantAuthorityGeneration", Map.of(tenant.toString(), 3L),
                            "membershipAuthorityGeneration", Map.of(tenant.toString(), 4L),
                            "privateRealmGrantVersions", List.of()),
                        "membershipVersion",
                        Map.of(tenant.toString(), 5L),
                        "issuanceFence",
                        "23",
                        "issuanceEvidence",
                        Map.of(
                            "evidenceType",
                            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                            "actorAccountId",
                            actor.toString(),
                            "controlUiTokenJti",
                            tokenJti.toString(),
                            "role",
                            "tenantAdmin",
                            "accountGeneration",
                            "2",
                            "tenantGeneration",
                            "3"))));
    var post =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            reservationOwner,
            19L,
            authorityBundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    var associationRequest =
        new Request(
            1,
            namespace,
            readId,
            post.canonicalBytes(),
            attempt,
            8L,
            new ExactReplay(version, commit, publishWorkflow, digest));
    return AuthoredInitialActorCoreReadGrpcCodec.toRequest(new ReadRequest(associationRequest));
  }

  private static final class Collector
      implements StreamObserver<ReadAuthoredInitialActorCoreResponse> {
    private ReadAuthoredInitialActorCoreResponse value;
    private Status.Code error;
    private String errorDescription;
    private boolean completed;

    @Override
    public void onNext(ReadAuthoredInitialActorCoreResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
      errorDescription = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
