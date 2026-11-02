package unit.net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.operator.RealmGrantMutation;
import net.firedevops.firemud.common.operator.RealmGrantMutation.Operation;
import org.junit.jupiter.api.Test;

class RealmGrantMutationTest {
  private static final Scope GRANT =
      new Scope(
          UUID.fromString("11111111-1111-4111-8111-111111111111"),
          UUID.fromString("22222222-2222-4222-8222-222222222222"),
          "world",
          "private-realm",
          UUID.fromString("33333333-3333-4333-8333-333333333333"));
  private static final Instant EXPIRY = Instant.parse("2026-11-03T01:00:00.123456789Z");

  @Test
  void createMatchesIndependentSevenFieldByteGrammarVector() {
    var action = mutation(Operation.CREATE, null, EXPIRY, "invite");
    assertThat(action.canonicalPreimage()).hasSize(623);
    assertThat(action.mutationDigest())
        .isEqualTo("02b15bc519f034f7e70ba1a4acef87564d0eb1881555b21272db9d6cc59ae6bc");
  }

  @Test
  void operationSpecificAbsenceAndExpectedRevisionAreEnforced() {
    assertThatThrownBy(() -> mutation(Operation.CREATE, 1L, EXPIRY, "invite"))
        .isInstanceOf(IllegalArgumentException.class);
    for (Operation operation :
        new Operation[] {Operation.EXTEND, Operation.REVOKE, Operation.REGRANT}) {
      assertThatThrownBy(
              () ->
                  mutation(
                      operation, null, operation == Operation.REVOKE ? null : EXPIRY, "change"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  mutation(operation, 0L, operation == Operation.REVOKE ? null : EXPIRY, "change"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> mutation(Operation.REVOKE, 1L, EXPIRY, "revoke"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(mutation(Operation.CREATE, null, EXPIRY, "invite").grantExpiresAt())
        .isEqualTo(EXPIRY);
    assertThat(mutation(Operation.REVOKE, 1L, null, "revoke").grantExpiresAt()).isNull();
  }

  @Test
  void everySemanticChangeBindsTheCanonicalMutation() {
    var original = mutation(Operation.EXTEND, 2L, EXPIRY, "extend");
    assertThat(original.mutationDigest()).matches("[0-9a-f]{64}");
    assertThat(original.mutationDigest())
        .isNotEqualTo(mutation(Operation.REGRANT, 2L, EXPIRY, "extend").mutationDigest());
    assertThat(original.mutationDigest())
        .isNotEqualTo(mutation(Operation.EXTEND, 3L, EXPIRY, "extend").mutationDigest());
    assertThat(original.mutationDigest())
        .isNotEqualTo(
            mutation(Operation.EXTEND, 2L, EXPIRY.plusNanos(1), "extend").mutationDigest());
    assertThat(original.mutationDigest())
        .isNotEqualTo(mutation(Operation.EXTEND, 2L, EXPIRY, "different reason").mutationDigest());
    var other =
        new Scope(
            GRANT.accountId(),
            GRANT.tenantId(),
            GRANT.worldSlug(),
            GRANT.realmSlug(),
            UUID.randomUUID());
    assertThat(original.mutationDigest())
        .isNotEqualTo(
            new RealmGrantMutation("prod", other, Operation.EXTEND, 2L, EXPIRY, "extend")
                .mutationDigest());
    assertThat(original.mutationDigest())
        .isNotEqualTo(
            new RealmGrantMutation("other", GRANT, Operation.EXTEND, 2L, EXPIRY, "extend")
                .mutationDigest());
  }

  @Test
  void auditReasonUsesExistingNfcGrammarAndRejectsMalformedOrBlankInput() {
    assertThat(mutation(Operation.CREATE, null, EXPIRY, "cafe\u0301").mutationDigest())
        .isEqualTo(mutation(Operation.CREATE, null, EXPIRY, "caf\u00e9").mutationDigest());
    assertThatThrownBy(() -> mutation(Operation.CREATE, null, EXPIRY, "\u00a0\t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> mutation(Operation.CREATE, null, EXPIRY, String.valueOf((char) 0x0085)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            mutation(Operation.CREATE, null, EXPIRY, String.valueOf((char) 0x200B)).auditReason())
        .isEqualTo(String.valueOf((char) 0x200B));
    assertThatThrownBy(() -> mutation(Operation.CREATE, null, EXPIRY, "\ud800"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mutation(Operation.CREATE, null, EXPIRY, "a".repeat(4097)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RealmGrantMutation mutation(
      Operation operation, Long expected, Instant expiry, String reason) {
    return new RealmGrantMutation("prod", GRANT, operation, expected, expiry, reason);
  }
}
