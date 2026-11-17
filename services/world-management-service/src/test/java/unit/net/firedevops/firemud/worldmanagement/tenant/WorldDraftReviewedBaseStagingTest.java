package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Fence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Request;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Owner-shaped fixtures only: no genuine Game Design producer or Account authorization. */
class WorldDraftReviewedBaseStagingTest {
  private static final UUID TENANT = id(1);
  private static final UUID VERSION = id(2);
  private static final UUID REGION = id(3);
  private static final UUID BASE = id(4);
  private static final UUID COMMIT = id(5);
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private final WorldDraftGraphApplicationRepository repository =
      mock(WorldDraftGraphApplicationRepository.class);
  private final WorldDraftReviewedBaseStaging staging =
      new WorldDraftReviewedBaseStaging(repository);

  @Test
  void readsExactHistoricalFenceAndKeepsIncomingDeltaAndAccountBytesSeparate() {
    var incoming = incoming(BASE.toString());
    var operation = operation(incoming);
    var companion = companion(incoming, evidence(base(), "firemud", vector(base())));
    var graph = graph(TENANT, VERSION, true);
    when(repository.readSynchronized(companion.baseVisibility())).thenReturn(Optional.of(graph));

    var staged = staging.readAndStage("firemud", operation, companion);

    assertThat(staged.originalOperation()).isSameAs(operation);
    assertThat(staged.originalOperation().accountBindingBytes())
        .containsExactly(operation.accountBindingBytes());
    assertThat(staged.companion()).isSameAs(companion);
    assertThat(staged.historicalBase()).isSameAs(graph);
    assertThat(staged.historicalBase().rows().getFirst().content().getRegion().getName())
        .isEqualTo("Historical region");
    assertThat(staged.delta().revisions().getFirst().mutation().getRegion().getName())
        .isEqualTo("Incoming delta");
    assertThat(staged.delta().binding()).isSameAs(incoming);
    verify(repository).readSynchronized(companion.baseVisibility());
  }

  @Test
  void rejectsChangedCompleteReviewedIncomingBindingBeforeRead() {
    var incoming = incoming(BASE.toString());
    var changedRevisions = new ArrayList<>(incoming.revisions());
    changedRevisions.set(
        0, new DraftCommitBinding.RevisionPayload("0", id(10), Owner.GAME_LOGIC, "changed"));
    var changedUnits = new ArrayList<>(incoming.affectedUnits());
    changedUnits.set(
        0,
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "AGGREGATE",
            REGION.toString(),
            "9"));
    for (var changed :
        List.of(
            DraftCommitBinding.create(
                incoming.target(),
                id(99),
                COMMIT,
                BASE.toString(),
                incoming.revisions(),
                incoming.affectedUnits()),
            DraftCommitBinding.create(
                incoming.target(),
                incoming.requestId(),
                COMMIT,
                BASE.toString(),
                changedRevisions,
                incoming.affectedUnits()),
            DraftCommitBinding.create(
                incoming.target(),
                incoming.requestId(),
                COMMIT,
                BASE.toString(),
                incoming.revisions(),
                changedUnits))) {
      var companion = companion(changed, evidence(base(), "firemud", vector(base())));
      assertThatIllegalArgumentException()
          .isThrownBy(() -> staging.readAndStage("firemud", operation(incoming), companion))
          .withMessageContaining("unchanged complete Account input");
    }
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsGenesisOpaqueAndWrongExactBaseInsteadOfInferringWorldLineage() {
    for (String bad : List.of("genesis:" + BASE, "opaque-base", id(99).toString())) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> companion(incoming(bad), evidence(base(), "firemud", vector(base()))));
    }
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsChangedFullTargetEvenWhenCanonicalTenantAndVersionMatch() {
    var incoming = incoming(BASE.toString());
    var changed =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                TENANT, VERSION, 901, "source", 802, "source", "NEW_GAME_ROW"),
            incoming.requestId(),
            COMMIT,
            BASE.toString(),
            incoming.revisions(),
            incoming.affectedUnits());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> companion(changed, evidence(base(), "firemud", vector(base()))));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsDifferentBaseBindingAndFenceCommit() {
    var incoming = incoming(BASE.toString());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDraftReviewedBaseCompanion(
                    incoming,
                    new DraftBaseReference(DraftBaseReference.Kind.AUTHORED_COMMIT, id(99)),
                    DIGEST,
                    evidence(base(), "firemud", vector(base()))));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DraftSynchronizedVisibilityEvidence(
                    new Request(1, "firemud", id(80), base().target()),
                    base(),
                    "SYNCHRONIZED",
                    new Fence(
                        base().requestId(),
                        id(99),
                        base().digest(),
                        vector(base()),
                        OffsetDateTime.now())));
  }

  @Test
  void rejectsWrongNamespaceBeforeStorageRead() {
    var incoming = incoming(BASE.toString());
    for (String namespace : List.of("other", "INVALID")) {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  staging.readAndStage(
                      namespace,
                      operation(incoming),
                      companion(incoming, evidence(base(), "firemud", vector(base())))));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                staging.readAndStage(
                    "firemud",
                    operation(incoming),
                    companion(incoming, evidence(base(), "other", vector(base())))));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMissingInvalidAndNoncanonicalRetainedReviewDigest() {
    for (String bad : List.of("", "sha256:" + "A".repeat(64), base().digest() + " ")) {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new WorldDraftReviewedBaseCompanion(
                      incoming(BASE.toString()),
                      DraftBaseReference.parse(BASE.toString()),
                      bad,
                      evidence(base(), "firemud", vector(base()))));
    }
  }

  @Test
  void rejectsPartialOwnerVectorNonAppliedAndMismatchedOwnerEpochs() {
    String complete = vector(base());
    for (String bad :
        List.of(
            "[]",
            complete.replace("APPLIED", "IN_PROGRESS"),
            complete.replace("\"resultingEpoch\":\"4\"", "\"resultingEpoch\":\"9\""),
            complete.replace(
                "\"bindingDigest\":\"" + base().digest(), "\"bindingDigest\":\"" + DIGEST))) {
      assertThatIllegalArgumentException().isThrownBy(() -> evidence(base(), "firemud", bad));
    }
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsBaseWithoutWorldOwnerInsteadOfTreatingItAsEmptyWorld() {
    var base = base();
    var noWorld =
        DraftCommitBinding.create(
            base.target(),
            base.requestId(),
            BASE,
            base.baseCommitId(),
            List.of(
                base.revisions().getFirst(),
                new DraftCommitBinding.RevisionPayload(
                    "1", id(12), Owner.GAME_DESIGN_CONTROL_PLANE, "control")),
            base.affectedUnits().stream()
                .filter(u -> u.owner() != Owner.WORLD_MANAGEMENT)
                .toList());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence(noWorld, "firemud", vector(noWorld)));
  }

  @Test
  void propagatesExactStorageResultMismatchWithoutFallback() {
    var incoming = incoming(BASE.toString());
    var companion = companion(incoming, evidence(base(), "firemud", vector(base())));
    var mismatch = new ConflictException("World synchronized owner result differs");
    when(repository.readSynchronized(companion.baseVisibility())).thenThrow(mismatch);
    assertThatThrownBy(() -> staging.readAndStage("firemud", operation(incoming), companion))
        .isSameAs(mismatch);
    verify(repository).readSynchronized(companion.baseVisibility());
  }

  @Test
  void refusesMissingHistoricalSnapshot() {
    var incoming = incoming(BASE.toString());
    var companion = companion(incoming, evidence(base(), "firemud", vector(base())));
    when(repository.readSynchronized(companion.baseVisibility())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> staging.readAndStage("firemud", operation(incoming), companion))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void refusesMissingRegionAndCrossTargetReadback() {
    var incoming = incoming(BASE.toString());
    var companion = companion(incoming, evidence(base(), "firemud", vector(base())));
    for (var graph :
        List.of(
            graph(TENANT, VERSION, false),
            graph(id(99), VERSION, true),
            graph(TENANT, id(99), true))) {
      when(repository.readSynchronized(companion.baseVisibility())).thenReturn(Optional.of(graph));
      assertThatThrownBy(() -> staging.readAndStage("firemud", operation(incoming), companion))
          .isInstanceOf(ConflictException.class);
    }
  }

  @Test
  void actualSynchronizedReaderRefusesAbsentAppliedCarrier() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenReturn(DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(new org.jooq.Field<?>[0]));
    var actual =
        new WorldDraftGraphApplicationRepository(
            dsl, mock(WorldDesignPublicationFenceRepository.class), new ObjectMapper());
    var incoming = incoming(BASE.toString());
    assertThatThrownBy(
            () ->
                new WorldDraftReviewedBaseStaging(actual)
                    .readAndStage(
                        "firemud",
                        operation(incoming),
                        companion(incoming, evidence(base(), "firemud", vector(base())))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("no canonical World APPLIED");
  }

  @Test
  void actualSynchronizedReaderRequiresPreTransactionCommittedRead() {
    var actual =
        new WorldDraftGraphApplicationRepository(
            mock(DSLContext.class),
            mock(WorldDesignPublicationFenceRepository.class),
            new ObjectMapper());
    var incoming = incoming(BASE.toString());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  new WorldDraftReviewedBaseStaging(actual)
                      .readAndStage(
                          "firemud",
                          operation(incoming),
                          companion(incoming, evidence(base(), "firemud", vector(base())))))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("committed read");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private static WorldDraftReviewedBaseCompanion companion(
      DraftCommitBinding incoming, DraftSynchronizedVisibilityEvidence evidence) {
    return new WorldDraftReviewedBaseCompanion(
        incoming, DraftBaseReference.parse(incoming.baseCommitId()), DIGEST, evidence);
  }

  private static DraftCommitBinding incoming(String base) {
    return binding(COMMIT, id(6), base, mutation("Incoming delta", COMMIT), "4", "7");
  }

  private static DraftCommitBinding base() {
    return binding(BASE, id(7), id(8).toString(), mutation("Historical region", BASE), "3", "6");
  }

  private static DraftCommitBinding binding(
      UUID commit,
      UUID request,
      String base,
      WorldDesignMutationRevision mutation,
      String aggregateEpoch,
      String scopeEpoch) {
    try {
      return DraftCommitBinding.create(
          new DraftCommitBinding.TargetProof(
              TENANT, VERSION, 901, "source", 801, "source", "NEW_GAME_ROW"),
          request,
          commit,
          base,
          List.of(
              new DraftCommitBinding.RevisionPayload("0", id(10), Owner.GAME_LOGIC, "opaque"),
              new DraftCommitBinding.RevisionPayload(
                  "1", id(11), Owner.WORLD_MANAGEMENT, JsonFormat.printer().print(mutation)),
              new DraftCommitBinding.RevisionPayload(
                  "2", id(12), Owner.GAME_DESIGN_CONTROL_PLANE, "control")),
          List.of(
              new AffectedUnit(
                  Owner.WORLD_MANAGEMENT,
                  "REGION",
                  REGION.toString(),
                  "AGGREGATE",
                  REGION.toString(),
                  aggregateEpoch),
              new AffectedUnit(
                  Owner.WORLD_MANAGEMENT,
                  "REGION",
                  REGION.toString(),
                  "REGION_SUBTREE",
                  REGION.toString(),
                  scopeEpoch),
              new AffectedUnit(Owner.GAME_LOGIC, "SCRIPT", "script", "AGGREGATE", "script", "0"),
              new AffectedUnit(
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  "SOURCE",
                  "source",
                  "AGGREGATE",
                  "source",
                  "0")));
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  private static WorldDesignMutationRevision mutation(String name, UUID commit) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(id(11).toString())
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setAggregateId(REGION.toString())
        .setScopeId(REGION.toString())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setExpectedDraftRevisionEpoch(4)
        .setExpectedDraftScopeRevisionEpoch(7)
        .setRegion(RegionDesignMutation.newBuilder().setName(name))
        .build();
  }

  private static WorldDraftTerminalOperation operation(DraftCommitBinding binding) {
    var account =
        new DraftAuthorizationFenceBinding(
                id(20),
                binding.requestId(),
                binding.commitId(),
                id(21),
                id(22),
                TENANT,
                VERSION,
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new DraftAuthorizationFenceBinding.SourceEvidence(
                        DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                        id(22).toString(),
                        "1",
                        "1",
                        null,
                        null,
                        new byte[] {1})))
            .withRequiredOwners();
    return new WorldDraftTerminalOperation(
        id(20),
        binding.requestId(),
        binding.commitId(),
        id(21),
        TENANT,
        VERSION,
        binding,
        new OwnerBinding(
            "firemud", TENANT, VERSION, id(50), 901, id(51), id(52), DIGEST, id(53), DIGEST,
            DIGEST),
        account.canonicalBytes());
  }

  private static DraftSynchronizedVisibilityEvidence evidence(
      DraftCommitBinding binding, String namespace, String vector) {
    return new DraftSynchronizedVisibilityEvidence(
        new Request(1, namespace, id(80), binding.target()),
        binding,
        "SYNCHRONIZED",
        new Fence(
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            vector,
            OffsetDateTime.now()));
  }

  private static String vector(DraftCommitBinding binding) {
    var vector =
        binding.requiredOwners().stream()
            .map(
                owner ->
                    Map.of(
                        "owner",
                        owner.name(),
                        "status",
                        "APPLIED",
                        "commitId",
                        binding.commitId().toString(),
                        "bindingDigest",
                        binding.digest(),
                        "resultIdentity",
                        "fixture-" + owner,
                        "resultBytesBase64",
                        Base64.getEncoder().encodeToString(new byte[] {1, 2}),
                        "appliedEpochs",
                        binding.affectedUnits(owner).stream()
                            .map(
                                unit ->
                                    Map.of(
                                        "aggregateType",
                                        unit.aggregateType(),
                                        "aggregateId",
                                        unit.aggregateId(),
                                        "scopeType",
                                        unit.scopeType(),
                                        "scopeId",
                                        unit.scopeId(),
                                        "expectedEpoch",
                                        unit.expectedEpoch(),
                                        "resultingEpoch",
                                        new BigInteger(unit.expectedEpoch())
                                            .add(BigInteger.ONE)
                                            .toString()))
                            .toList()))
            .toList();
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(vector)),
          java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  private static WorldCanonicalAuthoredGraph graph(UUID tenant, UUID version, boolean region) {
    var mutation = mutation("Historical region", BASE);
    var node = new WorldDraftTopologyInputGraph.Node("1", id(11), REGION, REGION, mutation, null);
    return new WorldCanonicalAuthoredGraph(
        tenant,
        version,
        100,
        200,
        region
            ? List.of(new Row(new Template(Family.REGION, REGION), 300, 400, node, mutation))
            : List.of());
  }

  private static UUID id(int value) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", value, value));
  }
}
