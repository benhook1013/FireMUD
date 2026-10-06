package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import org.junit.jupiter.api.Test;

class AccountJoinOperationRepositoryCanonicalTest {
  private static final String STREAM =
      "account:auth-authority:v1:membership/f4df3b0a-5fc6-4f86-9efc-0f053103b200/"
          + "4347218b-6914-4d50-a5ce-1b4836034f45";
  private static final String SHA256 = "sha256:" + "1".repeat(64);

  @Test
  void terminalProofBindsFirstSequenceAndExactMembershipRevision() {
    CanonicalJoinTerminalProof proof = validProof();

    assertThat(proof.eventStreamKey()).isEqualTo(STREAM);
    assertThat(proof.eventSequence()).isEqualTo(1L);
    assertThat(proof.membershipId()).isEqualTo(73L);
    assertThat(proof.membershipVersion()).isEqualTo(2L);
    assertThat(proof.membershipAuthorityGeneration()).isEqualTo(1L);
  }

  @Test
  void terminalProofRejectsNonFirstSequenceAndUnsupportedMembershipRevision() {
    assertThatThrownBy(
            () ->
                new CanonicalJoinTerminalProof(
                    STREAM,
                    2L,
                    "event-1",
                    SHA256,
                    UUID.fromString("75c70a9e-beb4-3ee2-9d9d-50e28f1123d6"),
                    SHA256,
                    Instant.parse("2026-10-04T00:00:00Z"),
                    5L,
                    73L,
                    2L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CanonicalJoinTerminalProof(
                    STREAM,
                    1L,
                    "event-1",
                    SHA256,
                    UUID.fromString("75c70a9e-beb4-3ee2-9d9d-50e28f1123d6"),
                    SHA256,
                    Instant.parse("2026-10-04T00:00:00Z"),
                    5L,
                    73L,
                    3L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalJoinTerminalProof validProof() {
    return new CanonicalJoinTerminalProof(
        STREAM,
        1L,
        "event-1",
        SHA256,
        UUID.fromString("75c70a9e-beb4-3ee2-9d9d-50e28f1123d6"),
        SHA256,
        Instant.parse("2026-10-04T00:00:00Z"),
        5L,
        73L,
        2L,
        1L);
  }
}
