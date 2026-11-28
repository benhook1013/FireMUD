package net.firedevops.firemud.accountservice.repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned, byte-length-framed identities for Account-owned tenant-role operations. */
public final class AccountTenantRoleMutationDigest {
  private static final String REQUEST_DOMAIN = "firemud/account/tenant-role/request/v1";
  private static final String RESULT_DOMAIN = "firemud/account/tenant-role/result/v1";

  private AccountTenantRoleMutationDigest() {}

  public static byte[] requestBytes(
      UUID requestId,
      UUID actorAccountUuid,
      UUID tenantUuid,
      UUID targetAccountUuid,
      String action,
      long expectedActorMembershipVersion,
      long expectedTargetMembershipVersion) {
    if (requestId == null
        || actorAccountUuid == null
        || tenantUuid == null
        || targetAccountUuid == null
        || action == null
        || expectedActorMembershipVersion <= 0
        || expectedTargetMembershipVersion <= 0) {
      throw new IllegalArgumentException("Complete tenant-role request identity is required");
    }
    return framed(
        REQUEST_DOMAIN,
        requestId.toString(),
        actorAccountUuid.toString(),
        tenantUuid.toString(),
        targetAccountUuid.toString(),
        action,
        Long.toString(expectedActorMembershipVersion),
        Long.toString(expectedTargetMembershipVersion));
  }

  public static byte[] resultBytes(
      UUID requestId,
      String requestDigest,
      UUID tenantUuid,
      UUID auditEventId,
      String auditEventType,
      String auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      java.util.List<MemberResult> members) {
    if (requestId == null
        || requestDigest == null
        || tenantUuid == null
        || auditEventId == null
        || auditEventType == null
        || auditOccurredAt == null
        || auditPayloadDigest == null
        || auditPayload == null
        || members == null
        || members.isEmpty()) {
      throw new IllegalArgumentException("Complete tenant-role operation result is required");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream framed = new DataOutputStream(bytes)) {
      writeField(framed, RESULT_DOMAIN.getBytes(StandardCharsets.UTF_8));
      writeField(framed, requestId.toString().getBytes(StandardCharsets.UTF_8));
      writeField(framed, requestDigest.getBytes(StandardCharsets.UTF_8));
      writeField(framed, tenantUuid.toString().getBytes(StandardCharsets.UTF_8));
      writeField(framed, auditEventId.toString().getBytes(StandardCharsets.UTF_8));
      writeField(framed, auditEventType.getBytes(StandardCharsets.UTF_8));
      writeField(framed, auditOccurredAt.getBytes(StandardCharsets.UTF_8));
      writeField(framed, auditPayloadDigest.getBytes(StandardCharsets.UTF_8));
      writeField(framed, auditPayload);
      framed.writeInt(members.size());
      for (MemberResult member : members.stream().sorted().toList()) {
        writeField(framed, member.accountUuid().toString().getBytes(StandardCharsets.UTF_8));
        writeField(framed, member.tenantUuid().toString().getBytes(StandardCharsets.UTF_8));
        writeField(
            framed, Long.toString(member.membershipVersion()).getBytes(StandardCharsets.UTF_8));
        writeField(
            framed,
            Long.toString(member.membershipAuthorityGeneration()).getBytes(StandardCharsets.UTF_8));
        writeField(framed, Long.toString(member.eventSequence()).getBytes(StandardCharsets.UTF_8));
        writeField(framed, member.eventRequestId().getBytes(StandardCharsets.UTF_8));
        writeField(framed, member.eventId().getBytes(StandardCharsets.UTF_8));
        writeField(framed, member.eventDigest().getBytes(StandardCharsets.UTF_8));
        framed.writeBoolean(member.callerBoundAuthorityInvalidated());
        writeField(framed, member.eventPayload());
      }
      framed.flush();
      return bytes.toByteArray();
    } catch (IOException impossibleForByteArray) {
      throw new IllegalStateException("Tenant-role result framing failed", impossibleForByteArray);
    }
  }

  public static String sha256(byte[] exactBytes) {
    if (exactBytes == null) {
      throw new IllegalArgumentException("Exact digest input bytes are required");
    }
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] framed(String... fields) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream framed = new DataOutputStream(bytes)) {
      for (String field : fields) {
        writeField(framed, field.getBytes(StandardCharsets.UTF_8));
      }
      framed.flush();
      return bytes.toByteArray();
    } catch (IOException impossibleForByteArray) {
      throw new IllegalStateException("Tenant-role request framing failed", impossibleForByteArray);
    }
  }

  private static void writeField(DataOutputStream output, byte[] field) throws IOException {
    output.writeInt(field.length);
    output.write(field);
  }

  public record MemberResult(
      UUID accountUuid,
      UUID tenantUuid,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long eventSequence,
      String eventRequestId,
      String eventId,
      String eventDigest,
      boolean callerBoundAuthorityInvalidated,
      byte[] eventPayload)
      implements Comparable<MemberResult> {
    public MemberResult {
      if (accountUuid == null
          || tenantUuid == null
          || membershipVersion <= 0
          || membershipAuthorityGeneration <= 0
          || eventSequence <= 0
          || eventRequestId == null
          || eventRequestId.isBlank()
          || eventId == null
          || eventId.isBlank()
          || eventDigest == null
          || !eventDigest.matches("sha256:[0-9a-f]{64}")
          || eventPayload == null) {
        throw new IllegalArgumentException("Complete tenant-role member result is required");
      }
      eventPayload = eventPayload.clone();
    }

    @Override
    public byte[] eventPayload() {
      return eventPayload.clone();
    }

    @Override
    public int compareTo(MemberResult other) {
      return accountUuid.toString().compareTo(other.accountUuid.toString());
    }
  }
}
