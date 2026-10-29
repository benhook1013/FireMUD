package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.junit.jupiter.api.Test;

class CanonicalGameplayIssuerMemberV2ValueCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final BigInteger LARGE = new BigInteger("9007199254740997");

  @Test
  void roundTripsCompleteTupleAndCountersBeyondJavaScriptSafeInteger() throws Exception {
    CanonicalGameplayIssuerMemberV2Value value = fixture();

    byte[] encoded = CanonicalGameplayIssuerMemberV2Codec.encode(value);
    CanonicalGameplayIssuerMemberV2Value decoded =
        CanonicalGameplayIssuerMemberV2Codec.decode(encoded);

    assertThat(decoded).isEqualTo(value);
    assertThat(decoded.bindingGeneration()).isEqualTo(LARGE);
    assertThat(decoded.anchor().issuerInventorySnapshotRevision())
        .isEqualTo(LARGE.add(BigInteger.valueOf(12)));
    assertThat(decoded.reservation().issuerCoverageOperationId()).isEqualTo(uuid(21));
    assertThat(decoded.reservation().issuerCoverageOperationFence())
        .isEqualTo(LARGE.add(BigInteger.valueOf(15)));
    assertThat(decoded.reservation().coverageFence()).isEqualTo(LARGE.add(BigInteger.valueOf(16)));
    assertThat(decoded.reservation().inventorySnapshotRevision())
        .isEqualTo(LARGE.add(BigInteger.valueOf(17)));
    assertThat(decoded.memberOperation().coverageGeneration())
        .isEqualTo(LARGE.add(BigInteger.valueOf(20)));
    assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("\"schemaVersion\":2");
    ObjectNode encodedRoot = (ObjectNode) JSON.readTree(encoded);
    assertThat(encodedRoot.path("reservation").path("coverageFence").textValue())
        .isEqualTo(LARGE.add(BigInteger.valueOf(16)).toString());
    assertThat(encodedRoot.path("anchor").path("coverageFence").textValue())
        .isEqualTo(LARGE.add(BigInteger.valueOf(11)).toString());
  }

  @Test
  void encoderRefusesCountersBeyondTheLocalReaderLimit() {
    CanonicalGameplayIssuerMemberV2Value original = fixture();
    BigInteger oversizedFence =
        new BigInteger("1".repeat(CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS + 1));
    CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation operation =
        new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
            original.memberOperation().operationId(),
            oversizedFence,
            original.memberOperation().coverageGeneration());
    CanonicalGameplayIssuerMemberV2Value value = withMemberOperation(original, operation);

    assertThatThrownBy(() -> CanonicalGameplayIssuerMemberV2Codec.encode(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("256-digit");
    assertThat(value).isEqualTo(withMemberOperation(original, operation));
    assertThat(value.hashCode()).isEqualTo(withMemberOperation(original, operation).hashCode());
  }

  @Test
  void equalityAndHashCodeDoNotDependOnTheCodecByteLimit() {
    CanonicalGameplayIssuerMemberV2Value original = fixture();
    byte[] oversizedDescriptor = new byte[CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES + 1];
    var leftLayout =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
            original.owner().issuerId(),
            original.owner().issuerIndexLayoutVersion(),
            original.owner().issuerIndexPartitionCount(),
            original.owner().issuerIndexPartitionCapacity(),
            CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
            oversizedDescriptor,
            CanonicalGameplayIssuerMemberV2Codec.sha256(oversizedDescriptor));
    var rightLayout =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
            original.owner().issuerId(),
            original.owner().issuerIndexLayoutVersion(),
            original.owner().issuerIndexPartitionCount(),
            original.owner().issuerIndexPartitionCapacity(),
            CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
            oversizedDescriptor.clone(),
            CanonicalGameplayIssuerMemberV2Codec.sha256(oversizedDescriptor));
    CanonicalGameplayIssuerMemberV2Value left = withLayout(original, leftLayout);
    CanonicalGameplayIssuerMemberV2Value right = withLayout(original, rightLayout);

    assertThatThrownBy(() -> CanonicalGameplayIssuerMemberV2Codec.encode(left))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 KiB");
    assertThat(left).isEqualTo(right);
    assertThat(left.hashCode()).isEqualTo(right.hashCode());
    assertThat(left).isNotEqualTo(original);
  }

  @Test
  void originalReservationAnchorAndMemberOperationTuplesRemainDistinctPhysicalIdentity()
      throws Exception {
    CanonicalGameplayIssuerMemberV2Value original = fixture();
    CanonicalGameplayIssuerMemberV2Value.Anchor changedAnchor =
        new CanonicalGameplayIssuerMemberV2Value.Anchor(
            uuid(34),
            original.anchor().operationFence().add(BigInteger.ONE),
            original.anchor().coverageFence().add(BigInteger.ONE),
            original.anchor().issuerInventorySnapshotRevision().add(BigInteger.ONE));
    CanonicalIssuerPartitionReservation changedOriginalReservation =
        new CanonicalIssuerPartitionReservation(
            original.reservation().reservationId(),
            original.owner(),
            original.bindingGeneration(),
            original.reservation().transitionId(),
            original.reservation().partitionId(),
            original.reservation().lifecycle(),
            original.reservation().reservationFence(),
            uuid(35),
            original.reservation().issuerCoverageOperationFence().add(BigInteger.ONE),
            original.reservation().coverageFence().add(BigInteger.ONE),
            original.reservation().inventorySnapshotRevision().add(BigInteger.ONE),
            original.reservation().inventoryRevision());
    CanonicalGameplayIssuerMemberV2Value anotherOriginalReservation =
        withReservation(original, changedOriginalReservation);
    CanonicalGameplayIssuerMemberV2Value anotherAnchor =
        copy(original, original.reservation(), changedAnchor);
    CanonicalGameplayIssuerMemberV2Value anotherMemberOperation =
        withMemberOperation(
            original,
            new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                uuid(36),
                original.memberOperation().operationFence().add(BigInteger.ONE),
                original.memberOperation().coverageGeneration().add(BigInteger.ONE)));
    CanonicalIssuerPartitionReservation changedLifecycle =
        new CanonicalIssuerPartitionReservation(
            original.reservation().reservationId(),
            original.owner(),
            original.bindingGeneration(),
            original.reservation().transitionId(),
            original.reservation().partitionId(),
            CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING,
            original.reservation().reservationFence(),
            original.reservation().issuerCoverageOperationId(),
            original.reservation().issuerCoverageOperationFence(),
            original.reservation().coverageFence(),
            original.reservation().inventorySnapshotRevision(),
            original.reservation().inventoryRevision());
    CanonicalGameplayIssuerMemberV2Value anotherReservation =
        copy(original, changedLifecycle, original.anchor());

    byte[] originalBytes = CanonicalGameplayIssuerMemberV2Codec.encode(original);
    for (CanonicalGameplayIssuerMemberV2Value changed :
        List.of(
            anotherOriginalReservation,
            anotherAnchor,
            anotherMemberOperation,
            anotherReservation)) {
      byte[] changedBytes = CanonicalGameplayIssuerMemberV2Codec.encode(changed);
      assertThat(changed).isNotEqualTo(original);
      assertThat(changedBytes).isNotEqualTo(originalBytes);
      CanonicalGameplayIssuerMemberV2Value decoded =
          CanonicalGameplayIssuerMemberV2Codec.decode(changedBytes);
      assertThat(decoded).isEqualTo(changed).isNotEqualTo(original);
      assertThat(decoded.hashCode()).isEqualTo(changed.hashCode());
    }
    assertThat(anotherOriginalReservation.anchor()).isEqualTo(original.anchor());
    assertThat(anotherAnchor.reservation()).isEqualTo(original.reservation());
    assertThat(anotherMemberOperation.reservation()).isEqualTo(original.reservation());
  }

  @Test
  void allRetainedByteAccessorsAreDefensiveCopies() {
    CanonicalGameplayIssuerMemberV2Value value = fixture();
    byte[] descriptor = value.layoutDescriptor().exactDescriptorBytes();
    byte[] checkpoint = value.sourceCheckpoint().exactCheckpointBytes();
    byte[] sourceEvent = value.sourceCheckpoint().latestIssuerEventBytes();
    byte descriptorFirst = descriptor[0];
    byte checkpointFirst = checkpoint[0];
    byte sourceEventFirst = sourceEvent[0];

    descriptor[0] = (byte) (descriptor[0] ^ 0x7f);
    checkpoint[0] = (byte) (checkpoint[0] ^ 0x7f);
    sourceEvent[0] = (byte) (sourceEvent[0] ^ 0x7f);

    assertThat(value.layoutDescriptor().exactDescriptorBytes()[0]).isEqualTo(descriptorFirst);
    assertThat(value.sourceCheckpoint().exactCheckpointBytes()[0]).isEqualTo(checkpointFirst);
    assertThat(value.sourceCheckpoint().latestIssuerEventBytes()[0]).isEqualTo(sourceEventFirst);
  }

  @Test
  void nullableSourceEventFieldsAreBothEncodedAsExplicitNull() {
    CanonicalGameplayIssuerMemberV2Value original = fixture();
    byte[] checkpointBytes = "checkpoint-without-event".getBytes(StandardCharsets.UTF_8);
    var source =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            original.owner().issuerId(),
            original.owner().issuerAuthGeneration(),
            checkpointBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(checkpointBytes),
            null,
            null);
    CanonicalGameplayIssuerMemberV2Value value =
        new CanonicalGameplayIssuerMemberV2Value(
            original.owner(),
            original.bindingGeneration(),
            original.reservation(),
            original.anchor(),
            original.memberOperation(),
            original.issuerControlEpoch(),
            original.layoutDescriptor(),
            source,
            original.incarnation());

    byte[] encoded = CanonicalGameplayIssuerMemberV2Codec.encode(value);
    CanonicalGameplayIssuerMemberV2Value decoded =
        CanonicalGameplayIssuerMemberV2Codec.decode(encoded);

    assertThat(new String(encoded, StandardCharsets.UTF_8))
        .contains("\"sourceEventBytes\":null", "\"sourceEventDigest\":null");
    assertThat(decoded.sourceCheckpoint().latestIssuerEventBytes()).isNull();
    assertThat(decoded.sourceCheckpoint().latestIssuerEventDigest()).isNull();
  }

  @Test
  void rejectsLegacyUnknownDuplicateNoncanonicalAndMalformedInputs() throws Exception {
    byte[] canonical = CanonicalGameplayIssuerMemberV2Codec.encode(fixture());
    ObjectNode root = (ObjectNode) JSON.readTree(canonical);

    ObjectNode schemaOne = root.deepCopy();
    schemaOne.put("schemaVersion", 1);
    assertRejected(canonical(schemaOne));

    ObjectNode extraField = root.deepCopy();
    extraField.put("extra", "not permitted");
    assertRejected(canonical(extraField));

    ObjectNode missingOriginalCoverageField = root.deepCopy();
    ((ObjectNode) missingOriginalCoverageField.get("reservation")).remove("coverageFence");
    assertRejected(canonical(missingOriginalCoverageField));

    ObjectNode unknownOriginalCoverageField = root.deepCopy();
    ((ObjectNode) unknownOriginalCoverageField.get("reservation"))
        .put("unrecognizedCoverageEvidence", "not permitted");
    assertRejected(canonical(unknownOriginalCoverageField));

    ObjectNode malformedOriginalCoverageField = root.deepCopy();
    ((ObjectNode) malformedOriginalCoverageField.get("reservation"))
        .put("inventorySnapshotRevision", "01");
    assertRejected(canonical(malformedOriginalCoverageField));

    String duplicate =
        new String(canonical, StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2");
    assertRejected(duplicate.getBytes(StandardCharsets.UTF_8));
    String originalFence =
        "\"issuerCoverageOperationFence\":\""
            + fixture().reservation().issuerCoverageOperationFence()
            + "\"";
    String duplicateOriginalReservationField =
        new String(canonical, StandardCharsets.UTF_8)
            .replace(originalFence, originalFence + "," + originalFence);
    assertRejected(duplicateOriginalReservationField.getBytes(StandardCharsets.UTF_8));

    ObjectNode leadingZero = root.deepCopy();
    leadingZero.put("bindingGeneration", "01");
    assertRejected(canonical(leadingZero));

    ObjectNode oversizedCounter = root.deepCopy();
    oversizedCounter.put(
        "bindingGeneration",
        "1".repeat(CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS + 1));
    assertRejected(canonical(oversizedCounter));

    byte[] malformedUtf8 = new byte[] {'{', '"', (byte) 0xc3, '"', '}', (byte) 0x80};
    assertRejected(malformedUtf8);
    assertRejected(
        "[".repeat(12).concat("0").concat("]".repeat(12)).getBytes(StandardCharsets.UTF_8));
    assertRejected(
        Arrays.copyOf(canonical, CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES + 1));
    assertRejected(
        (new String(canonical, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        (new String(canonical, StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8));

    ObjectNode unpaddedBytes = root.deepCopy();
    ObjectNode layout = (ObjectNode) unpaddedBytes.get("layout");
    layout.put("descriptorBytes", "YQ");
    assertRejected(canonical(unpaddedBytes));

    ObjectNode mismatchedDigest = root.deepCopy();
    ((ObjectNode) mismatchedDigest.get("layout")).put("descriptorDigest", digest("other"));
    assertRejected(canonical(mismatchedDigest));
  }

  @Test
  void constructorAcceptsDistinctValidOriginalReservationAndNewAnchorTuples() {
    CanonicalGameplayIssuerMemberV2Value value = fixture();

    assertThat(value.reservation().issuerCoverageOperationId())
        .isNotEqualTo(value.anchor().issuerCoverageOperationId());
    assertThat(value.reservation().issuerCoverageOperationFence())
        .isNotEqualTo(value.anchor().operationFence());
    assertThat(value.reservation().coverageFence()).isNotEqualTo(value.anchor().coverageFence());
    assertThat(value.reservation().inventorySnapshotRevision())
        .isNotEqualTo(value.anchor().issuerInventorySnapshotRevision());
    assertThat(
            CanonicalGameplayIssuerMemberV2Codec.decode(
                CanonicalGameplayIssuerMemberV2Codec.encode(value)))
        .isEqualTo(value);
  }

  static CanonicalGameplayIssuerMemberV2Value fixture() {
    CanonicalGameplayBindingIdentity owner =
        new CanonicalGameplayBindingIdentity(
            uuid(1),
            uuid(2),
            uuid(3),
            uuid(4),
            "SHARED",
            uuid(5),
            420L,
            uuid(6).toString(),
            uuid(7),
            LARGE.add(BigInteger.valueOf(5)),
            GameSessionAccountDelegationProfile.ISSUER,
            LARGE.add(BigInteger.valueOf(6)),
            BigInteger.valueOf(9),
            BigInteger.valueOf(16),
            BigInteger.valueOf(128));
    byte[] descriptorBytes = "issuer-layout-descriptor".getBytes(StandardCharsets.UTF_8);
    CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
            owner.issuerId(),
            owner.issuerIndexLayoutVersion(),
            owner.issuerIndexPartitionCount(),
            owner.issuerIndexPartitionCapacity(),
            CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
            descriptorBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(descriptorBytes));
    byte[] checkpointBytes = "account-issuer-checkpoint".getBytes(StandardCharsets.UTF_8);
    byte[] sourceEventBytes = "account-issuer-source-event".getBytes(StandardCharsets.UTF_8);
    CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint source =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            owner.issuerId(),
            owner.issuerAuthGeneration(),
            checkpointBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(checkpointBytes),
            sourceEventBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(sourceEventBytes));
    CanonicalGameplayIssuerMemberV2Value.Anchor anchor =
        new CanonicalGameplayIssuerMemberV2Value.Anchor(
            uuid(20),
            LARGE.add(BigInteger.valueOf(10)),
            LARGE.add(BigInteger.valueOf(11)),
            LARGE.add(BigInteger.valueOf(12)));
    UUID transitionId = uuid(30);
    CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation operation =
        new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
            uuid(33), LARGE.add(BigInteger.valueOf(13)), LARGE.add(BigInteger.valueOf(20)));
    CanonicalIssuerPartitionReservation reservation =
        new CanonicalIssuerPartitionReservation(
            uuid(31),
            owner,
            LARGE,
            transitionId,
            owner.bindingRef().partitionId(owner.issuerIndexPartitionCount()),
            CanonicalIssuerPartitionReservation.Lifecycle.BOUND,
            uuid(32),
            uuid(21),
            LARGE.add(BigInteger.valueOf(15)),
            LARGE.add(BigInteger.valueOf(16)),
            LARGE.add(BigInteger.valueOf(17)),
            LARGE.add(BigInteger.valueOf(14)));
    CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "coordination-prod",
            "deployment-21",
            List.of("coord-a", "coord-b"),
            "redis-build-7",
            "lua-registry-4",
            digest("lua-registry"),
            "READY",
            "boot-13",
            "startup-8",
            "storage-5",
            "aof-3");
    CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation =
        new CanonicalGameplayIssuerMemberV2Value.Incarnation(
            "workload=gamesession;namespace=game-session",
            digest("attestation"),
            deployment,
            digest("coord-ops-ro-acl"),
            digest("coord-redis-config"));
    return new CanonicalGameplayIssuerMemberV2Value(
        owner,
        LARGE,
        reservation,
        anchor,
        operation,
        LARGE.add(BigInteger.valueOf(15)),
        layout,
        source,
        incarnation);
  }

  private static CanonicalGameplayIssuerMemberV2Value copy(
      CanonicalGameplayIssuerMemberV2Value original,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerMemberV2Value.Anchor anchor) {
    return new CanonicalGameplayIssuerMemberV2Value(
        original.owner(),
        original.bindingGeneration(),
        reservation,
        anchor,
        original.memberOperation(),
        original.issuerControlEpoch(),
        original.layoutDescriptor(),
        original.sourceCheckpoint(),
        original.incarnation());
  }

  private static CanonicalGameplayIssuerMemberV2Value withMemberOperation(
      CanonicalGameplayIssuerMemberV2Value original,
      CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation memberOperation) {
    return new CanonicalGameplayIssuerMemberV2Value(
        original.owner(),
        original.bindingGeneration(),
        original.reservation(),
        original.anchor(),
        memberOperation,
        original.issuerControlEpoch(),
        original.layoutDescriptor(),
        original.sourceCheckpoint(),
        original.incarnation());
  }

  private static CanonicalGameplayIssuerMemberV2Value withReservation(
      CanonicalGameplayIssuerMemberV2Value original,
      CanonicalIssuerPartitionReservation reservation) {
    return new CanonicalGameplayIssuerMemberV2Value(
        original.owner(),
        original.bindingGeneration(),
        reservation,
        original.anchor(),
        original.memberOperation(),
        original.issuerControlEpoch(),
        original.layoutDescriptor(),
        original.sourceCheckpoint(),
        original.incarnation());
  }

  private static CanonicalGameplayIssuerMemberV2Value withLayout(
      CanonicalGameplayIssuerMemberV2Value original,
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layoutDescriptor) {
    return new CanonicalGameplayIssuerMemberV2Value(
        original.owner(),
        original.bindingGeneration(),
        original.reservation(),
        original.anchor(),
        original.memberOperation(),
        original.issuerControlEpoch(),
        layoutDescriptor,
        original.sourceCheckpoint(),
        original.incarnation());
  }

  private static byte[] canonical(ObjectNode object) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(object));
  }

  private static void assertRejected(byte[] encoded) {
    assertThatThrownBy(() -> CanonicalGameplayIssuerMemberV2Codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UUID uuid(long suffix) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
  }

  private static String digest(String input) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
