package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.testing.PlayerCreatedPublishedPolicyFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient.InvalidOwnerEvidenceException;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicCatalog;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicPointer;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
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
  private static LoopbackMtlsTestPki.Pki pki;
  private static Fixture fixture;

  @BeforeAll
  static void setUp() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity roster source test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity", "spiffe://firemud/ns/test/sa/entity-management-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "server", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/social-groups-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/game-session-service", true));
    fixture = fixture();
  }

  @Test
  void actualInitializedClientPreservesExactRequestAndCompleteTypedOwnerSources() throws Exception {
    try (var owner = owner("server");
        var client = client(owner)) {
      client.init();
      var result = client.read(fixture.request());
      assertThat(owner.received).containsExactly(fixture.request());
      assertThat(owner.observedPeer.get())
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
  void constructionRequiresFileBackedMtlsAndCanonicalNamespace() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var channels = new GrpcChannelFactory();
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, new CommonGrpcClientProperties(), channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    var plaintext = pki.clientProperties(directory, pki.identity("entity"));
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, plaintext, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("entity")),
                    channels,
                    "Other"))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  void wrongServerServiceAndNamespaceAreRejectedBeforeAnyReceivedRpc() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      try (var owner = owner(identity);
          var client = client(owner)) {
        client.init();
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOf(StatusRuntimeException.class);
        assertThat(owner.received).isEmpty();
      }
    }
  }

  @Test
  void absentInitializationAndAmbientSqlRefuseBeforeAnyReceivedRpc() throws Exception {
    try (var owner = owner("server");
        var client = client(owner)) {
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
      assertThat(owner.received).isEmpty();
    }
  }

  @Test
  void malformedAndChangedRequestSelectorsRefuseBeforeAnyReceivedRpc() throws Exception {
    var request = fixture.request();
    try (var owner = owner("server");
        var client = client(owner)) {
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
      assertThat(owner.received).isEmpty();
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
    var otherLaunch = fixture("other-authored-launch-descriptor").world();
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
    try (var owner = owner("server");
        var client = client(owner)) {
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
        owner.response.set(changed);
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOfSatisfying(
                InvalidOwnerEvidenceException.class,
                failure -> assertThat(failure.getCause()).isNotNull());
      }
      assertThat(owner.received).isNotEmpty().allMatch(fixture.request()::equals);
      owner.response.set(good);
      assertThat(client.read(fixture.request()).response()).isEqualTo(good);
    }
  }

  @Test
  void authenticatedMalformedOwnerResponseKeepsGuardAndCodecCausesDistinctFromCallerInput()
      throws Exception {
    try (var owner = owner("server");
        var client = client(owner)) {
      client.init();
      owner.response.set(fixture.response().toBuilder().setSchemaVersion(2).build());
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
      owner.response.set(
          fixture.response().toBuilder()
              .setWorldLifecycleEvidence(ByteString.copyFromUtf8("not-json-world-evidence"))
              .build());
      assertThatThrownBy(() -> client.read(fixture.request()))
          .isInstanceOf(InvalidOwnerEvidenceException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class);
      assertThat(owner.received).containsExactly(fixture.request(), fixture.request());
      owner.response.set(fixture.response());
      assertThat(client.read(fixture.request()).response()).isEqualTo(fixture.response());
    }
  }

  @Test
  void authenticatedOutboundFailuresKeepTheirExactTransportStatus() throws Exception {
    for (var status :
        List.of(Status.UNAVAILABLE, Status.FAILED_PRECONDITION, Status.INVALID_ARGUMENT)) {
      var outbound = status.withDescription("controlled source owner transport failure");
      try (var owner = owner("server", outbound.asRuntimeException());
          var client = client(owner)) {
        client.init();
        assertThatThrownBy(() -> client.read(fixture.request()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure -> {
                  assertThat(failure.getStatus().getCode()).isEqualTo(outbound.getCode());
                  assertThat(failure.getStatus().getDescription())
                      .isEqualTo(outbound.getDescription());
                });
        assertThat(owner.received).containsExactly(fixture.request());
      }
    }
  }

  @Test
  void inboundTransportRejectsResponseBeyond32MiBBeforeSourceDecoding() throws Exception {
    try (var owner = owner("server");
        var client = client(owner)) {
      client.init();
      owner.response.set(
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
      assertThat(owner.received).containsExactly(fixture.request());
    }
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static EntityPlayerCreatedRosterSourceClient client(Owner owner) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + owner.server.getPort());
    return new EntityPlayerCreatedRosterSourceClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("entity")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Owner owner(String name) throws Exception {
    return owner(name, null);
  }

  private static Owner owner(String name, StatusRuntimeException outboundFailure) throws Exception {
    var response = new AtomicReference<>(fixture.response());
    var received = new CopyOnWriteArrayList<ReadPlayerCreatedRosterSourceRequest>();
    var observedPeer = new AtomicReference<String>();
    var identity = pki.identity(name);
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new CharacterCreatePublicSourceServiceGrpc
                        .CharacterCreatePublicSourceServiceImplBase() {
                      @Override
                      public void readPlayerCreatedRosterSource(
                          ReadPlayerCreatedRosterSourceRequest request,
                          StreamObserver<ReadPlayerCreatedRosterSourceResponse> observer) {
                        observedPeer.set(GrpcPeerIdentity.current().uri());
                        received.add(request);
                        if (outboundFailure != null) {
                          observer.onError(outboundFailure);
                          return;
                        }
                        observer.onNext(response.get());
                        observer.onCompleted();
                      }
                    },
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new Owner(server, response, received, observedPeer);
  }

  private record Owner(
      Server server,
      AtomicReference<ReadPlayerCreatedRosterSourceResponse> response,
      List<ReadPlayerCreatedRosterSourceRequest> received,
      AtomicReference<String> observedPeer)
      implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private record Fixture(
      ReadPlayerCreatedRosterSourceRequest request,
      ReadPlayerCreatedRosterSourceResponse response,
      AuthoredInitialActorCoreReadEvidence.ReadResult authored,
      WorldCanonicalInstanceLifecycleEvidence world,
      PublishedRealmEntryPolicySetEvidence policySet) {}

  // Common's authored-core fixture lives in its private unit-test source and is not exported.
  // Build only the owner-shaped values needed here, using the exported World fixture and real
  // codecs.
  private static Fixture fixture() throws Exception {
    return fixture("canonical-instance-launch-descriptor");
  }

  private static Fixture fixture(String descriptorId) throws Exception {
    var originalWorld = AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    var originalDescriptor = originalWorld.launchBinding().descriptor();
    var selector = originalWorld.launchBinding().releaseAttestation().worldStartLocationEvidence();
    var tenant = originalWorld.request().canonicalTenantId();
    var version = originalWorld.request().canonicalVersionId();
    var intakeId = UUID.randomUUID();
    var operationId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, intakeId, tenant, "tenant-key", "synthetic-world", "Synthetic World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            intakeId,
            originalDescriptor.authoredWorldSourceOperationId(),
            requestDigest,
            tenant,
            "tenant-key",
            "synthetic-world",
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            intakeId,
            originalDescriptor.authoredWorldSourceOperationId(),
            requestDigest,
            tenant,
            "tenant-key",
            "synthetic-world",
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            evidenceDigest);
    var intake =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            NAMESPACE,
            intakeId,
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest);
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            intakeId,
            operationId,
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intake),
            digest('e'),
            source);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "world-lifecycle-control-request",
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest,
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            descriptorId,
            42L,
            false,
            null,
            "{}",
            originalDescriptor.generationConfigRevision(),
            9L,
            7L,
            originalDescriptor.publishedReleaseBundleRef(),
            false,
            null);
    var oldRelease = originalWorld.launchBinding().releaseAttestation();
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            19L,
            version,
            UUID.fromString(selector.request().appliedCommitId()),
            selector.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            intakeId,
            operationId,
            source.operationId(),
            descriptor.worldSlug(),
            evidenceDigest,
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1, NAMESPACE, FIXTURE_ID, intakeId, tenant),
            receipt);
    var prepared =
        PlayerCreatedPublishedPolicyFixtures.prepare(
            sourceBinding(association, association.sourceOperationId()).selectedCommit());
    selector = prepared.world();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            tenant,
            version,
            descriptor.worldSlug(),
            source.operationId(),
            evidenceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            oldRelease.participantDigests(),
            oldRelease.manifestHash(),
            oldRelease.manifestSchemaVersion(),
            oldRelease.requiredManifestAssetKeys(),
            oldRelease.artifactDigests(),
            oldRelease.commandDefinitions(),
            oldRelease.generationConfigRevision(),
            selector);
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            FIXTURE_ID,
            NAMESPACE,
            tenant,
            descriptor.worldSlug(),
            originalWorld.request().canonicalGameInstanceId(),
            originalWorld.request().playableStateNamespaceId(),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            version,
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var world =
        new WorldCanonicalInstanceLifecycleEvidence(
            lifecycleRequest,
            new CompleteLaunchBindingEvidence(descriptor, release),
            originalWorld.startLocation(),
            originalWorld.runtimeRoomInstanceId(),
            "ACTIVE",
            7L,
            originalWorld.rowVersion(),
            originalWorld.captureId(),
            WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes())
                .graphDigest()
                .substring(7),
            originalWorld.preparationInputDigest(),
            originalWorld.operationalRegionAssignments());
    association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            19L,
            version,
            UUID.fromString(selector.request().appliedCommitId()),
            selector.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            intakeId,
            operationId,
            source.operationId(),
            descriptor.worldSlug(),
            evidenceDigest,
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1, NAMESPACE, FIXTURE_ID, intakeId, tenant),
            receipt);
    var readRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            NAMESPACE,
            FIXTURE_ID,
            postTuple(tenant, NAMESPACE).canonicalBytes(),
            FIXTURE_ID,
            8L,
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                version,
                association.selectedCommitId(),
                association.publishWorkflowId(),
                association.associationDigest()));
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(7L)
            .setVersionId(42L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(release.publishWorkflowId())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(release.requiredManifestAssetKeys())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .setPublishedReleaseBundleRef(release.publishedReleaseBundleRef())
            .setCanonicalTenantId(tenant.toString())
            .setCanonicalVersionId(version.toString())
            .addAllCommandDefinitions(release.commandDefinitions());
    for (var participant : release.participantDigests()) {
      var item =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent())
        item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      bundle.addParticipantDigests(item);
    }
    var authored =
        new AuthoredInitialActorCoreReadEvidence.ReadResult(
            readRequest,
            new StartSessionTemplateAssociationReadEvidence.Result(
                readRequest, association, bundle.build(), selector, 23L),
            descriptor,
            List.of(
                AuthoredInitialActorCoreProjection.fromValidatedBinding(
                    FIXTURE_ID, sourceBinding(association, association.sourceOperationId()))));
    var releaseContent =
        StartSessionTemplateAssociationReadEvidence.releaseContent(bundle.build(), selector);
    var policySet =
        PlayerCreatedPublishedPolicyFixtures.seal(
            prepared,
            IsolatedWorldPublicationInventoryFixtures.stipulated(
                prepared.account(), prepared.world()),
            releaseContent,
            authored.projections().getFirst());
    var request =
        ReadPlayerCreatedRosterSourceRequest.newBuilder()
            .setSchemaVersion(1)
            .setReadRequestId(FIXTURE_ID.toString())
            .setTargetNamespace(NAMESPACE)
            .setCanonicalTenantId(tenant.toString())
            .setRealmId(FIXTURE_ID.toString())
            .setWorldSlug(descriptor.worldSlug())
            .setRealmSlug("realm")
            .setPlayableStateNamespaceId(lifecycleRequest.playableStateNamespaceId().toString())
            .setPlayableStateScope("SHARED")
            .setCanonicalGameInstanceId(lifecycleRequest.canonicalGameInstanceId().toString())
            .setCatalogRevision(3L)
            .setPointerVersion(5L)
            .setExpectedCanonicalVersionId(version.toString())
            .setActiveWorldEpoch(7L)
            .build();
    var response =
        ReadPlayerCreatedRosterSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setCatalog(
                CharacterCreatePublicCatalog.newBuilder()
                    .setTenantId(tenant.toString())
                    .setTenantSlug("tenant")
                    .setWorldSlug(request.getWorldSlug())
                    .setWorldDisplayName("Synthetic World")
                    .setRealmId(request.getRealmId())
                    .setRealmSlug("realm")
                    .setRealmDisplayName("Realm")
                    .setVisible(true)
                    .setPublicProduction(true)
                    .setStateScope("SHARED")
                    .setPlayableStateNamespaceId(request.getPlayableStateNamespaceId())
                    .setCharacterCreationPolicy("PLAYER_CREATED")
                    .setCatalogRevision(3L)
                    .setCreationRequestId(FIXTURE_ID.toString())
                    .setReceiptDigest(digest('a')))
            .setPointer(
                CharacterCreatePublicPointer.newBuilder()
                    .setTenantId(tenant.toString())
                    .setRealmId(request.getRealmId())
                    .setWorldSlug(request.getWorldSlug())
                    .setRealmSlug("realm")
                    .setAdmissionState("OPEN")
                    .setAdmissibleGameInstanceId(request.getCanonicalGameInstanceId())
                    .setPointerVersion(5L)
                    .setCatalogRevision(3L)
                    .setUpdatedAt("2026-10-24T00:00:00.123456789Z")
                    .setInitialAdmissionRequestId("initial-admission")
                    .setInitialAdmissionOwnerProofDigest(digest('b'))
                    .setAdmissionPointerSnapshotDigest("c".repeat(64)))
            .setCanonicalVersionId(version.toString())
            .setActiveLifecycleEpoch(7L)
            .setOriginalControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setOriginalOwnerAttemptId(FIXTURE_ID.toString())
            .setOriginalOwnerFence(8L)
            .setAuthoredSource(AuthoredInitialActorCoreReadGrpcCodec.toResponse(authored))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(policySet.canonicalBytes()))
            .setWorldLifecycleEvidence(ByteString.copyFrom(world.canonicalBytes()))
            .build();
    return new Fixture(request, response, authored, world, policySet);
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
