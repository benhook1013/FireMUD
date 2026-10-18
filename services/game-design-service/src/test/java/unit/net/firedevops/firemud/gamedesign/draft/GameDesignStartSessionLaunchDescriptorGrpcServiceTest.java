package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.publication.StartSessionLaunchDescriptorProducer;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/** Callback and codec tests with an injected peer context; not mTLS or persisted-owner proof. */
class GameDesignStartSessionLaunchDescriptorGrpcServiceTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "world-runtime";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final StartSessionLaunchDescriptorProducer producer =
      mock(StartSessionLaunchDescriptorProducer.class);
  private final GameDesignStartSessionLaunchDescriptorGrpcService service =
      new GameDesignStartSessionLaunchDescriptorGrpcService(producer, NAMESPACE);

  @Test
  void authenticatesExactPeerBeforeDecodingOrCallingTheProducer() {
    var observer = new Collector();
    service.resolveStartSessionLaunchDescriptor(
        ReadStartSessionTemplateAssociationRequest.getDefaultInstance(), observer);
    assertThat(observer.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(producer);
  }

  @Test
  void rejectsWrongWorkloadBeforeDecodingOrCallingTheProducer() {
    var observer =
        invokeAs(
            ReadStartSessionTemplateAssociationRequest.getDefaultInstance(), "account-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    verifyNoInteractions(producer);
  }

  @Test
  void rejectsWrongNamespacePeerBeforeDecodingOrCallingTheProducer() {
    var observer =
        invokeAsNamespace(
            ReadStartSessionTemplateAssociationRequest.getDefaultInstance(),
            "another-runtime",
            "game-session-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(producer);
  }

  @Test
  void rejectsMalformedAndInitialRequestsBeforeCallingTheProducer() {
    var malformed =
        invokeAs(
            ReadStartSessionTemplateAssociationRequest.getDefaultInstance(),
            "game-session-service");
    assertThat(malformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);

    var initial = invokeAs(request(new InitialConfigured()), "game-session-service");
    assertThat(initial.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(producer);
  }

  @Test
  void rejectsDifferentTargetNamespaceBeforeCallingTheProducer() {
    var otherNamespace =
        request(
            "other-runtime",
            new ExactReplay(
                uuid("03333333-3333-4333-8333-333333333333"),
                uuid("04444444-4444-4444-8444-444444444444"),
                "selected-workflow",
                digest('a')));
    var observer = invokeAs(otherNamespace, "game-session-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(producer);
  }

  @Test
  void typedStoredDenialUsesItsExactAssociationBindingAndMessage() {
    // This mocked callback exercises status-to-response mapping only; it does not prove storage.
    var request =
        request(
            new ExactReplay(
                uuid("03333333-3333-4333-8333-333333333333"),
                uuid("04444444-4444-4444-8444-444444444444"),
                "selected-workflow",
                digest('a')));
    var decoded =
        net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
            .fromRequest(request);
    var association = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    var denial = mock(StartSessionLaunchDescriptorProducer.StoredBusinessDenial.class);
    when(denial.associationRead()).thenReturn(association);
    when(denial.failureCode()).thenReturn("INVALID_TEMPLATE_CONFIGURATION");
    when(denial.getMessage())
        .thenReturn("INVALID_TEMPLATE_CONFIGURATION: captured source is unsupported");
    when(producer.resolve(decoded)).thenThrow(denial);
    var response =
        ResolveStartSessionLaunchDescriptorResponse.newBuilder()
            .setSchemaVersion(StartSessionLaunchDescriptorGrpcCodec.RESPONSE_SCHEMA_VERSION)
            .build();

    try (MockedStatic<StartSessionLaunchDescriptorGrpcCodec> codec =
        Mockito.mockStatic(StartSessionLaunchDescriptorGrpcCodec.class)) {
      codec
          .when(() -> StartSessionLaunchDescriptorGrpcCodec.fromRequest(request))
          .thenReturn(decoded);
      codec
          .when(
              () ->
                  StartSessionLaunchDescriptorGrpcCodec.toFailureResponse(
                      decoded,
                      association,
                      "INVALID_TEMPLATE_CONFIGURATION",
                      "INVALID_TEMPLATE_CONFIGURATION: captured source is unsupported"))
          .thenReturn(response);

      var observer = invokeAs(request, "game-session-service");

      assertThat(observer.error).isNull();
      assertThat(observer.value).isEqualTo(response);
      assertThat(observer.completed).isTrue();
      verify(producer).resolve(decoded);
      codec.verify(
          () ->
              StartSessionLaunchDescriptorGrpcCodec.toFailureResponse(
                  decoded,
                  association,
                  "INVALID_TEMPLATE_CONFIGURATION",
                  "INVALID_TEMPLATE_CONFIGURATION: captured source is unsupported"));
    }
  }

  @Test
  void ordinaryFailedPreconditionOwnerFailureRemainsANonOkRpcError() {
    var request =
        request(
            new ExactReplay(
                uuid("03333333-3333-4333-8333-333333333333"),
                uuid("04444444-4444-4444-8444-444444444444"),
                "selected-workflow",
                digest('a')));
    var decoded =
        net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
            .fromRequest(request);
    when(producer.resolve(decoded))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("owner evidence changed")
                .asRuntimeException());

    var observer = invokeAs(request, "game-session-service");

    assertThat(observer.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verify(producer).resolve(decoded);
  }

  @Test
  void ordinaryUnavailableOwnerFailureRemainsANonOkRpcError() {
    var request =
        request(
            new ExactReplay(
                uuid("03333333-3333-4333-8333-333333333333"),
                uuid("04444444-4444-4444-8444-444444444444"),
                "selected-workflow",
                digest('a')));
    var decoded =
        net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
            .fromRequest(request);
    when(producer.resolve(decoded))
        .thenThrow(Status.UNAVAILABLE.withDescription("owner unavailable").asRuntimeException());

    var observer = invokeAs(request, "game-session-service");

    assertThat(observer.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verify(producer).resolve(decoded);
  }

  private Collector invokeAs(ReadStartSessionTemplateAssociationRequest request, String workload) {
    return invokeAsNamespace(request, NAMESPACE, workload);
  }

  private Collector invokeAsNamespace(
      ReadStartSessionTemplateAssociationRequest request, String namespace, String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + workload, namespace, workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      var observer = new Collector();
      service.resolveStartSessionLaunchDescriptor(request, observer);
      return observer;
    } finally {
      context.detach(previous);
    }
  }

  private static ReadStartSessionTemplateAssociationRequest request(
      StartSessionTemplateAssociationReadEvidence.Selection selection) {
    return request(NAMESPACE, selection);
  }

  private static ReadStartSessionTemplateAssociationRequest request(
      String namespace, StartSessionTemplateAssociationReadEvidence.Selection selection) {
    var request =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            namespace,
            uuid("01111111-1111-4111-8111-111111111111"),
            postTuple(namespace).canonicalBytes(),
            ATTEMPT,
            8L,
            selection);
    return net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
        .toRequest(request);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "start-session-launch-descriptor-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact StartSession descriptor selection"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre, namespace),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, String namespace) {
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", namespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
                "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Collector
      implements StreamObserver<ResolveStartSessionLaunchDescriptorResponse> {
    private ResolveStartSessionLaunchDescriptorResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ResolveStartSessionLaunchDescriptorResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
