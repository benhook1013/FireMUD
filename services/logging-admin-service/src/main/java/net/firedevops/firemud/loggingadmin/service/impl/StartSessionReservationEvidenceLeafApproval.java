package net.firedevops.firemud.loggingadmin.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Method-scoped approved leaf instances for ReadCurrentClaimEvidence. */
@Component
public final class StartSessionReservationEvidenceLeafApproval {
  private static final int MAX_POLICY_BYTES = 180;
  private static final Pattern ACTIVE_ENTRY = Pattern.compile("active=([0-9a-f]{64})");
  private static final Pattern OVERLAP_ENTRY =
      Pattern.compile("overlap=([0-9a-f]{64});expires-at=([0-9]{1,19})");

  private final String approvalFile;

  public StartSessionReservationEvidenceLeafApproval(
      @Value("${firemud.grpc.account-reservation-evidence.approved-leaf-file:}")
          String approvalFile) {
    this.approvalFile = approvalFile;
  }

  /** Reads the trusted policy on every request; missing or invalid policy denies the peer. */
  public boolean isApproved(GrpcPeerCertificateEvidence certificateEvidence) {
    if (certificateEvidence == null || approvalFile == null || approvalFile.isBlank()) {
      return false;
    }

    Optional<ApprovalSet> approvalSet = readApprovalSet();
    if (approvalSet.isEmpty()) {
      return false;
    }

    String presentedFingerprint = certificateEvidence.leafSha256();
    ApprovalSet approval = approvalSet.orElseThrow();
    if (approval.activeFingerprint().equals(presentedFingerprint)) {
      return true;
    }
    return approval.overlapFingerprint() != null
        && approval.overlapFingerprint().equals(presentedFingerprint)
        && Instant.now().toEpochMilli() < approval.overlapExpiresAtEpochMillis();
  }

  private Optional<ApprovalSet> readApprovalSet() {
    try {
      Path path = Path.of(approvalFile);
      if (!path.isAbsolute() || !Files.isRegularFile(path)) {
        return Optional.empty();
      }
      byte[] content;
      try (InputStream input = Files.newInputStream(path)) {
        content = input.readNBytes(MAX_POLICY_BYTES + 1);
      }
      if (content.length == 0 || content.length > MAX_POLICY_BYTES) {
        return Optional.empty();
      }
      String text = decodeStrictUtf8(content);
      List<String> lines = text.lines().toList();
      if (lines.isEmpty() || lines.size() > 2) {
        return Optional.empty();
      }

      Matcher active = ACTIVE_ENTRY.matcher(lines.getFirst());
      if (!active.matches()) {
        return Optional.empty();
      }
      String activeFingerprint = active.group(1);
      if (lines.size() == 1) {
        return Optional.of(new ApprovalSet(activeFingerprint, null, 0L));
      }

      Matcher overlap = OVERLAP_ENTRY.matcher(lines.get(1));
      if (!overlap.matches() || activeFingerprint.equals(overlap.group(1))) {
        return Optional.empty();
      }
      long expiresAt;
      try {
        expiresAt = Long.parseLong(overlap.group(2));
      } catch (NumberFormatException exception) {
        return Optional.empty();
      }
      if (expiresAt <= 0L) {
        return Optional.empty();
      }
      return Optional.of(new ApprovalSet(activeFingerprint, overlap.group(1), expiresAt));
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static String decodeStrictUtf8(byte[] value) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private record ApprovalSet(
      String activeFingerprint, String overlapFingerprint, long overlapExpiresAtEpochMillis) {}
}
