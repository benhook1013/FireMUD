package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.testing.PlayerCreatedPublishedPolicyFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient.InvalidOwnerEvidenceException;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture.WireFixture;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Production client init/channel and closed owner-codec proof over controlled loopback PKI. Valid
 * owner-shaped results are stipulated fixtures with a matching closed authored projection. This
 * proves Entity transport and complete byte/target binding with the real peer interceptor. The
 * test-local owner stub does not execute the production Game Session adapter or prove genuine
 * publication, bootstrap issuance, issued scope, membership, protection, allocation or admission.
 */
@Timeout(120)
class EntityPlayerCreatedRosterSourceClientMtlsTest {
  private static final UUID FIXTURE_ID = UUID.fromString("01111111-1111-4111-8111-111111111111");
  private static final String NAMESPACE = "test";
  @TempDir static Path directory;
  private static ControlledPlayerCreatedRosterSourceFixture controlled;
  private static WireFixture fixture;

  @BeforeAll
  static void setUp() throws Exception {
    controlled = new ControlledPlayerCreatedRosterSourceFixture(directory);
    fixture = controlled.wireFixture();
  }

  @Test
  void actualInitializedClientPreservesExactRequestAndCompleteTypedOwnerSources() throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      var result = client.read(fixture.request());
      assertThat(owner.receivedRequests()).containsExactly(fixture.request());
      assertThat(owner.observedPeerUri())
          .isEqualTo("spiffe://firemud/ns/test/sa/entity-management-service");
      assertThat(result.publishedPolicySet().canonicalBytes())
          .containsExactly(fixture.policySet().canonicalBytes());
      assertThat(result.target())
          .isEqualTo(
              new CanonicalGameplayRosterTarget(
                  fixture.world().request().canonicalTenantId(),
                  FIXTURE_ID,
                  "synthetic-world",
                  "realm",
                  fixture.world().request().canonicalGameInstanceId(),
                  3L,
                  5L,
                  7L,
                  fixture.world().request().canonicalVersionId(),
                  fixture.policySet().policies().getFirst().policyDigest().substring(7),
                  fixture.policySet().publishedReleaseBundleRef(),
                  "c".repeat(64),
                  "b".repeat(64),
                  fixture.world().request().playableStateNamespaceId(),
                  PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                  CanonicalGameplayRosterEntryPolicy.PLAYER_CREATED));
      assertThat(result.creationDescriptor().canonicalJson())
          .isEqualTo(fixture.authored().projections().getFirst().descriptor().canonicalJson());
      assertThat(result.response().toByteArray()).containsExactly(fixture.response().toByteArray());
      assertThat(result.authored().canonicalBytes())
          .containsExactly(fixture.authored().canonicalBytes());
      assertThat(result.authored().projections()).hasSize(1);
      assertThat(result.world().canonicalBytes()).containsExactly(fixture.world().canonicalBytes());
      assertThat(result.response().getOriginalControlPlaneRequestId())
          .isEqualTo("world-lifecycle-control-request");
      assertThat(result.response().getPointer().getUpdatedAt())
          .isEqualTo("2026-10-24T00:00:00.123456789Z");
    }
  }

  @Test
  void actualClientSourceComposesBeforeTheLocalRosterRead() throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      var repository = mock(EntityCharacterCreateRepository.class);
      when(repository.readPlayerCreatedRoster(any(), any()))
          .thenAnswer(
              invocation -> {
                assertThat(owner.receivedRequests()).containsExactly(fixture.request());
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var published =
                    invocation.getArgument(
                        1, EntityPlayerCreatedRosterReadService.PublishedIdentity.class);
                assertThat(published.tenantId())
                    .isEqualTo(fixture.world().request().canonicalTenantId());
                assertThat(published.projection().canonicalBytes())
                    .containsExactly(fixture.authored().projections().getFirst().canonicalBytes());
                return List.of();
              });
      var service = new EntityPlayerCreatedRosterReadService(repository, client);

      assertThat(
              service.read(
                  new EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector(
                      UUID.randomUUID()),
                  fixture.request()))
          .isEmpty();
    }
  }

  @Test
  void constructionRequiresFileBackedMtlsAndCanonicalNamespace() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var channels = new GrpcChannelFactory();
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, new CommonGrpcClientProperties(), channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    var plaintext = controlled.clientProperties("entity");
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, plaintext, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, controlled.clientProperties("entity"), channels, "Other"))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  void wrongServerServiceAndNamespaceAreRejectedBeforeAnyReceivedRpc() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      try (var owner = controlled.owner(identity);
          var client = controlled.client(owner)) {
        client.init();
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOf(StatusRuntimeException.class);
        assertThat(owner.receivedRequests()).isEmpty();
      }
    }
  }

  @Test
  void absentInitializationAndAmbientSqlRefuseBeforeAnyReceivedRpc() throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOf(IllegalStateException.class);
      client.init();
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      assertThat(owner.receivedRequests()).isEmpty();
    }
  }

  @Test
  void malformedAndChangedRequestSelectorsRefuseBeforeAnyReceivedRpc() throws Exception {
    var request = fixture.request();
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      for (var changed :
          List.of(
              request.toBuilder().setSchemaVersion(2).build(),
              request.toBuilder().setTargetNamespace("other").build(),
              request.toBuilder().setPlayableStateScope("ISOLATED").build(),
              request.toBuilder().setCanonicalGameInstanceId("invalid").build(),
              request.toBuilder().setWorldSlug("x".repeat(16 * 1024)).build(),
              request.toBuilder().setUnknownFields(unknown()).build(),
              request.toBuilder().setExpectedCanonicalVersionId("42").build(),
              request.toBuilder().setActiveWorldEpoch(0).build(),
              request.toBuilder().setRealmSlug("Realm").build(),
              request.toBuilder().setReadRequestId(new UUID(0, 0).toString()).build(),
              request.toBuilder().setCatalogRevision(0).build(),
              request.toBuilder().setPointerVersion(0).build())) {
        assertThatThrownBy(() -> client.read(changed))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
      }
      assertThat(owner.receivedRequests()).isEmpty();
    }
  }

  @Test
  void changedEchoTargetsProvenanceClosedBodiesAndInactiveWorldFailClosed() throws Exception {
    var good = fixture.response();
    var world = fixture.world();
    var inactive =
        new WorldCanonicalInstanceLifecycleEvidence(
            world.request(),
            world.launchBinding(),
            world.startLocation(),
            world.runtimeRoomInstanceId(),
            "TERMINATING",
            world.lifecycleEpoch(),
            world.rowVersion(),
            world.captureId(),
            world.graphSha256(),
            world.preparationInputDigest(),
            world.operationalRegionAssignments());
    var otherLaunch = controlled.wireFixture("other-authored-launch-descriptor").world();
    var authored = fixture.authored();
    var noProjections =
        AuthoredInitialActorCoreReadGrpcCodec.toResponse(
            new AuthoredInitialActorCoreReadEvidence.ReadResult(
                authored.request(), authored.associationRead(), authored.descriptor(), List.of()));
    var privateProjection =
        AuthoredInitialActorCoreReadGrpcCodec.toResponse(
            new AuthoredInitialActorCoreReadEvidence.ReadResult(
                authored.request(),
                authored.associationRead(),
                authored.descriptor(),
                List.of(
                    AuthoredInitialActorCoreProjection.fromValidatedBinding(
                        FIXTURE_ID,
                        sourceBinding(
                            authored.associationRead().association(),
                            authored.associationRead().association().sourceOperationId(),
                            false)))));
    var extraProjection =
        AuthoredInitialActorCoreProjection.fromValidatedBinding(
            UUID.randomUUID(),
            sourceBinding(
                authored.associationRead().association(),
                authored.associationRead().association().sourceOperationId()));
    var ambiguousProjections =
        AuthoredInitialActorCoreReadGrpcCodec.toResponse(
            new AuthoredInitialActorCoreReadEvidence.ReadResult(
                authored.request(),
                authored.associationRead(),
                authored.descriptor(),
                List.of(authored.projections().getFirst(), extraProjection)));
    var operation =
        GameDesignPublicationOperationBinding.fromStored(fixture.policySet().operationBytes());
    var originalRelease =
        GameDesignPublicationTerminalEvidence.fromStored(
                fixture.policySet().terminalEvidenceBytes())
            .releaseContent();
    var changedRelease =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            originalRelease.canonicalTenantId(),
            originalRelease.canonicalVersionId(),
            "other-release",
            originalRelease.versionNumber(),
            originalRelease.attestationSchemaVersion(),
            originalRelease.publishWorkflowId(),
            originalRelease.manifestHash(),
            originalRelease.manifestSchemaVersion(),
            originalRelease.artifactDigests(),
            originalRelease.requiredManifestAssetKeys(),
            originalRelease.participantDigests(),
            originalRelease.commandDefinitions(),
            originalRelease.generationConfigRevision(),
            originalRelease.worldStartLocationEvidence());
    var otherReleaseSet =
        PlayerCreatedPublishedPolicyFixtures.seal(
            new PlayerCreatedPublishedPolicyFixtures.Prepared(
                operation.account().input().selection().selectedCommit(),
                operation.account(),
                operation.world()),
            operation.inventory(),
            changedRelease,
            authored.projections().getFirst());
    var mismatchedSources =
        new java.util.ArrayList<
            net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse>();
    for (String field : List.of("commitId", "revisionId", "logicalRevisionId")) {
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      var originalProjection = authored.projections().getFirst();
      var node =
          (tools.jackson.databind.node.ObjectNode)
              json.readTree(originalProjection.canonicalBytes());
      ((tools.jackson.databind.node.ObjectNode) node.get("policy"))
          .put(
              field,
              field.equals("logicalRevisionId")
                  ? "another-source-revision"
                  : UUID.randomUUID().toString());
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(json.writeValueAsString(node));
      var changedProjection =
          AuthoredInitialActorCoreProjection.fromStored(bytes, CommandSource.sha256(bytes));
      mismatchedSources.add(
          AuthoredInitialActorCoreReadGrpcCodec.toResponse(
              new AuthoredInitialActorCoreReadEvidence.ReadResult(
                  authored.request(),
                  authored.associationRead(),
                  authored.descriptor(),
                  List.of(changedProjection))));
    }
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      for (var changed :
          List.of(
              good.toBuilder()
                  .setRequest(good.getRequest().toBuilder().setPointerVersion(99))
                  .build(),
              good.toBuilder()
                  .setCatalog(
                      good.getCatalog().toBuilder().setRealmId(UUID.randomUUID().toString()))
                  .build(),
              good.toBuilder()
                  .setPointer(
                      good.getPointer().toBuilder()
                          .setAdmissibleGameInstanceId(UUID.randomUUID().toString()))
                  .build(),
              good.toBuilder().setCatalog(good.getCatalog().toBuilder().setVisible(false)).build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setPublicProduction(false))
                  .build(),
              good.toBuilder()
                  .setCatalog(
                      good.getCatalog().toBuilder().setCharacterCreationPolicy("PRESEEDED_ONLY"))
                  .build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setReceiptDigest("bad"))
                  .build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setCreationRequestId("42"))
                  .build(),
              good.toBuilder()
                  .setPointer(good.getPointer().toBuilder().setUpdatedAt("bad"))
                  .build(),
              good.toBuilder()
                  .setPointer(good.getPointer().toBuilder().clearInitialAdmissionRequestId())
                  .build(),
              good.toBuilder()
                  .setPointer(
                      good.getPointer().toBuilder().setInitialAdmissionOwnerProofDigest("bad"))
                  .build(),
              good.toBuilder()
                  .setPointer(
                      good.getPointer().toBuilder().setAdmissionPointerSnapshotDigest("bad"))
                  .build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setWorldDisplayName("Different world"))
                  .build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setRealmDisplayName("Different realm"))
                  .build(),
              good.toBuilder()
                  .setPointer(good.getPointer().toBuilder().setAdmissionState("CLOSED"))
                  .build(),
              good.toBuilder()
                  .setCatalog(good.getCatalog().toBuilder().setUnknownFields(unknown()))
                  .build(),
              good.toBuilder()
                  .setPointer(good.getPointer().toBuilder().setUnknownFields(unknown()))
                  .build(),
              good.toBuilder().setCanonicalVersionId(UUID.randomUUID().toString()).build(),
              good.toBuilder().setOriginalControlPlaneRequestId("different-original").build(),
              good.toBuilder().setOriginalOwnerAttemptId(UUID.randomUUID().toString()).build(),
              good.toBuilder().setOriginalOwnerFence(good.getOriginalOwnerFence() + 1).build(),
              good.toBuilder().setActiveLifecycleEpoch(good.getActiveLifecycleEpoch() + 1).build(),
              good.toBuilder().setAuthoredSource(noProjections).build(),
              good.toBuilder().setAuthoredSource(privateProjection).build(),
              good.toBuilder().setAuthoredSource(ambiguousProjections).build(),
              good.toBuilder()
                  .setAuthoredSource(
                      good.getAuthoredSource().toBuilder()
                          .setCanonicalResultBytes(ByteString.copyFromUtf8("{}")))
                  .build(),
              good.toBuilder()
                  .setAuthoredSource(
                      good.getAuthoredSource().toBuilder().setResultDigest(digest('0')))
                  .build(),
              good.toBuilder()
                  .setAuthoredSource(
                      good.getAuthoredSource().toBuilder().setUnknownFields(unknown()))
                  .build(),
              good.toBuilder().setWorldLifecycleEvidence(ByteString.copyFromUtf8("{}")).build(),
              good.toBuilder()
                  .setWorldLifecycleEvidence(ByteString.copyFrom(inactive.canonicalBytes()))
                  .build(),
              good.toBuilder()
                  .setWorldLifecycleEvidence(ByteString.copyFrom(otherLaunch.canonicalBytes()))
                  .build(),
              good.toBuilder()
                  .setPublishedPolicySetEvidence(
                      ByteString.copyFrom(otherReleaseSet.canonicalBytes()))
                  .build(),
              good.toBuilder().setAuthoredSource(mismatchedSources.get(0)).build(),
              good.toBuilder().setAuthoredSource(mismatchedSources.get(1)).build(),
              good.toBuilder().setAuthoredSource(mismatchedSources.get(2)).build(),
              good.toBuilder().clearPublishedPolicySetEvidence().build(),
              good.toBuilder().setPublishedPolicySetEvidence(ByteString.copyFromUtf8("{}")).build(),
              good.toBuilder()
                  .setPublishedPolicySetEvidence(
                      ByteString.copyFromUtf8(
                          fixture
                              .policySet()
                              .canonicalJson()
                              .replace(
                                  fixture.policySet().publishedReleaseBundleRef(),
                                  "other-release")))
                  .build(),
              good.toBuilder()
                  .setPublishedPolicySetEvidence(
                      ByteString.copyFromUtf8(
                          fixture
                              .policySet()
                              .canonicalJson()
                              .replace(
                                  fixture.policySet().sourceCommitId().toString(),
                                  UUID.randomUUID().toString())))
                  .build(),
              good.toBuilder()
                  .setPublishedPolicySetEvidence(
                      ByteString.copyFromUtf8(
                          fixture.policySet().canonicalJson().replace("Wayfarer", "Other actor")))
                  .build(),
              good.toBuilder().clearAuthoredSource().build(),
              good.toBuilder().clearWorldLifecycleEvidence().build(),
              good.toBuilder().clearCatalog().build(),
              good.toBuilder().clearPointer().build(),
              good.toBuilder().setOriginalControlPlaneRequestId("x".repeat(129)).build(),
              good.toBuilder().setUnknownFields(unknown()).build())) {
        owner.respondWith(changed);
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOfSatisfying(
                InvalidOwnerEvidenceException.class,
                failure -> assertThat(failure.getCause()).isNotNull());
      }
      assertThat(owner.receivedRequests()).isNotEmpty().allMatch(fixture.request()::equals);
      owner.respondWith(good);
      assertThat(client.read(fixture.request()).response()).isEqualTo(good);
    }
  }

  @Test
  void authenticatedMalformedOwnerResponseKeepsGuardAndCodecCausesDistinctFromCallerInput()
      throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      owner.respondWith(fixture.response().toBuilder().setSchemaVersion(2).build());
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOfSatisfying(
              InvalidOwnerEvidenceException.class,
              failure ->
                  assertThat(failure.getCause())
                      .isInstanceOfSatisfying(
                          StatusRuntimeException.class,
                          guard ->
                              assertThat(guard.getStatus().getCode())
                                  .isEqualTo(Status.Code.INVALID_ARGUMENT)));
      owner.respondWith(
          fixture.response().toBuilder()
              .setWorldLifecycleEvidence(ByteString.copyFromUtf8("not-json-world-evidence"))
              .build());
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOf(InvalidOwnerEvidenceException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class);
      assertThat(owner.receivedRequests()).containsExactly(fixture.request(), fixture.request());
      owner.respondWith(fixture.response());
      assertThat(client.read(fixture.request()).response()).isEqualTo(fixture.response());
    }
  }

  @Test
  void unavailableTransportIsNotReclassifiedAsInvalidOwnerEvidence() throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      owner.close();
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(owner.receivedRequests()).isEmpty();
    }
  }

  @Test
  void inboundTransportRejectsResponseBeyond32MiBBeforeSourceDecoding() throws Exception {
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      client.init();
      owner.respondWith(
          fixture.response().toBuilder()
              .setWorldLifecycleEvidence(
                  ByteString.copyFrom(
                      new byte[EntityPlayerCreatedRosterSourceClient.MAX_SOURCE_WIRE_BYTES]))
              .build());
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode())
                      .isEqualTo(io.grpc.Status.Code.RESOURCE_EXHAUSTED));
      assertThat(owner.receivedRequests()).containsExactly(fixture.request());
    }
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      UUID tenant, String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "world-lifecycle-control-request",
            FIXTURE_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(19L, FIXTURE_ID),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact original selected authored actor core"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        FIXTURE_ID,
        19L,
        authorityBundle(pre, tenant, namespace),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID tenant, String namespace) {
    String tenantText = tenant.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantText, "targetNamespace", namespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", FIXTURE_ID.toString(),
                "applicableTenantId", tenantText),
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
                "issuanceOperationId", FIXTURE_ID.toString(),
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
                "tenantAuthorityGeneration", Map.of(tenantText, 3L),
                "membershipAuthorityGeneration", Map.of(tenantText, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenantText, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                FIXTURE_ID.toString(),
                "controlUiTokenJti",
                FIXTURE_ID.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding(
      StartSessionTemplateAssociationReadEvidence.Association association,
      UUID worldSourceOperationId)
      throws Exception {
    return sourceBinding(association, worldSourceOperationId, true);
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding(
      StartSessionTemplateAssociationReadEvidence.Association association,
      UUID worldSourceOperationId,
      boolean visible)
      throws Exception {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":3,\"worldSlug\":\""
                + association.worldSlug()
                + "\",\"worldDisplayName\":\"Synthetic World\","
                + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":"
                + visible
                + ","
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PLAYER_CREATED\",\"creationDescriptor\":{"
                + "\"schemaVersion\":1,\"descriptorId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                + "\"descriptorRevisionId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\","
                + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
                + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}",
            tools.jackson.databind.json.JsonMapper.builder().build());
    var policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "policy-logical-r1",
                "policy",
                RealmPolicySource.tree(policy.canonicalJson())));
    var disposition = new Disposition("newcomer", List.of(), "welcome");
    var feedback =
        new Feedback(
            "welcome",
            "actor_created",
            Audience.SOURCE_ACTOR,
            "welcome_message",
            "Welcome, traveler.",
            List.of(),
            Visibility.PUBLIC,
            GameplayRuleManifest.ReplayPolicy.EFFECT_IDEMPOTENT);
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0",
                UUID.fromString("13333333-3333-4333-8333-333333333333"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                policyPayload),
            new RevisionPayload(
                "1",
                UUID.fromString("14444444-4444-4444-8444-444444444444"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                UUID.fromString("15555555-5555-4555-8555-555555555555"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(feedback)));
    TargetProof target =
        new TargetProof(
            association.canonicalTenantId(),
            association.canonicalVersionId(),
            42L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    DraftCommitBinding commit =
        DraftCommitBinding.create(
            target,
            UUID.fromString("1eeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            association.selectedCommitId(),
            "base-0",
            revisions,
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    association.canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "0"),
                new AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    association.canonicalVersionId().toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    commit = PlayerCreatedPublishedPolicyFixtures.withWorldSource(commit);
    var selectedPolicy = RealmPolicySource.revision(commit, revisions.getFirst());
    var policySnapshot = new RealmPolicySnapshot(commit, "1", List.of(selectedPolicy));
    var dispositionEntry =
        new GameplayRuleSource.Entry(
            Family.DISPOSITIONS,
            disposition,
            commit,
            "1",
            UUID.fromString("14444444-4444-4444-8444-444444444444"));
    var feedbackEntry =
        new GameplayRuleSource.Entry(
            Family.FEEDBACK,
            feedback,
            commit,
            "2",
            UUID.fromString("15555555-5555-4555-8555-555555555555"));
    var gameplay =
        new GameplayRuleSelectedSource(
            gameplaySnapshot(commit, List.of(dispositionEntry, feedbackEntry)));
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
            worldSourceOperationId,
            association.sourceEvidenceDigest(),
            association.worldSlug(),
            association.publishWorkflowId(),
            bytes("selected publication operation bytes"),
            bytes("selected release terminal evidence"),
            commit,
            policySnapshot,
            gameplay)
        .getFirst();
  }

  private static String gameplaySnapshot(
      DraftCommitBinding binding, List<GameplayRuleSource.Entry> entries) {
    Map<String, Object> families = new java.util.LinkedHashMap<>();
    for (Family family : Family.values()) {
      families.put(
          family.name(),
          entries.stream()
              .filter(value -> value.family() == family)
              .map(GameplayRuleSource.Entry::definition)
              .toList());
    }
    var manifest =
        GameplayRuleManifest.fromStored(
            GameplayRuleManifest.canonical(
                Map.of("schema", GameplayRuleManifest.SCHEMA, "families", families)));
    Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
    snapshot.put("schema", "game-design-gameplay-rule-source-snapshot/v1");
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", "1");
    snapshot.put("inheritedCommitId", "");
    snapshot.put("genesisReceiptId", "77777777-7777-4777-8777-777777777777");
    snapshot.put("manifestJson", manifest.canonicalJson());
    snapshot.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    Map.of(
                        "family", entry.family().name(),
                        "definitionJson", GameplayRuleManifest.canonical(entry.definition()),
                        "sourceBindingJson", entry.sourceBinding().canonicalJson(),
                        "sourceBindingDigest", entry.sourceBinding().digest(),
                        "revisionOrder", entry.revisionOrder(),
                        "revisionId", entry.revisionId().toString()))
            .toList());
    return CommandSource.canonical(snapshot);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
