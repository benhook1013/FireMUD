package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.junit.jupiter.api.Test;

class CanonicalGameplayIssuerBindingLocalAcknowledgementCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final BigInteger LARGE = new BigInteger("9007199254740997");

  @Test
  void roundTripsDistinctOriginalReservationAnchorAndLocalOperationEvidence() throws Exception {
    var evidence = fixture();
    byte[] encoded = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(evidence);

    var decoded = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(encoded);
    ObjectNode root = (ObjectNode) JSON.readTree(encoded);

    assertThat(CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(decoded))
        .containsExactly(encoded);
    assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
    assertThat(root.path("scope").textValue()).isEqualTo("ISSUER_BINDING_LOCAL");
    assertThat(root.path("issuerBindingLocalAcknowledgementGeneration").textValue())
        .isEqualTo("83");
    assertThat(root.path("globalIssuerCoverageGeneration").textValue()).isEqualTo("NOT_YET_ISSUED");
    assertThat(root.path("anchor").path("globalCoverageGeneration").textValue()).isEqualTo("5");
    assertThat(root.path("reservation").path("issuerCoverageOperationId").textValue())
        .isNotEqualTo(root.path("anchor").path("issuerCoverageOperationId").textValue());
    assertThat(root.path("reservation").path("coverageFence").textValue())
        .isNotEqualTo(root.path("anchor").path("coverageFence").textValue());
    assertThat(root.path("memberOperation").path("generation").textValue())
        .isNotEqualTo(root.path("globalIssuerCoverageGeneration").textValue());
  }

  @Test
  void roundTripsGenerationBeyondJsonExactIntegerRange() throws Exception {
    byte[] encoded = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(fixture(LARGE));
    var decoded = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(encoded);

    assertThat(JSON.readTree(encoded).path("issuerBindingLocalAcknowledgementGeneration").textValue())
        .isEqualTo(LARGE.toString());
    assertThat(decoded.issuerBindingLocalAcknowledgementGeneration()).isEqualTo(LARGE);
    assertThat(CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(decoded))
        .containsExactly(encoded);
  }

  @Test
  void rejectsUnknownFieldsAndAnAccountOrGlobalCoverageAlias() throws Exception {
    byte[] encoded = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(fixture());
    ObjectNode original = (ObjectNode) JSON.readTree(encoded);
    ObjectNode unknown = original.deepCopy();
    unknown.put("accountIndexAcknowledgementSchemaVersion", 2);
    ObjectNode globalCoverage = original.deepCopy();
    globalCoverage.put("globalIssuerCoverageGeneration", "17");
    ObjectNode unissuedAcknowledgement = original.deepCopy();
    unissuedAcknowledgement.put("issuerBindingLocalAcknowledgementGeneration", "NOT_YET_ISSUED");

    assertThatThrownBy(
            () ->
                CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(
                    JSON.writeValueAsBytes(unknown)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing or unknown fields");
    assertThatThrownBy(
            () ->
                CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(
                    JSON.writeValueAsBytes(globalCoverage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version or scope");
    assertThatThrownBy(
            () ->
                CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(
                    JSON.writeValueAsBytes(unissuedAcknowledgement)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDifferentOriginalReservationProvenanceUnderTheSameBinding() {
    var original = fixture();
    var changedReservation =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Reservation(
            original.reservation().reservationId(),
            original.reservation().transitionId(),
            original.reservation().reservationFence(),
            original.reservation().partitionId(),
            original.reservation().lifecycle(),
            uuid(51),
            original.reservation().issuerCoverageOperationFence().add(BigInteger.ONE),
            original.reservation().coverageFence().add(BigInteger.ONE),
            original.reservation().inventorySnapshotRevision().add(BigInteger.ONE),
            original.reservation().inventoryRevision());

    assertThatThrownBy(
            () ->
                new CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence(
                    original.operationId(),
                    original.issuerBindingLocalAcknowledgementGeneration(),
                    original.requestId(),
                    original.transitionId(),
                    original.candidateAccountIndexFence(),
                    original.issuerId(),
                    original.bindingRef(),
                    original.bindingGeneration(),
                    original.memberValueBytes(),
                    original.memberValueDigest(),
                    changedReservation,
                    original.anchor(),
                    original.memberOperation(),
                    original.currentSource(),
                    original.physicalReadback(),
                    original.capacity(),
                    original.sqlCurrentnessDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact member and proof tuples");
  }

  private static CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence fixture() {
    return fixture(BigInteger.valueOf(83));
  }

  private static CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence fixture(
      BigInteger acknowledgementGeneration) {
    var sourceMember = CanonicalGameplayIssuerMemberV2ValueCodecTest.fixture();
    var original = sourceMember.reservation();
    var reservation =
        new CanonicalIssuerPartitionReservation(
            original.reservationId(),
            sourceMember.owner(),
            sourceMember.bindingGeneration(),
            original.transitionId(),
            original.partitionId(),
            CanonicalIssuerPartitionReservation.Lifecycle.RESERVED,
            original.reservationFence(),
            original.issuerCoverageOperationId(),
            original.issuerCoverageOperationFence(),
            original.coverageFence(),
            original.inventorySnapshotRevision(),
            original.inventoryRevision());
    var member =
        new CanonicalGameplayIssuerMemberV2Value(
            sourceMember.owner(),
            sourceMember.bindingGeneration(),
            reservation,
            sourceMember.anchor(),
            sourceMember.memberOperation(),
            sourceMember.anchor().operationFence(),
            sourceMember.layoutDescriptor(),
            sourceMember.sourceCheckpoint(),
            sourceMember.incarnation());
    byte[] memberBytes = CanonicalGameplayIssuerMemberV2Codec.encode(member);
    byte[] readbackBytes = "exact-physical-field-set/v1".getBytes(StandardCharsets.UTF_8);
    byte[] checkpointBytes = member.sourceCheckpoint().exactCheckpointBytes();
    byte[] eventBytes = member.sourceCheckpoint().latestIssuerEventBytes();
    byte[] incarnationBytes =
        CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(member.incarnation());
    byte[] layoutBytes = member.layoutDescriptor().exactDescriptorBytes();
    byte[] anchorBytes = "installed-anchor/v1".getBytes(StandardCharsets.UTF_8);
    BigInteger one = BigInteger.ONE;
    BigInteger bound =
        member
            .layoutDescriptor()
            .partitionCount()
            .multiply(member.layoutDescriptor().partitionCapacity());
    return new CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence(
        member.memberOperation().operationId(),
        acknowledgementGeneration,
        uuid(41),
        member.reservation().transitionId(),
        uuid(42),
        member.owner().issuerId(),
        member.owner().bindingRef().bytes(),
        member.bindingGeneration(),
        memberBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(memberBytes),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Reservation(
            reservation.reservationId(),
            reservation.transitionId(),
            reservation.reservationFence(),
            reservation.partitionId(),
            reservation.lifecycle().name(),
            reservation.issuerCoverageOperationId(),
            reservation.issuerCoverageOperationFence(),
            reservation.coverageFence(),
            reservation.inventorySnapshotRevision(),
            reservation.inventoryRevision()),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Anchor(
            member.anchor().issuerCoverageOperationId(),
            member.anchor().issuerCoverageOperationId(),
            member.anchor().operationFence(),
            member.anchor().coverageFence(),
            member.anchor().issuerInventorySnapshotRevision(),
            BigInteger.valueOf(5),
            member.issuerControlEpoch(),
            member.layoutDescriptor().layoutVersion(),
            member.layoutDescriptor().partitionCount(),
            member.layoutDescriptor().partitionCapacity(),
            member.layoutDescriptor().mappingIdentity(),
            layoutBytes,
            member.layoutDescriptor().descriptorDigest(),
            anchorBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(anchorBytes)),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.MemberOperation(
            member.memberOperation().operationId(),
            member.memberOperation().operationFence(),
            member.memberOperation().coverageGeneration()),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.CurrentSource(
            member.owner().issuerAuthGeneration(),
            checkpointBytes,
            member.sourceCheckpoint().checkpointDigest(),
            eventBytes,
            member.sourceCheckpoint().latestIssuerEventDigest(),
            incarnationBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(incarnationBytes)),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.PhysicalReadback(
            memberBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(memberBytes),
            memberBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(memberBytes),
            CanonicalGameplayIssuerMemberV2Codec.sha256(readbackBytes),
            2,
            4,
            1,
            1,
            1),
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Capacity(
            reservation.partitionId(),
            member.layoutDescriptor().partitionCapacity(),
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            one,
            one,
            bound),
        CanonicalGameplayIssuerMemberV2Codec.sha256(
            "current-sql-cohort/v1".getBytes(StandardCharsets.UTF_8)));
  }

  private static UUID uuid(long value) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", value));
  }
}
