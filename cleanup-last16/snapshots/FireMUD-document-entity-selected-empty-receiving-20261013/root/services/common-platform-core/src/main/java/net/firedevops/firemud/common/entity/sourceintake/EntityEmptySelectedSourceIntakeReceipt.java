package net.firedevops.firemud.common.entity.sourceintake;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import tools.jackson.databind.json.JsonMapper;

/** Immutable local receipt for a freshly founded, explicitly empty Entity source catalogue. */
public final class EntityEmptySelectedSourceIntakeReceipt {
  public static final String DOMAIN = "entity-selected-empty-source-catalogue/v1";
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_BYTES = 80 * 1024 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String targetNamespace;
  private final UUID genesisId;
  private final UUID operationId;
  private final UUID fenceId;
  private final UUID intakeRequestId;
  private final UUID canonicalTenantId;
  private final UUID canonicalVersionId;
  private final UUID selectedCommitId;
  private final UUID sourceRevisionId;
  private final String sourceRevisionOrder;
  private final long localTenantKey;
  private final long localVersionKey;
  private final String requestDigest;
  private final String schemaDigest;
  private final String authorizationBindingDigest;
  private final byte[] authorizationBindingBytes;
  private final String selectedSourceDigest;
  private final byte[] selectedSourceBytes;
  private final String selectedSourceRevisionBindingDigest;
  private final byte[] selectedSourceRevisionBindingBytes;
  private final UUID worldReadRequestId;
  private final String worldReadRequestDigest;
  private final byte[] worldReadRequestBytes;
  private final String worldClosureDigest;
  private final byte[] worldClosureBytes;
  private final List<FamilyCensus> familyStates;
  private final OffsetDateTime retainedAt;
  private final byte[] canonicalBytes;
  private final String receiptDigest;
  private final SelectedOwnerIntakeAuthorizationBinding authorizationBinding;
  private final SelectedOwnerEmptySourceInputs inputs;

  private EntityEmptySelectedSourceIntakeReceipt(
      String targetNamespace,
      UUID genesisId,
      long localTenantKey,
      long localVersionKey,
      String requestDigest,
      String schemaDigest,
      byte[] authorizationBindingBytes,
      String authorizationBindingDigest,
      byte[] selectedSourceBytes,
      String selectedSourceDigest,
      byte[] selectedSourceRevisionBindingBytes,
      String selectedSourceRevisionBindingDigest,
      UUID worldReadRequestId,
      byte[] worldReadRequestBytes,
      String worldReadRequestDigest,
      byte[] worldClosureBytes,
      String worldClosureDigest,
      List<FamilyCensus> familyStates,
      OffsetDateTime retainedAt) {
    this.targetNamespace = requireText(targetNamespace, "targetNamespace");
    this.genesisId = requireUuid(genesisId, "genesisId");
    if (localTenantKey <= 0L || localVersionKey <= 0L || localTenantKey == localVersionKey) {
      throw new IllegalArgumentException("Distinct positive Entity local keys are required");
    }
    this.localTenantKey = localTenantKey;
    this.localVersionKey = localVersionKey;
    this.requestDigest = requireDigest(requestDigest, "requestDigest");
    this.authorizationBindingBytes =
        boundedBytes(
            authorizationBindingBytes,
            SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES,
            "authorizationBindingBytes");
    this.authorizationBinding =
        SelectedOwnerIntakeAuthorizationBinding.fromStored(this.authorizationBindingBytes);
    this.authorizationBindingDigest =
        requireDigest(authorizationBindingDigest, "authorizationBindingDigest");
    if (!this.authorizationBinding.digest().equals(this.authorizationBindingDigest)) {
      throw new IllegalArgumentException("Retained Account authorization digest differs");
    }
    this.selectedSourceBytes =
        boundedBytes(
            selectedSourceBytes, SelectedOwnerIntakeSourceContent.MAX_BYTES, "selectedSourceBytes");
    this.selectedSourceDigest = requireDigest(selectedSourceDigest, "selectedSourceDigest");
    if (!Arrays.equals(
            this.selectedSourceBytes, this.authorizationBinding.content().canonicalBytes())
        || !this.selectedSourceDigest.equals(this.authorizationBinding.content().digest())) {
      throw new IllegalArgumentException(
          "Selected source differs from its original Account binding");
    }
    this.inputs =
        decodeInputs(
            this.authorizationBinding,
            worldReadRequestId,
            worldReadRequestBytes,
            worldReadRequestDigest,
            worldClosureBytes,
            worldClosureDigest);
    this.selectedSourceRevisionBindingBytes =
        boundedBytes(
            selectedSourceRevisionBindingBytes,
            4 * 1024 * 1024,
            "selectedSourceRevisionBindingBytes");
    this.selectedSourceRevisionBindingDigest =
        requireDigest(selectedSourceRevisionBindingDigest, "selectedSourceRevisionBindingDigest");
    var sourceDeclaration = this.inputs.ownerSourceInventoryDeclaration();
    byte[] expectedRevisionBinding = sourceDeclaration.sourceBinding().canonicalBytes();
    if (!Arrays.equals(this.selectedSourceRevisionBindingBytes, expectedRevisionBinding)
        || !this.selectedSourceRevisionBindingDigest.equals(
            sourceDeclaration.sourceBinding().digest())) {
      throw new IllegalArgumentException(
          "Selected source revision binding differs from the original Game Design source");
    }
    this.operationId = requireUuid(this.authorizationBinding.operationId(), "operationId");
    this.fenceId = requireUuid(this.authorizationBinding.fenceId(), "fenceId");
    this.intakeRequestId =
        requireUuid(this.authorizationBinding.intakeRequestId(), "intakeRequestId");
    this.canonicalTenantId = requireUuid(this.authorizationBinding.tenantId(), "canonicalTenantId");
    this.canonicalVersionId =
        requireUuid(this.authorizationBinding.versionId(), "canonicalVersionId");
    this.selectedCommitId =
        requireUuid(this.authorizationBinding.selected().commitId(), "selectedCommitId");
    this.sourceRevisionId = requireUuid(sourceDeclaration.revisionId(), "sourceRevisionId");
    this.sourceRevisionOrder =
        requireDecimal(sourceDeclaration.revisionOrder(), "sourceRevisionOrder");
    this.worldReadRequestId = requireUuid(worldReadRequestId, "worldReadRequestId");
    this.worldReadRequestBytes =
        boundedBytes(
            worldReadRequestBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES,
            "worldReadRequestBytes");
    this.worldReadRequestDigest = requireDigest(worldReadRequestDigest, "worldReadRequestDigest");
    if (!DraftAuthorizationFenceBinding.digest(this.worldReadRequestBytes)
        .equals(this.worldReadRequestDigest)) {
      throw new IllegalArgumentException("Retained World read request digest differs");
    }
    this.worldClosureBytes =
        boundedBytes(
            worldClosureBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES,
            "worldClosureBytes");
    this.worldClosureDigest = requireDigest(worldClosureDigest, "worldClosureDigest");
    if (!this.inputs
        .worldInventoryReadEvidence()
        .inventory()
        .digest()
        .equals(this.worldClosureDigest)) {
      throw new IllegalArgumentException("Retained World closure digest differs");
    }
    if (!Arrays.equals(
        this.inputs.worldInventoryReadEvidence().inventory().canonicalBytes(),
        this.worldClosureBytes)) {
      throw new IllegalArgumentException("Retained World closure bytes differ");
    }
    this.familyStates =
        requireAllEmptyStates(
            familyStates, familyNames(sourceDeclaration.entityInventory().canonicalJson()));
    this.schemaDigest = requireDigest(schemaDigest, "schemaDigest");
    if (!schemaDigest.equals(
        schemaDigest(familyNames(sourceDeclaration.entityInventory().canonicalJson())))) {
      throw new IllegalArgumentException("Entity source catalogue schema digest differs");
    }
    requireSameNamespace(this.targetNamespace, this.authorizationBinding);
    if (this.authorizationBinding.owner() != Owner.ENTITY_MANAGEMENT
        || !this.authorizationBinding.schema().equals("account-entity-intake-authorization/v1")
        || !this.authorizationBinding.purpose().equals("ENTITY_INTAKE_RETENTION")) {
      throw new IllegalArgumentException("Exact original Entity Account authorization is required");
    }
    this.retainedAt = Objects.requireNonNull(retainedAt, "retainedAt");
    if (retainedAt.getOffset().getTotalSeconds() != 0) {
      throw new IllegalArgumentException("Receipt timestamp must use UTC");
    }
    if (!this.requestDigest.equals(
        requestDigest(
            targetNamespace,
            authorizationBinding,
            this.inputs.worldInventoryReadEvidence().request().freezeEvidence()))) {
      throw new IllegalArgumentException("Entity source intake request digest differs");
    }
    this.canonicalBytes = encode();
    if (this.canonicalBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Entity source catalogue receipt exceeds its size limit");
    }
    this.receiptDigest = DraftAuthorizationFenceBinding.digest(this.canonicalBytes);
  }

  public static EntityEmptySelectedSourceIntakeReceipt create(
      SelectedOwnerEmptySourceInputs inputs,
      UUID genesisId,
      long localTenantKey,
      long localVersionKey,
      String requestDigest,
      List<FamilyCensus> familyStates,
      OffsetDateTime retainedAt) {
    Objects.requireNonNull(inputs, "validated selected empty-source inputs are required");
    var binding = inputs.authorizationBinding();
    var worldEvidence = inputs.worldInventoryReadEvidence();
    byte[] worldReadRequest =
        SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(worldEvidence.request()).toByteArray();
    byte[] sourceRevisionBinding =
        inputs.ownerSourceInventoryDeclaration().sourceBinding().canonicalBytes();
    return new EntityEmptySelectedSourceIntakeReceipt(
        binding.targetNamespace(),
        genesisId,
        localTenantKey,
        localVersionKey,
        requestDigest,
        schemaDigest(
            familyNames(
                inputs.ownerSourceInventoryDeclaration().entityInventory().canonicalJson())),
        binding.canonicalBytes(),
        binding.digest(),
        inputs.sourceContent().canonicalBytes(),
        inputs.sourceContent().digest(),
        sourceRevisionBinding,
        inputs.ownerSourceInventoryDeclaration().sourceBinding().digest(),
        worldEvidence.request().readRequestId(),
        worldReadRequest,
        DraftAuthorizationFenceBinding.digest(worldReadRequest),
        worldEvidence.inventory().canonicalBytes(),
        worldEvidence.inventory().digest(),
        familyStates,
        retainedAt);
  }

  /** Decodes and fully revalidates the immutable canonical owner receipt. */
  public static EntityEmptySelectedSourceIntakeReceipt fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Stored Entity source catalogue receipt size is invalid");
    }
    byte[] stored = bytes.clone();
    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(DOMAIN);
    if (!Integer.toString(SCHEMA_VERSION).equals(reader.text())) {
      throw new IllegalArgumentException("Unsupported Entity source catalogue schema");
    }
    String schemaDigest = reader.text();
    String namespace = reader.text();
    byte[] authorizationBindingBytes = reader.bytes();
    String authorizationBindingDigest = reader.text();
    byte[] selectedSourceBytes = reader.bytes();
    String selectedSourceDigest = reader.text();
    byte[] selectedSourceRevisionBindingBytes = reader.bytes();
    String selectedSourceRevisionBindingDigest = reader.text();
    byte[] worldReadRequestBytes = reader.bytes();
    String worldReadRequestDigest = reader.text();
    byte[] worldClosureBytes = reader.bytes();
    String worldClosureDigest = reader.text();
    long localTenantKey = parsePositiveLong(reader.text(), "localTenantKey");
    long localVersionKey = parsePositiveLong(reader.text(), "localVersionKey");
    int stateCount = parsePositiveInt(reader.text(), "familyStateCount");
    if (stateCount != 23) {
      throw new IllegalArgumentException("Entity receipt must retain all 23 typed family states");
    }
    var states = new ArrayList<FamilyCensus>(stateCount);
    for (int index = 0; index < stateCount; index++) {
      states.add(
          new FamilyCensus(
              reader.text(),
              FamilyState.valueOf(reader.text()),
              FamilyEvidenceKind.valueOf(reader.text()),
              parseNonnegativeLong(reader.text(), "rowCount"),
              parseNonnegativeLong(reader.text(), "unqualifiedRowCount"),
              parseNonnegativeLong(reader.text(), "retainedRowCount"),
              parseNonnegativeLong(reader.text(), "selectedScopeRowCount"),
              parseNonnegativeLong(reader.text(), "referenceCount")));
    }
    UUID genesisId = parseUuid(reader.text(), "genesisId");
    String requestDigest = reader.text();
    UUID worldReadRequestId = parseUuid(reader.text(), "worldReadRequestId");
    OffsetDateTime retainedAt = parseTimestamp(reader.text());
    reader.requireEnd();
    var result =
        new EntityEmptySelectedSourceIntakeReceipt(
            namespace,
            genesisId,
            localTenantKey,
            localVersionKey,
            requestDigest,
            schemaDigest,
            authorizationBindingBytes,
            authorizationBindingDigest,
            selectedSourceBytes,
            selectedSourceDigest,
            selectedSourceRevisionBindingBytes,
            selectedSourceRevisionBindingDigest,
            worldReadRequestId,
            worldReadRequestBytes,
            worldReadRequestDigest,
            worldClosureBytes,
            worldClosureDigest,
            states,
            retainedAt);
    if (!Arrays.equals(stored, result.canonicalBytes)) {
      throw new IllegalArgumentException("Noncanonical stored Entity source catalogue receipt");
    }
    return result;
  }

  public String targetNamespace() {
    return targetNamespace;
  }

  public UUID genesisId() {
    return genesisId;
  }

  public UUID operationId() {
    return operationId;
  }

  public UUID fenceId() {
    return fenceId;
  }

  public UUID intakeRequestId() {
    return intakeRequestId;
  }

  public UUID canonicalTenantId() {
    return canonicalTenantId;
  }

  public UUID canonicalVersionId() {
    return canonicalVersionId;
  }

  public UUID selectedCommitId() {
    return selectedCommitId;
  }

  public UUID sourceRevisionId() {
    return sourceRevisionId;
  }

  public String sourceRevisionOrder() {
    return sourceRevisionOrder;
  }

  public long localTenantKey() {
    return localTenantKey;
  }

  public long localVersionKey() {
    return localVersionKey;
  }

  public String requestDigest() {
    return requestDigest;
  }

  public String schemaDigest() {
    return schemaDigest;
  }

  public String authorizationBindingDigest() {
    return authorizationBindingDigest;
  }

  public byte[] authorizationBindingBytes() {
    return authorizationBindingBytes.clone();
  }

  public String selectedSourceDigest() {
    return selectedSourceDigest;
  }

  public String selectedSourceRevisionBindingDigest() {
    return selectedSourceRevisionBindingDigest;
  }

  public byte[] selectedSourceBytes() {
    return selectedSourceBytes.clone();
  }

  public UUID worldReadRequestId() {
    return worldReadRequestId;
  }

  public String worldReadRequestDigest() {
    return worldReadRequestDigest;
  }

  public byte[] worldReadRequestBytes() {
    return worldReadRequestBytes.clone();
  }

  public String worldClosureDigest() {
    return worldClosureDigest;
  }

  public byte[] worldClosureBytes() {
    return worldClosureBytes.clone();
  }

  /** Digest of one persisted EMPTY-only provider row, bound to this exact retained source. */
  public String providerStateDigest(String family) {
    if (evidenceKindForFamily(family) != FamilyEvidenceKind.EMPTY_ONLY_OWNER_PROVIDER) {
      throw new IllegalArgumentException("Family is not backed by an EMPTY-only Entity provider");
    }
    var out = new ByteArrayOutputStream();
    frame(out, "entity-empty-only-family-provider-state/v1");
    frame(out, Integer.toString(SCHEMA_VERSION));
    frame(out, targetNamespace);
    frame(out, authorizationBindingBytes);
    frame(out, authorizationBindingDigest);
    frame(out, selectedSourceBytes);
    frame(out, selectedSourceDigest);
    frame(out, selectedSourceRevisionBindingBytes);
    frame(out, selectedSourceRevisionBindingDigest);
    frame(out, worldClosureBytes);
    frame(out, worldClosureDigest);
    frame(out, Long.toString(localTenantKey));
    frame(out, Long.toString(localVersionKey));
    frame(out, genesisId.toString());
    frame(out, family);
    frame(out, "providerSchemaVersion=1");
    frame(out, FamilyState.EMPTY.name());
    frame(out, "rowCount=0");
    frame(out, "referenceCount=0");
    return DraftAuthorizationFenceBinding.digest(out.toByteArray());
  }

  public List<FamilyCensus> familyStates() {
    return List.copyOf(familyStates);
  }

  public OffsetDateTime retainedAt() {
    return retainedAt;
  }

  public String receiptDigest() {
    return receiptDigest;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String outcome() {
    return "COMMITTED_EMPTY";
  }

  public String sourceParticipationDisposition() {
    return "PENDING_OWNER_TERMINAL_SETTLEMENT";
  }

  public SelectedOwnerIntakeAuthorizationBinding authorizationBinding() {
    return authorizationBinding;
  }

  public SelectedOwnerEmptySourceInputs inputs() {
    return inputs;
  }

  public void requireSameRequest(
      String namespace,
      SelectedOwnerIntakeAuthorizationBinding candidateBinding,
      WorldSelectedDraftPublicationFreezeEvidence candidateFreeze) {
    Objects.requireNonNull(candidateBinding, "candidate Account binding is required");
    Objects.requireNonNull(candidateFreeze, "candidate World freeze is required");
    if (!targetNamespace.equals(namespace)
        || !requestDigest.equals(requestDigest(namespace, candidateBinding, candidateFreeze))
        || !Arrays.equals(authorizationBindingBytes, candidateBinding.canonicalBytes())
        || !inputs
            .worldInventoryReadEvidence()
            .request()
            .freezeEvidence()
            .equals(candidateFreeze)) {
      throw new IllegalStateException(
          "Entity source request identity already records different selected evidence");
    }
  }

  public void requireSameInputs(SelectedOwnerEmptySourceInputs candidate) {
    Objects.requireNonNull(candidate, "validated selected empty-source inputs are required");
    requireSameRequest(
        candidate.authorizationBinding().targetNamespace(),
        candidate.authorizationBinding(),
        candidate.worldInventoryReadEvidence().request().freezeEvidence());
    if (!Arrays.equals(
            worldClosureBytes, candidate.worldInventoryReadEvidence().inventory().canonicalBytes())
        || !worldClosureDigest.equals(candidate.worldInventoryReadEvidence().inventory().digest())
        || !Arrays.equals(selectedSourceBytes, candidate.sourceContent().canonicalBytes())
        || !selectedSourceDigest.equals(candidate.sourceContent().digest())) {
      throw new IllegalStateException(
          "Entity exact retry changed selected source or World closure");
    }
  }

  /** Stable request identity; the fresh World read correlation is intentionally excluded. */
  public static String requestDigest(
      String namespace,
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    requireText(namespace, "targetNamespace");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");
    var out = new ByteArrayOutputStream();
    frame(out, "entity-selected-empty-source-catalogue-request/v1");
    frame(out, namespace);
    frame(out, binding.canonicalBytes());
    frame(
        out,
        net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec
            .toRequest(freezeEvidence.request())
            .toByteArray());
    frame(
        out,
        net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec
            .toResponse(freezeEvidence.acknowledgement())
            .toByteArray());
    return DraftAuthorizationFenceBinding.digest(out.toByteArray());
  }

  public static String schemaDigest(String canonicalEntityDeclaration) {
    return schemaDigest(familyNames(canonicalEntityDeclaration));
  }

  private byte[] encode() {
    var out = new ByteArrayOutputStream();
    frame(out, DOMAIN);
    frame(out, Integer.toString(SCHEMA_VERSION));
    frame(out, schemaDigest);
    frame(out, targetNamespace);
    frame(out, authorizationBindingBytes);
    frame(out, authorizationBindingDigest);
    frame(out, selectedSourceBytes);
    frame(out, selectedSourceDigest);
    frame(out, selectedSourceRevisionBindingBytes);
    frame(out, selectedSourceRevisionBindingDigest);
    frame(out, worldReadRequestBytes);
    frame(out, worldReadRequestDigest);
    frame(out, worldClosureBytes);
    frame(out, worldClosureDigest);
    frame(out, Long.toString(localTenantKey));
    frame(out, Long.toString(localVersionKey));
    frame(out, Integer.toString(familyStates.size()));
    for (FamilyCensus state : familyStates) {
      frame(out, state.family());
      frame(out, state.state().name());
      frame(out, state.evidenceKind().name());
      frame(out, Long.toString(state.rowCount()));
      frame(out, Long.toString(state.unqualifiedRowCount()));
      frame(out, Long.toString(state.retainedRowCount()));
      frame(out, Long.toString(state.selectedScopeRowCount()));
      frame(out, Long.toString(state.referenceCount()));
    }
    frame(out, genesisId.toString());
    frame(out, requestDigest);
    frame(out, worldReadRequestId.toString());
    frame(out, retainedAt.toString());
    return out.toByteArray();
  }

  private static SelectedOwnerEmptySourceInputs decodeInputs(
      SelectedOwnerIntakeAuthorizationBinding binding,
      UUID readRequestId,
      byte[] requestBytes,
      String requestDigest,
      byte[] closureBytes,
      String closureDigest) {
    UUID checkedRequestId = requireUuid(readRequestId, "worldReadRequestId");
    byte[] boundedRequest =
        boundedBytes(
            requestBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES,
            "worldReadRequestBytes");
    if (!DraftAuthorizationFenceBinding.digest(boundedRequest)
        .equals(requireDigest(requestDigest, "worldReadRequestDigest"))) {
      throw new IllegalArgumentException("Retained World read request digest differs");
    }
    final ReadSelectedOwnerWorldInventoryRequest requestMessage;
    try {
      requestMessage = ReadSelectedOwnerWorldInventoryRequest.parseFrom(boundedRequest);
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Retained World read request is malformed", invalid);
    }
    if (!Arrays.equals(boundedRequest, requestMessage.toByteArray())) {
      throw new IllegalArgumentException("Retained World read request is not canonical");
    }
    var request = SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(requestMessage);
    if (!request.readRequestId().equals(checkedRequestId)
        || !request.targetNamespace().equals(binding.targetNamespace())
        || !Arrays.equals(
            binding.canonicalBytes(), request.authorizationBinding().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Retained World request differs from original Entity binding");
    }
    byte[] boundedClosure =
        boundedBytes(
            closureBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES,
            "worldClosureBytes");
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromCanonicalBytes(
            request.freezeEvidence(),
            boundedClosure,
            requireDigest(closureDigest, "worldClosureDigest"));
    return new SelectedOwnerEmptySourceInputs(
        binding, new SelectedOwnerWorldInventoryReadEvidence(request, inventory));
  }

  private static List<FamilyCensus> requireAllEmptyStates(
      List<FamilyCensus> states, List<String> expectedFamilies) {
    List<FamilyCensus> copy = List.copyOf(Objects.requireNonNull(states, "familyStates"));
    if (expectedFamilies.size() != 23 || copy.size() != expectedFamilies.size()) {
      throw new IllegalArgumentException("All 23 Entity owner family states are required");
    }
    for (int index = 0; index < expectedFamilies.size(); index++) {
      FamilyCensus state = copy.get(index);
      if (!expectedFamilies.get(index).equals(state.family())
          || state.state() != FamilyState.EMPTY) {
        throw new IllegalArgumentException(
            "Entity family states must be complete, ordered, and explicitly EMPTY");
      }
    }
    return copy;
  }

  public static List<String> familyNames(String canonicalDeclaration) {
    try {
      var root = JSON.readTree(canonicalDeclaration);
      var families = root.get("families");
      if (families == null || !families.isObject()) {
        throw new IllegalArgumentException("Typed Entity family declaration is required");
      }
      var names = new ArrayList<String>();
      families.properties().forEach(property -> names.add(property.getKey()));
      names.sort(String::compareTo);
      if (names.size() != 23) {
        throw new IllegalArgumentException("Typed Entity declaration must provide all 23 families");
      }
      return List.copyOf(names);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Entity family order could not be derived from its typed declaration", malformed);
    }
  }

  private static String schemaDigest(List<String> familyNames) {
    var out = new ByteArrayOutputStream();
    frame(out, "entity-selected-empty-source-catalogue-schema/v1");
    frame(out, DOMAIN);
    frame(out, Integer.toString(SCHEMA_VERSION));
    frame(out, "encoding=ADR-0047-byte-length-frames;strings=UTF-8;counts=canonical-decimal");
    for (String field :
        List.of(
            "targetNamespace:utf8",
            "authorizationBindingBytes:canonical-original-account-binding",
            "authorizationBindingDigest:sha256",
            "selectedSourceBytes:canonical-game-design-source-content",
            "selectedSourceDigest:sha256",
            "selectedSourceRevisionBindingBytes:canonical-game-design-source-operation-binding",
            "selectedSourceRevisionBindingDigest:sha256",
            "worldReadRequestBytes:canonical-recipient-qualified-world-request",
            "worldReadRequestDigest:sha256",
            "worldClosureBytes:canonical-public-world-inventory",
            "worldClosureDigest:sha256",
            "localTenantKey:positive-canonical-decimal",
            "localVersionKey:positive-canonical-decimal",
            "familyStates:count-then-23-lexical-family-records-with-provider-kind",
            "genesisId:canonical-uuid",
            "requestDigest:sha256",
            "worldReadRequestId:canonical-uuid",
            "retainedAt:canonical-utc-offset-date-time")) {
      frame(out, field);
    }
    frame(
        out,
        "familyRecordFields=count-then-family,state,evidenceKind,rowCount,unqualifiedRowCount,retainedRowCount,selectedScopeRowCount,referenceCount");
    frame(out, "family:utf8");
    frame(out, "state=EMPTY");
    frame(out, "evidenceKind=V1_SOURCE_CENSUS|EMPTY_ONLY_OWNER_PROVIDER");
    frame(out, "rowCount:canonical-nonnegative-decimal");
    frame(out, "unqualifiedRowCount:canonical-nonnegative-decimal");
    frame(out, "retainedRowCount:canonical-nonnegative-decimal");
    frame(out, "selectedScopeRowCount:canonical-nonnegative-decimal");
    frame(out, "referenceCount:canonical-nonnegative-decimal");
    frame(out, Integer.toString(familyNames.size()));
    for (String family : familyNames) frame(out, family);
    return DraftAuthorizationFenceBinding.digest(out.toByteArray());
  }

  private static String requireSameNamespace(
      String namespace, SelectedOwnerIntakeAuthorizationBinding binding) {
    if (!namespace.equals(binding.targetNamespace())) {
      throw new IllegalArgumentException("Entity authorization namespace differs");
    }
    return namespace;
  }

  private static FamilyEvidenceKind evidenceKindForFamily(String family) {
    return switch (family) {
      case "ITEM_TEMPLATE_ROOTS",
          "NPC_TEMPLATE_ROOTS",
          "CRAFTING_RECIPE_ROOTS",
          "CRAFTING_RECIPE_RESULT_BINDINGS",
          "CRAFTING_INGREDIENT_BINDINGS",
          "EQUIPMENT_SLOT_ROOTS",
          "EQUIPMENT_SLOT_GROUPS",
          "BODY_LAYOUT_ROOTS",
          "BODY_LAYOUT_MEMBERSHIPS" ->
          FamilyEvidenceKind.V1_SOURCE_CENSUS;
      case "ACTOR_BODY_LAYOUT_ASSIGNMENTS",
          "ARCHETYPE_ASSIGNMENTS",
          "ARCHETYPE_CONSTRAINTS",
          "ARCHETYPE_ROOTS",
          "BALANCE_CURVE_ATTACHMENTS",
          "BALANCE_CURVE_ROOTS",
          "EQUIPMENT_ATTACHMENT_RULES",
          "EQUIPMENT_CAPABILITIES",
          "EQUIPMENT_COMPATIBILITY_RULES",
          "EQUIPMENT_OCCUPANCY_RULES",
          "INBOUND_LOOT_BINDINGS",
          "LOOT_ITEM_MAPPINGS",
          "LOOT_TABLE_ROOTS",
          "OTHER_ACTOR_TEMPLATE_ROOTS" ->
          FamilyEvidenceKind.EMPTY_ONLY_OWNER_PROVIDER;
      default -> throw new IllegalArgumentException("Unsupported Entity family: " + family);
    };
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value;
  }

  private static String requireDigest(String value, String label) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be canonical SHA-256");
    }
    return value;
  }

  private static byte[] boundedBytes(byte[] value, int max, String label) {
    if (value == null || value.length == 0 || value.length > max) {
      throw new IllegalArgumentException(label + " is absent or exceeds its limit");
    }
    return value.clone();
  }

  private static UUID requireUuid(UUID value, String label) {
    DraftAuthorizationFenceBinding.requireUuid(value);
    return value;
  }

  private static UUID parseUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
      return requireUuid(parsed, label);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical nonzero UUID", invalid);
    }
  }

  private static String requireDecimal(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, true);
    return value;
  }

  private static long parsePositiveLong(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, false);
    long parsed = Long.parseLong(value);
    if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
      throw new IllegalArgumentException(label + " must be a positive canonical decimal");
    }
    return parsed;
  }

  private static long parseNonnegativeLong(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, true);
    long parsed = Long.parseLong(value);
    if (parsed < 0L || !Long.toString(parsed).equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal");
    }
    return parsed;
  }

  private static int parsePositiveInt(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, false);
    int parsed = Integer.parseInt(value);
    if (parsed <= 0 || !Integer.toString(parsed).equals(value)) {
      throw new IllegalArgumentException(label + " must be a positive canonical decimal");
    }
    return parsed;
  }

  private static OffsetDateTime parseTimestamp(String value) {
    final OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new IllegalArgumentException("Receipt timestamp must be canonical UTC", malformed);
    }
    if (parsed.getOffset().getTotalSeconds() != 0 || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Receipt timestamp must be canonical UTC");
    }
    return parsed;
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  public enum FamilyState {
    EMPTY
  }

  /** Distinguishes source census from a newly persisted fresh-only owner state. */
  public enum FamilyEvidenceKind {
    V1_SOURCE_CENSUS,
    EMPTY_ONLY_OWNER_PROVIDER
  }

  /** Actual owner-local census and reference counts captured under the source-table guard. */
  public record FamilyCensus(
      String family,
      FamilyState state,
      FamilyEvidenceKind evidenceKind,
      long rowCount,
      long unqualifiedRowCount,
      long retainedRowCount,
      long selectedScopeRowCount,
      long referenceCount) {
    public FamilyCensus {
      requireText(family, "family");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(evidenceKind, "evidenceKind");
      if (evidenceKindForFamily(family) != evidenceKind) {
        throw new IllegalArgumentException("Entity family evidence kind differs from its provider");
      }
      if (rowCount < 0L
          || unqualifiedRowCount < 0L
          || retainedRowCount < 0L
          || selectedScopeRowCount < 0L
          || referenceCount < 0L) {
        throw new IllegalArgumentException("Entity family census counts must be nonnegative");
      }
      if (state == FamilyState.EMPTY
          && (rowCount != 0L
              || unqualifiedRowCount != 0L
              || retainedRowCount != 0L
              || selectedScopeRowCount != 0L
              || referenceCount != 0L)) {
        throw new IllegalArgumentException("An EMPTY Entity family must have a zero owner census");
      }
    }
  }
}
