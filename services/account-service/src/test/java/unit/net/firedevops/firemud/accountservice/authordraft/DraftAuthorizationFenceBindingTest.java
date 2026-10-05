package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

class DraftAuthorizationFenceBindingTest {
  @Test
  void originalSourceChangeRoundTripsWithoutNarrowingIndependentCounters() {
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.MEMBERSHIP,
            UUID.randomUUID() + "/" + UUID.randomUUID(),
            "922337203685477580812345",
            "922337203685477581812345",
            "membership-stream",
            "922337203685477583812345",
            "unchanged-original-event".getBytes(StandardCharsets.UTF_8));
    SourceChange original =
        new SourceChange(UUID.randomUUID(), List.of(source), new byte[] {1, 2, 3});
    SourceChange recovered = SourceChange.fromStored(original.canonicalBytes());
    assertThat(recovered.canonicalBytes()).containsExactly(original.canonicalBytes());
    assertThat(recovered.sources().getFirst().generation()).isEqualTo(source.generation());
    assertThat(recovered.sources().getFirst().sourceVersion()).isEqualTo(source.sourceVersion());
    assertThat(recovered.sources().getFirst().checkpointSequence())
        .isEqualTo(source.checkpointSequence());
    assertThat(recovered.mutation()).containsExactly(original.mutation());
  }

  @Test
  void originalSourceChangeRejectsMalformedFramesAndNoncanonicalRecoveryBytes() {
    SourceChange original = new SourceChange(UUID.randomUUID(), List.of(source()), new byte[] {1});
    byte[] stored = original.canonicalBytes();
    assertThatThrownBy(
            () -> SourceChange.fromStored(java.util.Arrays.copyOf(stored, stored.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SourceChange.fromStored(java.util.Arrays.copyOf(stored, stored.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] negativeSize = stored.clone();
    java.nio.ByteBuffer.wrap(negativeSize).putInt(-1);
    assertThatThrownBy(() -> SourceChange.fromStored(negativeSize))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] impossibleSize = stored.clone();
    java.nio.ByteBuffer.wrap(impossibleSize).putInt(Integer.MAX_VALUE);
    assertThatThrownBy(() -> SourceChange.fromStored(impossibleSize))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiedSourceListsAndEvidenceCannotRewriteBindingOrSourceChange() {
    byte[] evidence = new byte[] {1, 2};
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT, UUID.randomUUID().toString(), "1", "2", "stream", "0", evidence);
    List<SourceEvidence> incoming = new ArrayList<>(List.of(source));
    DraftAuthorizationFenceBinding original = binding(source, "exact-payload");
    DraftAuthorizationFenceBinding captured =
        new DraftAuthorizationFenceBinding(
            original.operationId(),
            original.requestId(),
            original.commitId(),
            original.fenceId(),
            original.actorAccountId(),
            original.tenantId(),
            original.versionId(),
            original.baseCommitId(),
            original.expectedDraftEpoch(),
            original.gameDesignBinding(),
            original.normalizedInput(),
            original.inputDigest(),
            incoming);
    SourceChange change = new SourceChange(UUID.randomUUID(), incoming, new byte[] {4});
    byte[] capturedBytes = captured.canonicalBytes();
    byte[] changedBytes = change.canonicalBytes();
    incoming.clear();
    evidence[0] = 99;
    captured.sources().getFirst().evidence()[0] = 98;
    change.sources().getFirst().evidence()[0] = 97;
    assertThatThrownBy(() -> captured.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> change.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(captured.canonicalBytes()).containsExactly(capturedBytes);
    assertThat(change.canonicalBytes()).containsExactly(changedBytes);
  }

  @Test
  void canonicalFramesPreserveIndependentCountersAndDefendExactBytes() {
    byte[] evidence = "exact-source-proof".getBytes(StandardCharsets.UTF_8);
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            UUID.randomUUID().toString(),
            "9007199254740993",
            "9007199254741007",
            "account:source",
            "17",
            evidence);
    DraftAuthorizationFenceBinding binding = binding(source, "normalized-input");
    byte[] gameDesign = binding.gameDesignBinding();
    byte[] input = binding.normalizedInput();
    DraftAuthorizationFenceBinding captured =
        copy(
            binding,
            binding.requestId(),
            binding.commitId(),
            binding.tenantId(),
            binding.versionId(),
            binding.baseCommitId(),
            gameDesign,
            input,
            binding.inputDigest());
    byte[] original = captured.canonicalBytes();
    evidence[0] = 0;
    gameDesign[0] = 0;
    input[0] = 0;
    captured.normalizedInput()[0] = 0;
    captured.gameDesignBinding()[0] = 0;
    source.evidence()[0] = 0;
    assertThat(captured.canonicalBytes()).containsExactly(original);
    assertThat(captured.baseCommitId()).isEqualTo("opaque/base:9007199254740993");
    assertThat(
            copy(
                    binding,
                    binding.requestId(),
                    binding.commitId(),
                    binding.tenantId(),
                    binding.versionId(),
                    binding.baseCommitId(),
                    binding.gameDesignBinding(),
                    binding.normalizedInput(),
                    binding.inputDigest())
                .canonicalBytes())
        .containsExactly(binding.canonicalBytes());
    assertThat(new String(original, StandardCharsets.UTF_8))
        .contains("9007199254740993", "9007199254741007", "17", binding.baseCommitId());
  }

  @Test
  void nonexistentCounterApplicabilityIsNotManufacturedForGlobalRoleBirth() {
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.GLOBAL_ROLES,
            UUID.randomUUID().toString(),
            null,
            "1",
            null,
            null,
            new byte[] {1});
    assertThat(source.generation()).isNull();
    assertThat(source.checkpointSequence()).isNull();
    assertThat(source.canonicalBytes()).isNotEmpty();
  }

  @Test
  void rejectsOversizedMultibyteParticipationKeyWithoutChangingCanonicalScopes() {
    String exactLimit = "界".repeat(680) + "x";
    SourceEvidence retained =
        new SourceEvidence(SourceKind.ISSUER, exactLimit, "1", "1", "stream", "0", new byte[] {1});
    assertThat(retained.scopeId()).isEqualTo(exactLimit);
    assertThat(retained.key().getBytes(StandardCharsets.UTF_8)).hasSize(2048);
    assertThatThrownBy(
            () ->
                new SourceEvidence(
                    SourceKind.ISSUER, exactLimit + "x", "1", "1", "stream", "0", new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    String configuredIssuer = "界".repeat(512);
    SourceEvidence exactIssuer =
        new SourceEvidence(
            SourceKind.ISSUER, configuredIssuer, "1", "1", "stream", "0", new byte[] {1});
    assertThat(exactIssuer.scopeId()).isEqualTo(configuredIssuer);
    assertThat(exactIssuer.key()).isEqualTo("ISSUER:" + configuredIssuer);
    UUID account = UUID.randomUUID();
    SourceEvidence canonical =
        new SourceEvidence(
            SourceKind.ACCOUNT, account.toString(), "1", "2", "stream", "0", new byte[] {1});
    assertThat(canonical.scopeId()).isEqualTo(account.toString());
    assertThat(canonical.key()).isEqualTo("ACCOUNT:" + account);
  }

  @Test
  void rejectsAliasesNoncanonicalCountersAndChangedInputDigest() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    id.toString().toUpperCase(),
                    "1",
                    "2",
                    "stream",
                    "3",
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    for (String counter : List.of("01", "1e3", "-1", " 1", "1.0")) {
      assertThatThrownBy(
              () ->
                  new SourceEvidence(
                      SourceKind.ACCOUNT,
                      id.toString(),
                      counter,
                      "2",
                      "stream",
                      "3",
                      new byte[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
    }
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT, id.toString(), "1", "2", "stream", "3", new byte[] {1});
    DraftAuthorizationFenceBinding binding = binding(source, "payload");
    assertThatThrownBy(
            () ->
                new DraftAuthorizationFenceBinding(
                    binding.operationId(),
                    binding.requestId(),
                    binding.commitId(),
                    binding.fenceId(),
                    binding.actorAccountId(),
                    binding.tenantId(),
                    binding.versionId(),
                    binding.baseCommitId(),
                    "0",
                    binding.gameDesignBinding(),
                    new byte[] {2},
                    binding.inputDigest(),
                    List.of(source)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedDuplicatedIdentitiesAndBaseBeforeStorage() {
    DraftAuthorizationFenceBinding b = binding(source(), "payload");
    for (int field = 0; field < 5; field++) {
      int changedField = field;
      assertThatThrownBy(
              () ->
                  copy(
                      b,
                      changedField == 0 ? UUID.randomUUID() : b.requestId(),
                      changedField == 1 ? UUID.randomUUID() : b.commitId(),
                      changedField == 2 ? UUID.randomUUID() : b.tenantId(),
                      changedField == 3 ? UUID.randomUUID() : b.versionId(),
                      changedField == 4 ? "changed/base" : b.baseCommitId(),
                      b.gameDesignBinding(),
                      b.normalizedInput(),
                      b.inputDigest()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String invalidBase : List.of("", " ", "x".repeat(257), "界".repeat(86))) {
      assertThatThrownBy(
              () ->
                  copy(
                      b,
                      b.requestId(),
                      b.commitId(),
                      b.tenantId(),
                      b.versionId(),
                      invalidBase,
                      b.gameDesignBinding(),
                      b.normalizedInput(),
                      b.inputDigest()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsMalformedNoncanonicalOrReorderedGameDesignBytesAndAlternateInput() {
    DraftAuthorizationFenceBinding b = binding(source(), "payload");
    DraftCommitBinding complete =
        DraftCommitBinding.fromStored(
            new String(b.gameDesignBinding(), StandardCharsets.UTF_8), b.inputDigest());
    List<RevisionPayload> reordered = new ArrayList<>(complete.revisions());
    java.util.Collections.reverse(reordered);
    List<byte[]> invalid =
        List.of(
            new byte[] {3},
            new byte[] {(byte) 0xff},
            (complete.canonicalJson() + " ").getBytes(StandardCharsets.UTF_8),
            complete
                .canonicalJson()
                .replace("\"revisionOrder\":\"0\"", "\"revisionOrder\":\"1\"")
                .getBytes(StandardCharsets.UTF_8));
    for (byte[] bytes : invalid) {
      assertThatThrownBy(
              () ->
                  copy(
                      b,
                      b.requestId(),
                      b.commitId(),
                      b.tenantId(),
                      b.versionId(),
                      b.baseCommitId(),
                      bytes,
                      bytes,
                      DraftAuthorizationFenceBinding.digest(bytes)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    complete.target(),
                    complete.requestId(),
                    complete.commitId(),
                    complete.baseCommitId(),
                    reordered,
                    complete.affectedUnits()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] alternate = "alternate-input".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                copy(
                    b,
                    b.requestId(),
                    b.commitId(),
                    b.tenantId(),
                    b.versionId(),
                    b.baseCommitId(),
                    b.gameDesignBinding(),
                    alternate,
                    DraftAuthorizationFenceBinding.digest(alternate)))
        .isInstanceOf(IllegalArgumentException.class);
    DraftCommitBinding changed =
        DraftCommitBinding.create(
            complete.target(),
            complete.requestId(),
            complete.commitId(),
            complete.baseCommitId(),
            List.of(
                new RevisionPayload(
                    "0",
                    complete.revisions().getFirst().revisionId(),
                    Owner.WORLD_MANAGEMENT,
                    "changed-payload"),
                complete.revisions().get(1)),
            complete.affectedUnits());
    assertThatThrownBy(
            () ->
                copy(
                    b,
                    b.requestId(),
                    b.commitId(),
                    b.tenantId(),
                    b.versionId(),
                    b.baseCommitId(),
                    changed.canonicalBytes(),
                    b.normalizedInput(),
                    changed.digest()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SourceEvidence source() {
    return new SourceEvidence(
        SourceKind.ACCOUNT, UUID.randomUUID().toString(), "1", "2", "stream", "0", new byte[] {1});
  }

  private DraftAuthorizationFenceBinding copy(
      DraftAuthorizationFenceBinding b,
      UUID request,
      UUID commit,
      UUID tenant,
      UUID version,
      String base,
      byte[] gameDesign,
      byte[] input,
      String digest) {
    return new DraftAuthorizationFenceBinding(
        b.operationId(),
        request,
        commit,
        b.fenceId(),
        b.actorAccountId(),
        tenant,
        version,
        base,
        b.expectedDraftEpoch(),
        gameDesign,
        input,
        digest,
        b.sources());
  }

  private DraftAuthorizationFenceBinding binding(SourceEvidence source, String payload) {
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "tenant-key",
                2,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "opaque/base:9007199254740993",
            List.of(
                new RevisionPayload("0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, payload),
                new RevisionPayload(
                    "1", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, "control")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "9007199254740999"),
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "version",
                    "version-1",
                    "aggregate",
                    "version-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "9007199254741009",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(source));
  }
}
