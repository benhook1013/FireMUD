package unit.net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceLeafApproval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartSessionReservationEvidenceLeafApprovalTest {
  private static final byte[] ACTIVE_LEAF_DER =
      "active account leaf".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] OVERLAP_LEAF_DER =
      "renewing account leaf".getBytes(StandardCharsets.US_ASCII);
  private static final String ACTIVE_FINGERPRINT = sha256(ACTIVE_LEAF_DER);
  private static final String OVERLAP_FINGERPRINT = sha256(OVERLAP_LEAF_DER);

  @TempDir Path temporaryDirectory;

  @Test
  void matchesOnlyTheCurrentLeafAndReadsTrustedReplacementOnEachCall() throws Exception {
    Path policy = temporaryDirectory.resolve("approved-leaves.txt");
    write(policy, "active=" + ACTIVE_FINGERPRINT + "\n");
    StartSessionReservationEvidenceLeafApproval approval = approval(policy);

    assertThat(approval.isApproved(evidence(ACTIVE_LEAF_DER))).isTrue();
    assertThat(approval.isApproved(evidence(OVERLAP_LEAF_DER))).isFalse();

    write(policy, "active=" + OVERLAP_FINGERPRINT + "\n");

    assertThat(approval.isApproved(evidence(ACTIVE_LEAF_DER))).isFalse();
    assertThat(approval.isApproved(evidence(OVERLAP_LEAF_DER))).isTrue();
  }

  @Test
  void acceptsOneExplicitOverlapOnlyUntilItsAbsoluteExpiry() throws Exception {
    Path policy = temporaryDirectory.resolve("approved-leaves.txt");
    long expiresAt = System.currentTimeMillis() + 60_000L;
    write(
        policy,
        "active="
            + ACTIVE_FINGERPRINT
            + "\noverlap="
            + OVERLAP_FINGERPRINT
            + ";expires-at="
            + expiresAt
            + "\n");
    StartSessionReservationEvidenceLeafApproval approval = approval(policy);

    assertThat(approval.isApproved(evidence(ACTIVE_LEAF_DER))).isTrue();
    assertThat(approval.isApproved(evidence(OVERLAP_LEAF_DER))).isTrue();

    write(
        policy,
        "active="
            + ACTIVE_FINGERPRINT
            + "\noverlap="
            + OVERLAP_FINGERPRINT
            + ";expires-at="
            + (System.currentTimeMillis() - 1L)
            + "\n");

    assertThat(approval.isApproved(evidence(ACTIVE_LEAF_DER))).isTrue();
    assertThat(approval.isApproved(evidence(OVERLAP_LEAF_DER))).isFalse();
  }

  @Test
  void missingUnreadableMalformedOrAmbiguousPolicyDenies() throws Exception {
    Path missing = temporaryDirectory.resolve("missing.txt");
    assertThat(approval(missing).isApproved(evidence(ACTIVE_LEAF_DER))).isFalse();

    Path directory = Files.createDirectory(temporaryDirectory.resolve("directory-policy"));
    assertThat(approval(directory).isApproved(evidence(ACTIVE_LEAF_DER))).isFalse();

    Path malformed = temporaryDirectory.resolve("malformed.txt");
    for (String content :
        new String[] {
          "",
          "active=" + ACTIVE_FINGERPRINT.toUpperCase() + "\n",
          "active=" + ACTIVE_FINGERPRINT + "\nactive=" + OVERLAP_FINGERPRINT + "\n",
          "active="
              + ACTIVE_FINGERPRINT
              + "\noverlap="
              + ACTIVE_FINGERPRINT
              + ";expires-at=9999999999999\n",
          "active="
              + ACTIVE_FINGERPRINT
              + "\noverlap="
              + OVERLAP_FINGERPRINT
              + ";expires-at=not-a-time\n",
          "active="
              + ACTIVE_FINGERPRINT
              + "\nactive="
              + OVERLAP_FINGERPRINT
              + "\nactive="
              + ACTIVE_FINGERPRINT
              + "\n"
        }) {
      write(malformed, content);
      assertThat(approval(malformed).isApproved(evidence(ACTIVE_LEAF_DER)))
          .as("policy content must fail closed: %s", content)
          .isFalse();
    }
  }

  @Test
  void rejectsMissingEvidenceAndRelativePolicyPaths() throws Exception {
    Path policy = temporaryDirectory.resolve("approved-leaves.txt");
    write(policy, "active=" + ACTIVE_FINGERPRINT + "\n");
    assertThat(approval(policy).isApproved(null)).isFalse();
    assertThat(
            new StartSessionReservationEvidenceLeafApproval("relative-policy.txt")
                .isApproved(evidence(ACTIVE_LEAF_DER)))
        .isFalse();
  }

  private static StartSessionReservationEvidenceLeafApproval approval(Path path) {
    return new StartSessionReservationEvidenceLeafApproval(path.toString());
  }

  private static GrpcPeerCertificateEvidence evidence(byte[] der) {
    X509Certificate leaf = mock(X509Certificate.class);
    try {
      when(leaf.getEncoded()).thenReturn(der);
    } catch (CertificateEncodingException exception) {
      throw new AssertionError(exception);
    }
    return GrpcPeerCertificateEvidence.fromCertificate(leaf).orElseThrow();
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void write(Path path, String value) throws Exception {
    Files.writeString(path, value, StandardCharsets.US_ASCII);
  }
}
