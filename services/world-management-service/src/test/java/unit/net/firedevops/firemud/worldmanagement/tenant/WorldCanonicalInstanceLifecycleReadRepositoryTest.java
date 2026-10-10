package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorldCanonicalInstanceLifecycleReadRepositoryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void rejectsAmbientTransactionBeforeOpeningIndependentOwnerSnapshot() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var association = mock(WorldCanonicalInstanceAssociationRepository.class);
    var repository = new WorldCanonicalInstanceLifecycleReadRepository(dsl, manager, association);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");
    verifyNoInteractions(dsl, manager, association);
  }

  @Test
  void acceptsOnlyClosedFrozenGraphProfilesAndSupportedInboundClosure() {
    assertThatCode(() -> validateFrozenGraphProfile(graphV2(), 2)).doesNotThrowAnyException();
    assertThatThrownBy(() -> validateFrozenGraphProfile(graphV2(), 3))
        .hasMessageContaining("missing or unsupported fields");
    assertThatCode(() -> validateFrozenGraphProfile(graphV3(), 3)).doesNotThrowAnyException();

    ObjectNode unsupportedClosure = graphV3();
    JsonNode familyCounts = unsupportedClosure.get("inboundSourceClosure").get("familyCounts");
    ((ObjectNode) familyCounts.get(0)).put("count", 1);
    assertThatThrownBy(() -> validateFrozenGraphProfile(unsupportedClosure, 3))
        .hasMessageContaining("outside the supported profile");
  }

  @Test
  void mapsRetainedAndSelectedFullAttestationsToTheirFrozenGraphProfiles() throws Exception {
    assertThat(expectedFrozenGraphSchemaVersion(release(2))).isEqualTo(2);
    assertThat(expectedFrozenGraphSchemaVersion(release(3))).isEqualTo(3);

    var selectedFull =
        release(AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
    assertThat(selectedFull.participantDigests())
        .extracting(AuthoredWorldReleaseAttestationEvidence.Participant::digestSchemaVersion)
        .containsExactly(4, 3, 1, 6, 2);
    assertThat(selectedFull.worldStartLocationEvidence()).isNotNull();
    assertThat(selectedFull.worldStartLocationEvidence().request().digestSchemaVersion())
        .isEqualTo(4);
    assertThat(selectedFull.worldStartLocationEvidence().request().canonicalTenantId())
        .isEqualTo(selectedFull.canonicalTenantId());
    assertThat(selectedFull.worldStartLocationEvidence().request().canonicalVersionId())
        .isEqualTo(selectedFull.canonicalVersionId());
    assertThat(selectedFull.worldStartLocationEvidence().request().contentDigest())
        .isEqualTo(selectedFull.participantDigests().getFirst().contentDigest());
    assertThat(expectedFrozenGraphSchemaVersion(selectedFull)).isEqualTo(3);
  }

  @Test
  void rejectsUnsupportedOrMixedSelectedFullAttestationProfiles() throws Exception {
    assertThatThrownBy(() -> expectedFrozenGraphSchemaVersion(release(1)))
        .hasMessageContaining("no supported selected World graph profile");

    var selectedFull =
        release(AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
    assertThatThrownBy(
            () ->
                selectedFull(
                    selectedFull,
                    participantDigestsWithSchema(selectedFull, "ENTITY_MANAGEMENT", 2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
    assertThatThrownBy(
            () ->
                selectedFull(
                    selectedFull,
                    participantDigestsWithSchema(selectedFull, "AUTOMATION_SCRIPTING", 5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
    assertThatThrownBy(
            () ->
                selectedFull(
                    selectedFull,
                    participantDigestsWithSchema(selectedFull, "WORLD_MANAGEMENT", 3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Participant digest schema is unsupported");
  }

  private static int expectedFrozenGraphSchemaVersion(
      AuthoredWorldReleaseAttestationEvidence release) {
    try {
      Method selector =
          WorldCanonicalInstanceLifecycleReadRepository.class.getDeclaredMethod(
              "expectedFrozenGraphSchemaVersion", AuthoredWorldReleaseAttestationEvidence.class);
      selector.setAccessible(true);
      return (int) selector.invoke(null, release);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Frozen graph schema selection failed", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Frozen graph schema selector is unavailable", failure);
    }
  }

  private static AuthoredWorldReleaseAttestationEvidence release(int schemaVersion)
      throws Exception {
    WorldPublishedStartLocationEvidence selector =
        AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(schemaVersion)
            ? selectedWorldEvidence(
                schemaVersion
                            == AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION
                        || schemaVersion
                            == AuthoredWorldReleaseAttestationEvidence
                                .CLOSURE_SELECTOR_SCHEMA_VERSION
                    ? 4
                    : 3)
            : null;
    String commitId = selector == null ? "test-commit" : selector.request().appliedCommitId();
    UUID tenantId =
        selector == null
            ? UUID.fromString("11111111-1111-4111-8111-111111111111")
            : selector.request().canonicalTenantId();
    UUID versionId =
        selector == null
            ? UUID.fromString("22222222-2222-4222-8222-222222222222")
            : selector.request().canonicalVersionId();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner -> {
                  boolean gameLogic = "GAME_LOGIC".equals(owner);
                  String contentDigest =
                      "WORLD_MANAGEMENT".equals(owner) && selector != null
                          ? selector.request().contentDigest()
                          : "a".repeat(64);
                  return new AuthoredWorldReleaseAttestationEvidence.Participant(
                      owner,
                      "7",
                      false,
                      null,
                      commitId,
                      contentDigest,
                      AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                          owner, schemaVersion),
                      gameLogic,
                      gameLogic ? "sha256:" + "c".repeat(64) : null);
                })
            .toList();

    String descriptorDigest = "sha256:" + "b".repeat(64);
    String manifestHash = "sha256:" + "d".repeat(64);
    String namespace = selector == null ? "test" : selector.request().targetNamespace();
    String workflow = selector == null ? "test-workflow" : selector.request().publishWorkflowId();
    if (schemaVersion == AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION) {
      return AuthoredWorldReleaseAttestationEvidence.create(
          namespace,
          descriptorDigest,
          tenantId,
          versionId,
          "synthetic-world",
          UUID.fromString("33333333-3333-4333-8333-333333333333"),
          "sha256:" + "e".repeat(64),
          "test-descriptor",
          "test-release",
          1,
          workflow,
          commitId,
          participants,
          manifestHash,
          1,
          List.of(),
          List.of(),
          List.of(),
          "test-generation-config");
    }
    return switch (schemaVersion) {
      case AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION ->
          AuthoredWorldReleaseAttestationEvidence.create(
              namespace,
              descriptorDigest,
              tenantId,
              versionId,
              "synthetic-world",
              UUID.fromString("33333333-3333-4333-8333-333333333333"),
              "sha256:" + "e".repeat(64),
              "test-descriptor",
              "test-release",
              1,
              workflow,
              commitId,
              participants,
              manifestHash,
              1,
              List.of(),
              List.of(),
              List.of(),
              "test-generation-config",
              selector);
      case AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION ->
          AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
              namespace,
              descriptorDigest,
              tenantId,
              versionId,
              "synthetic-world",
              UUID.fromString("33333333-3333-4333-8333-333333333333"),
              "sha256:" + "e".repeat(64),
              "test-descriptor",
              "test-release",
              1,
              workflow,
              commitId,
              participants,
              manifestHash,
              1,
              List.of(),
              List.of(),
              List.of(),
              "test-generation-config",
              selector);
      case AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION ->
          AuthoredWorldReleaseAttestationEvidence.createSelectedFull(
              namespace,
              descriptorDigest,
              tenantId,
              versionId,
              "synthetic-world",
              UUID.fromString("33333333-3333-4333-8333-333333333333"),
              "sha256:" + "e".repeat(64),
              "test-descriptor",
              "test-release",
              1,
              workflow,
              commitId,
              participants,
              manifestHash,
              1,
              List.of(),
              List.of(),
              List.of(),
              "test-generation-config",
              selector);
      default -> throw new IllegalArgumentException("Unsupported test release schema");
    };
  }

  private static WorldPublishedStartLocationEvidence selectedWorldEvidence(int schemaVersion)
      throws Exception {
    // ISOLATED carrier/profile selection only: this stipulates the newer World digest schema.
    // It does not recompute a closure digest or prove a physical selected freeze/lifecycle read.
    var retained = WorldCanonicalPlayerLocationGrpcServiceTest.publishedSelector("test");
    var request = retained.request();
    if (schemaVersion == request.digestSchemaVersion()) return retained;
    var selectedRequest =
        new WorldPublishedStartLocationEvidence.Request(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            request.intakeRequestId(),
            request.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            request.versionStateEpoch(),
            request.publishWorkflowId(),
            request.appliedCommitId(),
            request.contentDigest(),
            schemaVersion,
            request.worldAffectedTuples());
    return new WorldPublishedStartLocationEvidence(
        selectedRequest,
        retained.selectorReceiptBytes(),
        retained.originalAccountBindingBytes(),
        retained.appliedResultBytes());
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Participant>
      participantDigestsWithSchema(
          AuthoredWorldReleaseAttestationEvidence release, String owner, int schemaVersion) {
    return release.participantDigests().stream()
        .map(
            participant ->
                new AuthoredWorldReleaseAttestationEvidence.Participant(
                    participant.participantKey(),
                    participant.scopeValue(),
                    participant.baseVersionIdPresent(),
                    participant.baseVersionId(),
                    participant.appliedCommitId(),
                    participant.contentDigest(),
                    owner.equals(participant.participantKey())
                        ? schemaVersion
                        : participant.digestSchemaVersion(),
                    participant.abilitySchemaDigestPresent(),
                    participant.abilitySchemaDigest()))
        .toList();
  }

  private static AuthoredWorldReleaseAttestationEvidence selectedFull(
      AuthoredWorldReleaseAttestationEvidence basis,
      List<AuthoredWorldReleaseAttestationEvidence.Participant> participants) {
    return AuthoredWorldReleaseAttestationEvidence.createSelectedFull(
        basis.targetNamespace(),
        basis.descriptorResultDigest(),
        basis.canonicalTenantId(),
        basis.canonicalVersionId(),
        basis.worldSlug(),
        basis.authoredWorldSourceOperationId(),
        basis.authoredWorldSourceEvidenceDigest(),
        basis.launchDescriptorId(),
        basis.publishedReleaseBundleRef(),
        basis.versionStateEpoch(),
        basis.publishWorkflowId(),
        basis.commitId(),
        participants,
        basis.manifestHash(),
        basis.manifestSchemaVersion(),
        basis.requiredManifestAssetKeys(),
        basis.artifactDigests(),
        basis.commandDefinitions(),
        basis.generationConfigRevision(),
        basis.worldStartLocationEvidence());
  }

  private static void validateFrozenGraphProfile(JsonNode graph, int expectedSchemaVersion) {
    try {
      Method validator =
          WorldCanonicalInstanceLifecycleReadRepository.class.getDeclaredMethod(
              "requireFrozenGraphProfile", JsonNode.class, int.class);
      validator.setAccessible(true);
      validator.invoke(null, graph, expectedSchemaVersion);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Frozen graph profile validation failed", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Frozen graph profile validator is unavailable", failure);
    }
  }

  private static ObjectNode graphV2() {
    ObjectNode graph = JSON.createObjectNode();
    graph.put("schemaVersion", "2");
    graph.put("canonicalTenantId", "11111111-1111-4111-8111-111111111111");
    graph.put("canonicalVersionId", "22222222-2222-4222-8222-222222222222");
    graph.putArray("rows");
    return graph;
  }

  private static ObjectNode graphV3() {
    ObjectNode graph = graphV2();
    graph.put("schemaVersion", "3");
    ObjectNode closure = graph.putObject("inboundSourceClosure");
    closure.put("schemaVersion", 1);
    var familyCounts = closure.putArray("familyCounts");
    for (var family : WorldDraftTopologyInputGraph.INBOUND_SOURCE_FAMILY_ORDER) {
      ObjectNode familyCount = familyCounts.addObject();
      familyCount.put("family", family.name());
      familyCount.put("count", 0);
    }
    return graph;
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request request() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "test",
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "starter-world",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "SHARED",
        true,
        "control-request",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }
}
