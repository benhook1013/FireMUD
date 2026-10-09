package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.util.JsonFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplication;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorldOriginalDraftGraphApplicationServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = id(1);
  private static final UUID VERSION = id(2);
  private static final UUID REGISTRATION_REQUEST = id(3);
  private static final UUID SOURCE_OPERATION = id(4);
  private static final UUID INTAKE_REQUEST = id(5);
  private static final UUID INTAKE_OPERATION = id(6);
  private static final UUID VERSION_READ_REQUEST = id(7);
  private static final UUID VERSION_IDENTITY_OPERATION = id(8);
  private static final UUID ACCOUNT_OPERATION = id(9);
  private static final UUID REQUEST = id(10);
  private static final UUID COMMIT = id(11);
  private static final UUID FENCE = id(12);
  private static final UUID ACTOR = id(13);
  private static final UUID REGION = id(14);
  private static final long GAME_DESIGN_VERSION_ROW = 42L;
  private static final long SOURCE_GAME_ROW = 81L;
  private static final String GAME_TENANT_KEY = "legacy-game-tenant-42";

  private final WorldAuthoredVersionIdentityRepository identities =
      mock(WorldAuthoredVersionIdentityRepository.class);
  private final WorldAuthoredSourceIntakeRepository intakes =
      mock(WorldAuthoredSourceIntakeRepository.class);
  private final WorldDraftGraphApplicationService graphApplications =
      mock(WorldDraftGraphApplicationService.class);
  private final WorldOriginalDraftGraphApplicationService service =
      new WorldOriginalDraftGraphApplicationService(identities, intakes, graphApplications);

  @Test
  void derivesRealTerminalOperationAndTopologyFromExactCommittedReceipts() {
    var source = source();
    var intake = intake(source);
    var identity = identity(intake);
    byte[] accountBytes = accountBinding(binding()).canonicalBytes();
    var result = mock(WorldDraftGraphAppliedResult.class);
    when(graphApplications.readCommitted(eq(NAMESPACE), any(byte[].class)))
        .thenReturn(Optional.empty());
    when(identities.readByCanonicalTarget(NAMESPACE, TENANT, VERSION, GAME_DESIGN_VERSION_ROW))
        .thenReturn(Optional.of(identity));
    when(intakes.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(intake));
    when(graphApplications.apply(any(WorldDraftGraphApplication.class))).thenReturn(result);

    assertThat(service.apply(NAMESPACE, accountBytes)).isSameAs(result);

    ArgumentCaptor<WorldDraftGraphApplication> application =
        ArgumentCaptor.forClass(WorldDraftGraphApplication.class);
    var order = inOrder(graphApplications, identities, intakes);
    order.verify(graphApplications).readCommitted(eq(NAMESPACE), any(byte[].class));
    order
        .verify(identities)
        .readByCanonicalTarget(NAMESPACE, TENANT, VERSION, GAME_DESIGN_VERSION_ROW);
    order.verify(intakes).read(NAMESPACE, INTAKE_REQUEST);
    order.verify(graphApplications).apply(application.capture());
    var applied = application.getValue();
    assertThat(applied.operation().operationId()).isEqualTo(ACCOUNT_OPERATION);
    assertThat(applied.operation().requestId()).isEqualTo(REQUEST);
    assertThat(applied.operation().commitId()).isEqualTo(COMMIT);
    assertThat(applied.operation().authorizationFenceId()).isEqualTo(FENCE);
    assertThat(applied.operation().accountBindingBytes()).isEqualTo(accountBytes);
    assertThat(applied.operation().ownerBinding())
        .isEqualTo(
            new OwnerBinding(
                NAMESPACE,
                TENANT,
                VERSION,
                VERSION_IDENTITY_OPERATION,
                GAME_DESIGN_VERSION_ROW,
                INTAKE_REQUEST,
                INTAKE_OPERATION,
                intake.requestDigest(),
                SOURCE_OPERATION,
                source.evidenceDigest(),
                intake.receiptDigest()));
    assertThat(applied.plan().binding().target().sourceGameRowId()).isEqualTo(SOURCE_GAME_ROW);
    assertThat(applied.plan().graph().nodes()).hasSize(1);
    assertThat(applied.plan().graph().nodes().getFirst().templateId()).isEqualTo(REGION);
  }

  @Test
  void exactReplayIsRecoveredBeforeAnyMutableIdentityOrIntakeLookup() {
    byte[] originalBytes = accountBinding(binding()).canonicalBytes();
    var prior = mock(WorldDraftGraphAppliedResult.class);
    when(graphApplications.readCommitted(eq(NAMESPACE), any(byte[].class)))
        .thenReturn(Optional.of(prior));

    assertThat(service.apply(NAMESPACE, originalBytes)).isSameAs(prior);

    var order = inOrder(graphApplications, identities, intakes);
    order.verify(graphApplications).readCommitted(eq(NAMESPACE), any(byte[].class));
    verifyNoInteractions(identities, intakes);
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));
  }

  @Test
  void missingIdentityOrAmbiguousIdentityDeniesWithoutSourceLookupOrApply() {
    byte[] originalBytes = accountBinding(binding()).canonicalBytes();
    when(graphApplications.readCommitted(eq(NAMESPACE), any(byte[].class)))
        .thenReturn(Optional.empty());
    when(identities.readByCanonicalTarget(NAMESPACE, TENANT, VERSION, GAME_DESIGN_VERSION_ROW))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.apply(NAMESPACE, originalBytes))
        .isInstanceOf(InvalidIdentityEvidenceException.class)
        .hasMessageContaining("identity receipt is missing");
    verifyNoInteractions(intakes);
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));

    when(identities.readByCanonicalTarget(NAMESPACE, TENANT, VERSION, GAME_DESIGN_VERSION_ROW))
        .thenThrow(
            new InvalidIdentityEvidenceException(
                "World authored-Version identity target has ambiguous World associations"));
    assertThatThrownBy(() -> service.apply(NAMESPACE, originalBytes))
        .isInstanceOf(InvalidIdentityEvidenceException.class)
        .hasMessageContaining("ambiguous");
    verifyNoInteractions(intakes);
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));
  }

  @Test
  void missingOrSubstitutedSourceAssociationDeniesBeforeApplying() {
    var source = source();
    var intake = intake(source);
    var identity = identity(intake);
    byte[] originalBytes = accountBinding(binding()).canonicalBytes();
    when(graphApplications.readCommitted(eq(NAMESPACE), any(byte[].class)))
        .thenReturn(Optional.empty());
    when(identities.readByCanonicalTarget(NAMESPACE, TENANT, VERSION, GAME_DESIGN_VERSION_ROW))
        .thenReturn(Optional.of(identity));

    when(intakes.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.apply(NAMESPACE, originalBytes))
        .isInstanceOf(InvalidIdentityEvidenceException.class)
        .hasMessageContaining("source intake association is missing");

    var substituted = intake(source("other-world", SOURCE_OPERATION));
    when(intakes.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(substituted));
    assertThatThrownBy(() -> service.apply(NAMESPACE, originalBytes))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("independently read source intake");
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));
  }

  @Test
  void sourceRowKeyProvenanceAndTargetScopeSubstitutionsAreDenied() {
    var source = source();
    var intake = intake(source);
    var identity = identity(intake);
    when(graphApplications.readCommitted(anyString(), any(byte[].class)))
        .thenReturn(Optional.empty());
    when(identities.readByCanonicalTarget(anyString(), any(UUID.class), any(UUID.class), anyLong()))
        .thenReturn(Optional.of(identity));
    when(intakes.read(anyString(), eq(INTAKE_REQUEST))).thenReturn(Optional.of(intake));

    byte[] wrongRow =
        accountBinding(binding(SOURCE_GAME_ROW + 1, GAME_TENANT_KEY, "NEW_GAME_ROW"))
            .canonicalBytes();
    assertThatThrownBy(() -> service.apply(NAMESPACE, wrongRow))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");

    byte[] wrongKey =
        accountBinding(binding(SOURCE_GAME_ROW, "substituted-key", "NEW_GAME_ROW"))
            .canonicalBytes();
    assertThatThrownBy(() -> service.apply(NAMESPACE, wrongKey))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");

    byte[] unsupportedProvenance =
        accountBinding(binding(SOURCE_GAME_ROW, GAME_TENANT_KEY, "RETAINED_GAME_V29"))
            .canonicalBytes();
    assertThatThrownBy(() -> service.apply(NAMESPACE, unsupportedProvenance))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("requires retained fresh-source provenance");

    assertThatThrownBy(() -> service.apply("other", accountBinding(binding()).canonicalBytes()))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");

    assertThatThrownBy(
            () ->
                service.apply(
                    NAMESPACE,
                    accountBinding(
                            binding(
                                id(22),
                                VERSION,
                                GAME_DESIGN_VERSION_ROW,
                                GAME_TENANT_KEY,
                                SOURCE_GAME_ROW,
                                GAME_TENANT_KEY,
                                "NEW_GAME_ROW"))
                        .canonicalBytes()))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");

    assertThatThrownBy(
            () ->
                service.apply(
                    NAMESPACE,
                    accountBinding(
                            binding(
                                TENANT,
                                id(23),
                                GAME_DESIGN_VERSION_ROW,
                                GAME_TENANT_KEY,
                                SOURCE_GAME_ROW,
                                GAME_TENANT_KEY,
                                "NEW_GAME_ROW"))
                        .canonicalBytes()))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");

    assertThatThrownBy(
            () ->
                service.apply(
                    NAMESPACE,
                    accountBinding(
                            binding(
                                TENANT,
                                VERSION,
                                GAME_DESIGN_VERSION_ROW + 1,
                                GAME_TENANT_KEY,
                                SOURCE_GAME_ROW,
                                GAME_TENANT_KEY,
                                "NEW_GAME_ROW"))
                        .canonicalBytes()))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("retained World source");
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));
  }

  @Test
  void accountAndDraftOwnerVectorsMustBeExactlyGameDesignAndWorld() {
    DraftCommitBinding binding = bindingWithExtraOwner();
    byte[] accountBytes = accountBinding(binding).canonicalBytes();
    when(graphApplications.readCommitted(eq(NAMESPACE), any(byte[].class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.apply(NAMESPACE, accountBytes))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("exact supported GD+World owners");
    verifyNoInteractions(identities, intakes);
    verify(graphApplications, never()).apply(any(WorldDraftGraphApplication.class));
  }

  private static DraftAuthorizationFenceBinding accountBinding(DraftCommitBinding binding) {
    byte[] complete = binding.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
        ACCOUNT_OPERATION,
        REQUEST,
        COMMIT,
        FENCE,
        ACTOR,
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.baseCommitId(),
        "0",
        complete,
        complete,
        binding.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT, ACTOR.toString(), "1", "1", null, null, new byte[] {1})),
        DraftAuthorizationFenceBinding.SCHEMA_V2,
        accountOwners(binding));
  }

  private static List<Owner> accountOwners(DraftCommitBinding binding) {
    var owners = new java.util.ArrayList<Owner>();
    owners.add(Owner.GAME_DESIGN);
    for (DraftCommitBinding.Owner owner : binding.requiredOwners()) {
      switch (owner) {
        case WORLD_MANAGEMENT -> owners.add(Owner.WORLD);
        case ENTITY_MANAGEMENT -> owners.add(Owner.ENTITY);
        case GAME_LOGIC -> owners.add(Owner.GAME_LOGIC);
        case AUTOMATION_SCRIPTING -> owners.add(Owner.AUTOMATION);
        case GAME_DESIGN_CONTROL_PLANE -> {}
      }
    }
    return List.copyOf(owners);
  }

  private static DraftCommitBinding binding() {
    return binding(SOURCE_GAME_ROW, GAME_TENANT_KEY, "NEW_GAME_ROW");
  }

  private static DraftCommitBinding binding(long sourceRow, String sourceKey, String provenance) {
    return binding(
        TENANT, VERSION, GAME_DESIGN_VERSION_ROW, sourceKey, sourceRow, sourceKey, provenance);
  }

  private static DraftCommitBinding binding(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      long gameDesignVersionRowId,
      String gameDesignVersionTenantKey,
      long sourceRow,
      String sourceKey,
      String provenance) {
    var mutation =
        WorldDesignMutationRevision.newBuilder()
            .setCommitId(COMMIT.toString())
            .setLogicalRevisionId(id(15).toString())
            .setAggregateId(REGION.toString())
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(REGION.toString())
            .setRegion(RegionDesignMutation.newBuilder().setName("North"))
            .build();
    try {
      return DraftCommitBinding.create(
          new DraftCommitBinding.TargetProof(
              canonicalTenantId,
              canonicalVersionId,
              gameDesignVersionRowId,
              gameDesignVersionTenantKey,
              sourceRow,
              sourceKey,
              provenance),
          REQUEST,
          COMMIT,
          "base-commit",
          List.of(
              new DraftCommitBinding.RevisionPayload(
                  "0", id(16), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "gd-original"),
              new DraftCommitBinding.RevisionPayload(
                  "1",
                  id(15),
                  DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                  JsonFormat.printer().print(mutation))),
          worldUnits());
    } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static DraftCommitBinding bindingWithExtraOwner() {
    DraftCommitBinding original = binding();
    var units = new java.util.ArrayList<>(original.affectedUnits());
    units.add(
        new AffectedUnit(
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
            "ENTITY",
            id(20).toString(),
            "AGGREGATE",
            id(20).toString(),
            "0"));
    var revisions = new java.util.ArrayList<>(original.revisions());
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "2", id(21), DraftCommitBinding.Owner.ENTITY_MANAGEMENT, "entity-original"));
    return DraftCommitBinding.create(
        original.target(), REQUEST, COMMIT, original.baseCommitId(), revisions, units);
  }

  private static List<AffectedUnit> worldUnits() {
    return List.of(
        new AffectedUnit(
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            "DRAFT",
            VERSION.toString(),
            "AGGREGATE",
            VERSION.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "AGGREGATE",
            REGION.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "REGION_SUBTREE",
            REGION.toString(),
            "0"));
  }

  private static AuthoredWorldSourceEvidence source() {
    return source("violet-wilds", SOURCE_OPERATION);
  }

  private static AuthoredWorldSourceEvidence source(String worldSlug, UUID sourceOperationId) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST, TENANT, "north-star", worldSlug, "Violet Wilds");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST,
            sourceOperationId,
            requestDigest,
            TENANT,
            "north-star",
            worldSlug,
            "Violet Wilds",
            SOURCE_GAME_ROW,
            GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST,
        sourceOperationId,
        requestDigest,
        TENANT,
        "north-star",
        worldSlug,
        "Violet Wilds",
        SOURCE_GAME_ROW,
        GAME_TENANT_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static WorldAuthoredSourceIntakeReceipt intake(AuthoredWorldSourceEvidence source) {
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, source);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        NAMESPACE,
        INTAKE_REQUEST,
        INTAKE_OPERATION,
        TENANT,
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, INTAKE_OPERATION, requestDigest, source, 91L),
        91L,
        source);
  }

  private static WorldAuthoredVersionIdentityReceipt identity(
      WorldAuthoredSourceIntakeReceipt intake) {
    var stateRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            VERSION_READ_REQUEST,
            TENANT,
            intake.worldSlug(),
            SOURCE_OPERATION,
            intake.sourceEvidenceDigest(),
            GAME_DESIGN_VERSION_ROW);
    var state =
        AuthoredWorldVersionStateEvidence.create(
            stateRequest,
            intake.source(),
            VERSION,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            3L);
    return new WorldAuthoredVersionIdentityReceipt(
        1, VERSION_IDENTITY_OPERATION, 101L, intake, state);
  }

  private static UUID id(int value) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", value, value));
  }
}
