package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

class DraftAuthorizationFenceBindingCodecTest {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID SOURCE_SCOPE_ID = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final String EXPECTED_DRAFT_EPOCH = "9007199254741009";
  private static final String LARGE_SOURCE_VERSION = "9007199254740993";
  private static final String GAME_DESIGN_DIGEST =
      "sha256:2a6fe4b88eb642dac5eb64b04d0123d11a4944ec94f4b4304f9416753c09742f";
  private static final String FENCE_DIGEST =
      "sha256:1ee6345cf8420f08f59f4570a62bef8b149b56f12c5b0c66ba5d3f77bd57b7e2";
  private static final String GAME_DESIGN_JSON =
      "{\"affectedUnits\":[{\"aggregateId\":\"world-1\",\"aggregateType\":\"WORLD_TEMPLATE\","
          + "\"expectedEpoch\":\"0\",\"owner\":\"WORLD_MANAGEMENT\",\"scopeId\":\"room-1\","
          + "\"scopeType\":\"ROOM_SCOPE\"}],\"baseCommitId\":\"base-source-1\","
          + "\"canonicalTenantId\":\"11111111-1111-4111-8111-111111111111\","
          + "\"canonicalVersionId\":\"22222222-2222-4222-8222-222222222222\","
          + "\"commitId\":\"55555555-5555-4555-8555-555555555555\","
          + "\"requestId\":\"44444444-4444-4444-8444-444444444444\","
          + "\"requiredOwners\":[\"WORLD_MANAGEMENT\"],\"revisions\":[{"
          + "\"owner\":\"WORLD_MANAGEMENT\",\"payload\":\"{}\","
          + "\"revisionId\":\"66666666-6666-4666-8666-666666666666\","
          + "\"revisionOrder\":\"0\"}],\"schemaVersion\":\"1\",\"target\":{"
          + "\"gameDesignVersionRowId\":\"19\",\"gameDesignVersionTenantKey\":\"tenant-key\","
          + "\"sourceGameRowId\":\"42\",\"sourceGameTenantKey\":\"tenant-key\","
          + "\"sourceProvenanceKind\":\"NEW_GAME_ROW\"}}";

  @Test
  void preservesV57ProducerGoldenBytesAndDigestAndRoundTripsEveryField() {
    DraftAuthorizationFenceBinding original = binding(List.of(source()));
    byte[] golden = goldenBytes();

    assertThat(original.gameDesignBinding())
        .containsExactly(GAME_DESIGN_JSON.getBytes(StandardCharsets.UTF_8));
    assertThat(original.inputDigest()).isEqualTo(GAME_DESIGN_DIGEST);
    assertThat(original.canonicalBytes()).containsExactly(golden);
    assertThat(DraftAuthorizationFenceBinding.digest(golden)).isEqualTo(FENCE_DIGEST);

    DraftAuthorizationFenceBinding restored = DraftAuthorizationFenceBinding.fromStored(golden);
    assertBindingValues(original, restored);
    assertThat(restored.canonicalBytes()).containsExactly(golden);
  }

  @Test
  void aDifferentCanonicalOperationDecodesAsDifferentImmutableDataNotAuthorization() {
    DraftAuthorizationFenceBinding original = binding(List.of(source()));
    List<byte[]> frames = readFrames(original.canonicalBytes());
    frames.set(1, utf8("99999999-9999-4999-8999-999999999999"));
    byte[] changedOperation = writeFrames(frames);

    DraftAuthorizationFenceBinding restored =
        DraftAuthorizationFenceBinding.fromStored(changedOperation);

    assertThat(restored.operationId()).isNotEqualTo(original.operationId());
    assertThat(restored.canonicalBytes()).containsExactly(changedOperation);
    assertThat(DraftAuthorizationFenceBinding.digest(restored.canonicalBytes()))
        .isNotEqualTo(DraftAuthorizationFenceBinding.digest(original.canonicalBytes()));
  }

  @Test
  void rejectsOperationSourceOrderDigestTargetAndCounterSubstitutions() {
    byte[] original = goldenBytes();

    List<byte[]> nilOperation = readFrames(original);
    nilOperation.set(1, utf8("00000000-0000-0000-0000-000000000000"));
    assertInvalid(writeFrames(nilOperation));

    List<byte[]> noncanonicalOperation = readFrames(original);
    noncanonicalOperation.set(1, utf8("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
    assertInvalid(writeFrames(noncanonicalOperation));

    List<byte[]> changedDigest = readFrames(original);
    changedDigest.set(13, utf8("sha256:" + "f".repeat(64)));
    assertInvalid(writeFrames(changedDigest));

    List<byte[]> changedTenant = readFrames(original);
    changedTenant.set(6, utf8("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    assertInvalid(writeFrames(changedTenant));

    List<byte[]> reorderedOwners = readFrames(original);
    byte[] gameDesignOwner = reorderedOwners.get(16);
    reorderedOwners.set(16, reorderedOwners.get(17));
    reorderedOwners.set(17, gameDesignOwner);
    assertInvalid(writeFrames(reorderedOwners));

    List<byte[]> noncanonicalEpoch = readFrames(original);
    noncanonicalEpoch.set(10, utf8("09007199254741009"));
    assertInvalid(writeFrames(noncanonicalEpoch));

    List<byte[]> noncanonicalCount = readFrames(original);
    noncanonicalCount.set(14, utf8("01"));
    assertInvalid(writeFrames(noncanonicalCount));

    List<byte[]> excessiveCount = readFrames(original);
    excessiveCount.set(14, utf8("999999999999999999999999999999999999"));
    assertInvalid(writeFrames(excessiveCount));
  }

  @Test
  void rejectsReorderedSourceVectorAndMalformedStoredFrames() {
    DraftAuthorizationFenceBinding twoSources = binding(List.of(source(), accountSource()));
    List<byte[]> reversedSources = readFrames(twoSources.canonicalBytes());
    byte[] firstSource = reversedSources.get(15);
    reversedSources.set(15, reversedSources.get(16));
    reversedSources.set(16, firstSource);
    assertInvalid(writeFrames(reversedSources));

    List<byte[]> invalidUtf8 = readFrames(goldenBytes());
    invalidUtf8.set(8, new byte[] {(byte) 0xc3, 0x28});
    assertInvalid(writeFrames(invalidUtf8));

    List<byte[]> unknownSchema = readFrames(goldenBytes());
    unknownSchema.set(0, utf8("account-draft-authorization-fence/v2"));
    assertInvalid(writeFrames(unknownSchema));

    byte[] golden = goldenBytes();
    assertInvalid(Arrays.copyOf(golden, golden.length - 1));
    byte[] trailing = Arrays.copyOf(golden, golden.length + 1);
    trailing[trailing.length - 1] = 1;
    assertInvalid(trailing);

    byte[] negativeLength = golden.clone();
    ByteBuffer.wrap(negativeLength).putInt(0, -1);
    assertInvalid(negativeLength);
    byte[] oversizedLength = golden.clone();
    ByteBuffer.wrap(oversizedLength).putInt(0, Integer.MAX_VALUE);
    assertInvalid(oversizedLength);
  }

  private static void assertBindingValues(
      DraftAuthorizationFenceBinding expected, DraftAuthorizationFenceBinding actual) {
    assertThat(actual.operationId()).isEqualTo(expected.operationId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.commitId()).isEqualTo(expected.commitId());
    assertThat(actual.fenceId()).isEqualTo(expected.fenceId());
    assertThat(actual.actorAccountId()).isEqualTo(expected.actorAccountId());
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
    assertThat(actual.versionId()).isEqualTo(expected.versionId());
    assertThat(actual.baseCommitId()).isEqualTo(expected.baseCommitId());
    assertThat(actual.expectedDraftEpoch()).isEqualTo(expected.expectedDraftEpoch());
    assertThat(actual.gameDesignBinding()).containsExactly(expected.gameDesignBinding());
    assertThat(actual.normalizedInput()).containsExactly(expected.normalizedInput());
    assertThat(actual.inputDigest()).isEqualTo(expected.inputDigest());
    assertThat(actual.sources()).hasSize(expected.sources().size());
    for (int index = 0; index < expected.sources().size(); index++) {
      SourceEvidence expectedSource = expected.sources().get(index);
      SourceEvidence actualSource = actual.sources().get(index);
      assertThat(actualSource.kind()).isEqualTo(expectedSource.kind());
      assertThat(actualSource.scopeId()).isEqualTo(expectedSource.scopeId());
      assertThat(actualSource.generation()).isEqualTo(expectedSource.generation());
      assertThat(actualSource.sourceVersion()).isEqualTo(expectedSource.sourceVersion());
      assertThat(actualSource.checkpointStream()).isEqualTo(expectedSource.checkpointStream());
      assertThat(actualSource.checkpointSequence()).isEqualTo(expectedSource.checkpointSequence());
      assertThat(actualSource.evidence()).containsExactly(expectedSource.evidence());
    }
  }

  private static DraftAuthorizationFenceBinding binding(List<SourceEvidence> sources) {
    DraftCommitBinding gameDesign = gameDesignBinding();
    byte[] gameDesignBytes = gameDesign.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
        OPERATION_ID,
        REQUEST_ID,
        COMMIT_ID,
        FENCE_ID,
        ACTOR_ID,
        TENANT_ID,
        VERSION_ID,
        "base-source-1",
        EXPECTED_DRAFT_EPOCH,
        gameDesignBytes,
        gameDesignBytes,
        gameDesign.digest(),
        sources);
  }

  private static DraftCommitBinding gameDesignBinding() {
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        REQUEST_ID,
        COMMIT_ID,
        "base-source-1",
        List.of(new RevisionPayload("0", REVISION_ID, Owner.WORLD_MANAGEMENT, "{}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT, "WORLD_TEMPLATE", "world-1", "ROOM_SCOPE", "room-1", "0")));
  }

  private static SourceEvidence source() {
    return new SourceEvidence(
        SourceKind.GLOBAL_ROLES,
        SOURCE_SCOPE_ID.toString(),
        null,
        LARGE_SOURCE_VERSION,
        null,
        null,
        new byte[] {1, 2, 3});
  }

  private static SourceEvidence accountSource() {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        "1",
        "2",
        "account:source",
        "0",
        new byte[] {4, 5});
  }

  private static byte[] goldenBytes() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (String value :
        List.of(
            "account-draft-authorization-fence/v1",
            OPERATION_ID.toString(),
            REQUEST_ID.toString(),
            COMMIT_ID.toString(),
            FENCE_ID.toString(),
            ACTOR_ID.toString(),
            TENANT_ID.toString(),
            VERSION_ID.toString(),
            "base-source-1",
            "DRAFT",
            EXPECTED_DRAFT_EPOCH)) {
      appendFrame(output, value);
    }
    byte[] gameDesign = GAME_DESIGN_JSON.getBytes(StandardCharsets.UTF_8);
    appendFrame(output, gameDesign);
    appendFrame(output, gameDesign);
    appendFrame(output, GAME_DESIGN_DIGEST);
    appendFrame(output, "1");
    appendFrame(output, goldenSourceBytes());
    appendFrame(output, "GAME_DESIGN");
    appendFrame(output, "WORLD");
    return output.toByteArray();
  }

  private static byte[] goldenSourceBytes() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    appendFrame(output, "account-draft-source-evidence/v1");
    appendFrame(output, "GLOBAL_ROLES");
    appendFrame(output, SOURCE_SCOPE_ID.toString());
    appendFrame(output, "ABSENT");
    appendFrame(output, LARGE_SOURCE_VERSION);
    appendFrame(output, "ABSENT");
    appendFrame(output, new byte[] {1, 2, 3});
    return output.toByteArray();
  }

  private static List<byte[]> readFrames(byte[] stored) {
    ByteBuffer input = ByteBuffer.wrap(stored);
    List<byte[]> frames = new ArrayList<>();
    while (input.hasRemaining()) {
      if (input.remaining() < Integer.BYTES) {
        throw new IllegalArgumentException("Incomplete test frame");
      }
      int length = input.getInt();
      if (length < 0 || length > input.remaining()) {
        throw new IllegalArgumentException("Invalid test frame length");
      }
      byte[] frame = new byte[length];
      input.get(frame);
      frames.add(frame);
    }
    return frames;
  }

  private static byte[] writeFrames(List<byte[]> frames) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    frames.forEach(frame -> appendFrame(output, frame));
    return output.toByteArray();
  }

  private static void appendFrame(ByteArrayOutputStream output, String value) {
    appendFrame(output, utf8(value));
  }

  private static void appendFrame(ByteArrayOutputStream output, byte[] value) {
    output.write((value.length >>> 24) & 0xff);
    output.write((value.length >>> 16) & 0xff);
    output.write((value.length >>> 8) & 0xff);
    output.write(value.length & 0xff);
    output.writeBytes(value);
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static void assertInvalid(byte[] stored) {
    assertThatThrownBy(() -> DraftAuthorizationFenceBinding.fromStored(stored))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
