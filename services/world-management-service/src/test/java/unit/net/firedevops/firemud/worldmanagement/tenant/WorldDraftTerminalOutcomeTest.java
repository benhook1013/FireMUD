package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcome;
import org.junit.jupiter.api.Test;

class WorldDraftTerminalOutcomeTest {
  @Test
  void operationRetainsAndStrictlyCrossChecksTheCompleteV57AccountBinding() {
    DraftCommitBinding binding = binding();
    Ids ids = ids();
    byte[] accountBytes = accountBinding(binding, ids).canonicalBytes();
    WorldDraftTerminalOperation operation = operation(binding, accountBytes, ids);

    assertThat(operation.accountBindingBytes()).containsExactly(accountBytes);
    assertThat(operation.accountBindingDigest()).isEqualTo(digest(accountBytes));
    assertThat(operation.canonicalBytes()).contains(accountBytes);
    assertThat(operation.binding()).isEqualTo(binding);
  }

  @Test
  void changedOperationFenceTargetOrGameDesignBytesCannotReuseTheAccountBinding() {
    DraftCommitBinding binding = binding();
    Ids ids = ids();
    byte[] accountBytes = accountBinding(binding, ids).canonicalBytes();

    assertThatThrownBy(
            () ->
                operation(
                    binding,
                    accountBytes,
                    new Ids(UUID.randomUUID(), ids.request(), ids.commit(), ids.fence(), ids.actor())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("V57 Account binding");
    assertThatThrownBy(
            () ->
                operation(
                    binding,
                    accountBytes,
                    new Ids(ids.operation(), ids.request(), ids.commit(), UUID.randomUUID(), ids.actor())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("V57 Account binding");
    assertThatThrownBy(
            () ->
                new WorldDraftTerminalOperation(
                    ids.operation(),
                    ids.request(),
                    ids.commit(),
                    ids.fence(),
                    UUID.randomUUID(),
                    binding.target().canonicalVersionId(),
                    binding,
                    ownerBinding(binding),
                    accountBytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact World operation");
  }

  @Test
  void abortEvidenceRequiresEveryExactWorldEpochAndRejectsSubstitutionOrMalformedFrames() {
    DraftCommitBinding binding = binding();
    Ids ids = ids();
    WorldDraftTerminalOperation operation =
        operation(binding, accountBinding(binding, ids).canonicalBytes(), ids);
    List<AffectedUnit> units = binding.affectedUnits(Owner.WORLD_MANAGEMENT);
    var observed =
        units.stream()
            .map(unit -> new WorldDraftTerminalOutcome.ObservedEpoch(unit, unit.expectedEpoch()))
            .toList();
    WorldDraftTerminalOutcome outcome =
        WorldDraftTerminalOutcome.create(operation, observed, java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    WorldDraftTerminalOutcome replay =
        WorldDraftTerminalOutcome.fromStored(
            operation,
            outcome.canonicalBytes(),
            outcome.digest(),
            outcome.recordedAt());

    assertThat(replay.canonicalBytes()).containsExactly(outcome.canonicalBytes());
    assertThat(replay.digest()).isEqualTo(outcome.digest());
    assertThat(replay.observedEpochs()).isEqualTo(observed);
    assertThatThrownBy(() -> WorldDraftTerminalOutcome.create(operation, observed.subList(0, 1), outcome.recordedAt()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete affected set");
    assertThatThrownBy(
            () ->
                WorldDraftTerminalOutcome.fromStored(
                    operation,
                    outcome.canonicalBytes(),
                    digest("substituted".getBytes(StandardCharsets.UTF_8)),
                    outcome.recordedAt()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not exact canonical evidence");
    byte[] trailing = java.util.Arrays.copyOf(outcome.canonicalBytes(), outcome.canonicalBytes().length + 1);
    assertThatThrownBy(
            () ->
                WorldDraftTerminalOutcome.fromStored(
                    operation, trailing, outcome.digest(), outcome.recordedAt()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private WorldDraftTerminalOperation operation(
      DraftCommitBinding binding, byte[] accountBytes, Ids ids) {
    return new WorldDraftTerminalOperation(
        ids.operation(),
        ids.request(),
        ids.commit(),
        ids.fence(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding,
        ownerBinding(binding),
        accountBytes);
  }

  private DraftAuthorizationFenceBinding accountBinding(DraftCommitBinding binding, Ids ids) {
    return new DraftAuthorizationFenceBinding(
        ids.operation(),
        ids.request(),
        ids.commit(),
        ids.fence(),
        ids.actor(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.baseCommitId(),
        "0",
        binding.canonicalBytes(),
        binding.canonicalBytes(),
        binding.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.TENANT,
                binding.target().canonicalTenantId().toString(),
                null,
                "1",
                null,
                null,
                new byte[] {1, 2, 3})));
  }

  private DraftCommitBinding binding() {
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID aggregate = UUID.randomUUID();
    UUID worldRevision = UUID.randomUUID();
    UUID gdRevision = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    TargetProof target = new TargetProof(tenant, version, 11, "gd-tenant", 12, "gd-tenant", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        request,
        commit,
        "base-commit",
        List.of(
            new RevisionPayload("0", worldRevision, Owner.WORLD_MANAGEMENT, "world mutation"),
            new RevisionPayload("1", gdRevision, Owner.GAME_DESIGN_CONTROL_PLANE, "game mutation")),
        List.of(
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "REGION", aggregate.toString(), "AGGREGATE", aggregate.toString(), "0"),
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "REGION", aggregate.toString(), "REGION_SUBTREE", aggregate.toString(), "0"),
            new AffectedUnit(Owner.GAME_DESIGN_CONTROL_PLANE, "VERSION", version.toString(), "AGGREGATE", version.toString(), "0")));
  }

  private OwnerBinding ownerBinding(DraftCommitBinding binding) {
    return new OwnerBinding(
        "firemud",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        UUID.randomUUID(),
        binding.target().gameDesignVersionRowId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        digest(new byte[] {1}),
        UUID.randomUUID(),
        digest(new byte[] {2}),
        digest(new byte[] {3}));
  }

  private Ids ids() {
    return new Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  private String digest(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Ids(UUID operation, UUID request, UUID commit, UUID fence, UUID actor) {}
}
