package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleMutationDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleMutationDigest.MemberResult;
import org.junit.jupiter.api.Test;

class AccountTenantRoleMutationDigestTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TARGET_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String ACTION = "GRANT_DESIGNER_雪";
  private static final long ACTOR_VERSION = 9_007_199_254_740_993L;
  private static final long TARGET_VERSION = 9_223_372_036_854_775_806L;

  @Test
  void requestUsesUtf8ByteLengthFramingAndMatchesIndependentFixedSha256Vector() {
    assertThat(List.of(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID)).doesNotHaveDuplicates();

    byte[] independentPreimage =
        independentFrame(
            "firemud/account/tenant-role/request/v1",
            REQUEST_ID.toString(),
            ACTOR_ID.toString(),
            TENANT_ID.toString(),
            TARGET_ID.toString(),
            ACTION,
            "9007199254740993",
            "9223372036854775806");

    assertThat(ACTION.getBytes(StandardCharsets.UTF_8)).hasSize(18);
    assertThat(
            AccountTenantRoleMutationDigest.requestBytes(
                REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, ACTOR_VERSION, TARGET_VERSION))
        .containsExactly(independentPreimage);
    assertThat(independentSha256(independentPreimage))
        .isEqualTo("sha256:9d43eb2da0eabcfb264a547d60f9b0c8cdf2b10bcef9d1253c0559d404b7f1ba");
    assertThat(AccountTenantRoleMutationDigest.sha256(independentPreimage))
        .isEqualTo(independentSha256(independentPreimage));
  }

  @Test
  void everyRequestFieldChangesTheDigest() {
    String original = requestDigest(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 7L, 11L);

    assertThat(
            requestDigest(
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                ACTOR_ID,
                TENANT_ID,
                TARGET_ID,
                ACTION,
                7L,
                11L))
        .isNotEqualTo(original);
    assertThat(
            requestDigest(
                REQUEST_ID,
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                TENANT_ID,
                TARGET_ID,
                ACTION,
                7L,
                11L))
        .isNotEqualTo(original);
    assertThat(
            requestDigest(
                REQUEST_ID,
                ACTOR_ID,
                uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                TARGET_ID,
                ACTION,
                7L,
                11L))
        .isNotEqualTo(original);
    assertThat(
            requestDigest(
                REQUEST_ID,
                ACTOR_ID,
                TENANT_ID,
                uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                ACTION,
                7L,
                11L))
        .isNotEqualTo(original);
    assertThat(
            requestDigest(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, "REVOKE_DESIGNER", 7L, 11L))
        .isNotEqualTo(original);
    assertThat(requestDigest(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 8L, 11L))
        .isNotEqualTo(original);
    assertThat(requestDigest(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 7L, 12L))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsMissingRequestFieldsAndNonpositiveExpectedVersions() {
    assertThatThrownBy(() -> request(null, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, null, TENANT_ID, TARGET_ID, ACTION, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, ACTOR_ID, null, TARGET_ID, ACTION, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, ACTOR_ID, TENANT_ID, null, ACTION, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, null, 1L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 0L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST_ID, ACTOR_ID, TENANT_ID, TARGET_ID, ACTION, 1L, -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultFramesEveryOperationAuditAndMemberFieldAndSortsMembersDeterministically() {
    MemberResult first =
        member(
            uuid("66666666-6666-4666-8666-666666666666"),
            TENANT_ID,
            1,
            2,
            3,
            "event-request",
            "event-id",
            "sha256:" + "d".repeat(64),
            false,
            new byte[] {1, 2, 3});
    MemberResult second =
        member(
            uuid("55555555-5555-4555-8555-555555555555"),
            TENANT_ID,
            2,
            3,
            4,
            "event-request-2",
            "event-id-2",
            "sha256:" + "e".repeat(64),
            true,
            new byte[] {2, 3, 4});
    List<MemberResult> members = List.of(first, second);
    byte[] auditPayload = new byte[] {0, (byte) 0xff, 3, 0};
    byte[] framed =
        resultBytes(
            REQUEST_ID,
            "sha256:" + "a".repeat(64),
            TENANT_ID,
            uuid("77777777-7777-4777-8777-777777777777"),
            "TENANT_ROLE_CHANGED",
            "2026-10-05T12:34:56Z",
            "sha256:" + "b".repeat(64),
            auditPayload,
            members);

    assertThat(framed)
        .containsExactly(
            independentResultFrame(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                List.of(second, first)));
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                List.of(second, first)))
        .containsExactly(framed);

    assertThat(
            resultBytes(
                uuid("88888888-8888-4888-8888-888888888888"),
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "c".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                uuid("99999999-9999-4999-8999-999999999999"),
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_REVOKED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-06T12:34:56Z",
                "sha256:" + "b".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "c".repeat(64),
                auditPayload,
                members))
        .isNotEqualTo(framed);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                new byte[] {0, 1},
                members))
        .isNotEqualTo(framed);

    MemberResult baselineMember = first;
    List<MemberResult> changedMembers =
        List.of(
            member(
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                TENANT_ID,
                1,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                uuid("99999999-9999-4999-8999-999999999999"),
                1,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                4,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                4,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                4,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                3,
                "event-request-changed",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                3,
                "event-request",
                "event-id-changed",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "e".repeat(64),
                false,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                true,
                new byte[] {1, 2, 3}),
            member(
                baselineMember.accountUuid(),
                TENANT_ID,
                1,
                2,
                3,
                "event-request",
                "event-id",
                "sha256:" + "d".repeat(64),
                false,
                new byte[] {3, 2, 1}));
    for (MemberResult changed : changedMembers) {
      assertThat(
              resultBytes(
                  REQUEST_ID,
                  "sha256:" + "a".repeat(64),
                  TENANT_ID,
                  uuid("77777777-7777-4777-8777-777777777777"),
                  "TENANT_ROLE_CHANGED",
                  "2026-10-05T12:34:56Z",
                  "sha256:" + "b".repeat(64),
                  auditPayload,
                  List.of(changed, second)))
          .isNotEqualTo(framed);
    }
  }

  @Test
  void memberEventPayloadIsDefensivelyCopiedOnConstructionAndAccess() {
    byte[] original = new byte[] {1, 2, 3};
    MemberResult member =
        new MemberResult(
            uuid("66666666-6666-4666-8666-666666666666"),
            TENANT_ID,
            1,
            2,
            3,
            "event-request",
            "event-id",
            "sha256:" + "d".repeat(64),
            true,
            original);
    original[0] = 99;
    byte[] accessed = member.eventPayload();
    accessed[1] = 99;

    assertThat(member.eventPayload()).containsExactly(1, 2, 3);
    assertThat(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                new byte[] {0},
                List.of(member)))
        .containsExactly(
            resultBytes(
                REQUEST_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                uuid("77777777-7777-4777-8777-777777777777"),
                "TENANT_ROLE_CHANGED",
                "2026-10-05T12:34:56Z",
                "sha256:" + "b".repeat(64),
                new byte[] {0},
                List.of(
                    new MemberResult(
                        uuid("66666666-6666-4666-8666-666666666666"),
                        TENANT_ID,
                        1,
                        2,
                        3,
                        "event-request",
                        "event-id",
                        "sha256:" + "d".repeat(64),
                        true,
                        new byte[] {1, 2, 3}))));
  }

  @Test
  void rejectsIncompleteResultsAndNonpositiveMemberCounters() {
    assertThatThrownBy(
            () ->
                new MemberResult(
                    null,
                    TENANT_ID,
                    1,
                    1,
                    1,
                    "r",
                    "i",
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    null,
                    1,
                    1,
                    1,
                    "r",
                    "i",
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    null,
                    "i",
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    "r",
                    null,
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    "r",
                    "i",
                    null,
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    "r",
                    "i",
                    "sha256:" + "d".repeat(64),
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> memberWithCounters(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> memberWithCounters(1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> memberWithCounters(1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    " ",
                    "i",
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemberResult(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    TENANT_ID,
                    1,
                    1,
                    1,
                    "r",
                    " ",
                    "sha256:" + "d".repeat(64),
                    false,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingTopLevelResultFieldsAndMissingMembers() {
    MemberResult member =
        member(
            uuid("66666666-6666-4666-8666-666666666666"),
            TENANT_ID,
            1,
            1,
            1,
            "event-request",
            "event-id",
            "sha256:" + "d".repeat(64),
            false,
            new byte[] {1});
    assertResultBytesRejected(
        null,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        null,
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        null,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        null,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        null,
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        null,
        "payload-digest",
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        null,
        new byte[] {1},
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        null,
        List.of(member));
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        null);
    assertResultBytesRejected(
        REQUEST_ID,
        "request-digest",
        TENANT_ID,
        TARGET_ID,
        "event-type",
        "occurred-at",
        "payload-digest",
        new byte[] {1},
        List.of());
  }

  private static String requestDigest(
      UUID requestId,
      UUID actorId,
      UUID tenantId,
      UUID targetId,
      String action,
      long actorVersion,
      long targetVersion) {
    return AccountTenantRoleMutationDigest.sha256(
        request(requestId, actorId, tenantId, targetId, action, actorVersion, targetVersion));
  }

  private static byte[] request(
      UUID requestId,
      UUID actorId,
      UUID tenantId,
      UUID targetId,
      String action,
      long actorVersion,
      long targetVersion) {
    return AccountTenantRoleMutationDigest.requestBytes(
        requestId, actorId, tenantId, targetId, action, actorVersion, targetVersion);
  }

  private static byte[] resultBytes(
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      UUID auditEventId,
      String auditEventType,
      String auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      List<MemberResult> members) {
    return AccountTenantRoleMutationDigest.resultBytes(
        requestId,
        requestDigest,
        tenantId,
        auditEventId,
        auditEventType,
        auditOccurredAt,
        auditPayloadDigest,
        auditPayload,
        members);
  }

  private static void assertResultBytesRejected(
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      UUID auditEventId,
      String auditEventType,
      String auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      List<MemberResult> members) {
    assertThatThrownBy(
            () ->
                resultBytes(
                    requestId,
                    requestDigest,
                    tenantId,
                    auditEventId,
                    auditEventType,
                    auditOccurredAt,
                    auditPayloadDigest,
                    auditPayload,
                    members))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MemberResult member(
      UUID accountId,
      UUID tenantId,
      long version,
      long generation,
      long sequence,
      String eventRequestId,
      String eventId,
      String eventDigest,
      boolean invalidated,
      byte[] payload) {
    return new MemberResult(
        accountId,
        tenantId,
        version,
        generation,
        sequence,
        eventRequestId,
        eventId,
        eventDigest,
        invalidated,
        payload);
  }

  private static MemberResult memberWithCounters(long version, long generation, long sequence) {
    return new MemberResult(
        uuid("66666666-6666-4666-8666-666666666666"),
        TENANT_ID,
        version,
        generation,
        sequence,
        "r",
        "i",
        "sha256:" + "d".repeat(64),
        false,
        new byte[0]);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static byte[] independentResultFrame(
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      UUID auditEventId,
      String auditEventType,
      String auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      List<MemberResult> members) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (DataOutputStream framed = new DataOutputStream(output)) {
      writeIndependentField(
          framed, "firemud/account/tenant-role/result/v1".getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, requestId.toString().getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, requestDigest.getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, tenantId.toString().getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, auditEventId.toString().getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, auditEventType.getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, auditOccurredAt.getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, auditPayloadDigest.getBytes(StandardCharsets.UTF_8));
      writeIndependentField(framed, auditPayload);
      framed.writeInt(members.size());
      for (MemberResult member : members.stream().sorted().toList()) {
        writeIndependentField(
            framed, member.accountUuid().toString().getBytes(StandardCharsets.UTF_8));
        writeIndependentField(
            framed, member.tenantUuid().toString().getBytes(StandardCharsets.UTF_8));
        writeIndependentField(
            framed, Long.toString(member.membershipVersion()).getBytes(StandardCharsets.UTF_8));
        writeIndependentField(
            framed,
            Long.toString(member.membershipAuthorityGeneration()).getBytes(StandardCharsets.UTF_8));
        writeIndependentField(
            framed, Long.toString(member.eventSequence()).getBytes(StandardCharsets.UTF_8));
        writeIndependentField(framed, member.eventRequestId().getBytes(StandardCharsets.UTF_8));
        writeIndependentField(framed, member.eventId().getBytes(StandardCharsets.UTF_8));
        writeIndependentField(framed, member.eventDigest().getBytes(StandardCharsets.UTF_8));
        framed.writeBoolean(member.callerBoundAuthorityInvalidated());
        writeIndependentField(framed, member.eventPayload());
      }
      framed.flush();
      return output.toByteArray();
    } catch (IOException impossibleForByteArray) {
      throw new IllegalStateException(impossibleForByteArray);
    }
  }

  private static byte[] independentFrame(String... values) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (DataOutputStream framed = new DataOutputStream(output)) {
      for (String value : values) {
        writeIndependentField(framed, value.getBytes(StandardCharsets.UTF_8));
      }
      framed.flush();
      return output.toByteArray();
    } catch (IOException impossibleForByteArray) {
      throw new IllegalStateException(impossibleForByteArray);
    }
  }

  private static void writeIndependentField(DataOutputStream output, byte[] bytes)
      throws IOException {
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String independentSha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossibleOnStandardJdk) {
      throw new IllegalStateException(impossibleOnStandardJdk);
    }
  }
}
