package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuthoredInitialActorCoreReadEvidenceTest {
  @Test
  void completeResultBindsTheExactAssociationDescriptorAndOrderedProjectionSet()
      throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var result = fixture.result();

    assertThat(result.request()).isEqualTo(fixture.request());
    assertThat(result.associationRead()).isEqualTo(fixture.associationRead());
    assertThat(result.descriptor()).isEqualTo(fixture.descriptor());
    assertThat(result.projections()).isEmpty();
    assertThat(result.canonicalBytes()).isNotEmpty();
    assertThat(result.digest()).startsWith("sha256:");
  }

  @Test
  void refusesInitialSelectionAsActorCoreReadAuthority() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var initial =
        new Request(
            fixture.request().schemaVersion(),
            fixture.request().targetNamespace(),
            fixture.request().readRequestId(),
            fixture.request().canonicalPostAuthorizationTuple(),
            fixture.request().ownerAttemptId(),
            fixture.request().ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.InitialConfigured());

    assertThatThrownBy(
            () ->
                new AuthoredInitialActorCoreReadEvidence.ReadRequest(initial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact pinned association replay");
  }
}

/** Shared, owner-shaped value fixture for the directly owning Common transport tests. */
final class AuthoredInitialActorCoreReadTestFixture {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID INTAKE_ID = uuid("04444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_OPERATION_ID = uuid("05555555-5555-4555-8555-555555555555");
  private static final UUID SOURCE_OPERATION_ID = uuid("06666666-6666-4666-8666-666666666666");
  private static final UUID WORLD_READ_ID = uuid("07777777-7777-4777-8777-777777777777");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "test";
  private static final String WORKFLOW = "selected-publication-workflow";
  private static final String WORLD_SLUG = "synthetic-world";
  private static final long TEMPLATE_ID = 19L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuthoredInitialActorCoreReadTestFixture() {}

  static Fixture create() throws Exception {
    var worldEvidence = AuthoredWorldReleaseAttestationSelectorTest.selectorEvidence();
    UUID tenant = worldEvidence.request().canonicalTenantId();
    UUID version = worldEvidence.request().canonicalVersionId();
    UUID commit = uuid(worldEvidence.request().appliedCommitId());
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, INTAKE_ID, tenant, "tenant-key", WORLD_SLUG, "Synthetic World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            INTAKE_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            tenant,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            INTAKE_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            tenant,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var intake =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1, NAMESPACE, INTAKE_ID, tenant, WORLD_SLUG, SOURCE_OPERATION_ID, sourceEvidenceDigest);
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            tenant,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intake),
            digest('e'),
            source);
    var worldRead =
        new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
            1, NAMESPACE, WORLD_READ_ID, INTAKE_ID, tenant);
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            TEMPLATE_ID,
            version,
            commit,
            worldEvidence.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            SOURCE_OPERATION_ID,
            WORLD_SLUG,
            sourceEvidenceDigest,
            worldRead,
            receipt);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "actor-core-original-start-session",
            tenant,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            TEMPLATE_ID,
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
            "descriptor-1",
            42L,
            false,
            null,
            "{}",
            "generation",
            9L,
            7L,
            "release-1",
            false,
            null);
    var releaseEvidence =
        AuthoredWorldReleaseAttestationSelectorTest.release(descriptor, worldEvidence);
    PublishedReleaseBundle.Builder release =
        PublishedReleaseBundle.newBuilder()
            .setId(7L)
            .setVersionId(42L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(worldEvidence.request().publishWorkflowId())
            .setManifestHash(releaseEvidence.manifestHash())
            .setManifestSchemaVersion(releaseEvidence.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(releaseEvidence.requiredManifestAssetKeys())
            .setGenerationConfigRevision(releaseEvidence.generationConfigRevision())
            .setPublishedReleaseBundleRef(releaseEvidence.publishedReleaseBundleRef())
            .setCanonicalTenantId(tenant.toString())
            .setCanonicalVersionId(version.toString())
            .addAllCommandDefinitions(releaseEvidence.commandDefinitions());
    for (var participant : releaseEvidence.participantDigests()) {
      ParticipantDigest.Builder item =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent()) {
        item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      release.addParticipantDigests(item);
    }
    var request =
        new Request(
            1,
            NAMESPACE,
            READ_ID,
            postTuple(tenant, NAMESPACE).canonicalBytes(),
            ATTEMPT,
            8L,
            new ExactReplay(
                version,
                commit,
                worldEvidence.request().publishWorkflowId(),
                association.associationDigest()));
    var associationRead =
        new StartSessionTemplateAssociationReadEvidence.Result(
            request, association, release.build(), worldEvidence, 23L);
    return new Fixture(request, associationRead, descriptor, source);
  }

  static StartSessionTemplateAssociationReadEvidence.Request request() throws Exception {
    return create().request();
  }

  static StartSessionTemplateAssociationReadEvidence.Request request(String namespace)
      throws Exception {
    var original = create().request();
    return new Request(
        original.schemaVersion(),
        namespace,
        original.readRequestId(),
        postTuple(original.decodedTuple().preAuthorizationTuple().action().scope().tenantId(), namespace)
            .canonicalBytes(),
        original.ownerAttemptId(),
        original.ownerFence(),
        original.selection());
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      UUID tenant, String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "actor-core-original-start-session",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(TEMPLATE_ID, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact original selected authored actor core"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
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
                "applicableAccountId", ACTOR.toString(),
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
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind", "controlPlaneRequestId",
                        "requestId", tuple.controlPlaneRequestId()),
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
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(value));
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

  record Fixture(
      Request request,
      Result associationRead,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldSourceEvidence source) {
    AuthoredInitialActorCoreReadEvidence.ReadResult result() {
      return new AuthoredInitialActorCoreReadEvidence.ReadResult(
          request, associationRead, descriptor, List.of());
    }
  }
}
