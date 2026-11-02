package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.cluster.SlotHash;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import org.junit.jupiter.api.Test;

/** Data and independent Redis slot calculation only; no creation authority is produced. */
class AccountTenantCreationAdmissionBindingTest {
  static final long NOW = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
  static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
  static final UUID ISSUANCE = UUID.fromString("40000000-0000-4000-8000-000000000004");
  static final UUID OPERATION = UUID.fromString("50000000-0000-4000-8000-000000000005");
  static final String HASH = "a".repeat(64);

  @Test
  void companionHashesCompleteUnchangedTokenKeyAcrossIndependentVectors() {
    for (int index = 0; index < 32; index++) {
      String hash = String.format("%064x", index);
      String token = AccountTenantCreationAdmissionRedisKeys.token(hash);
      String receipt = AccountTenantCreationAdmissionRedisKeys.receipt(hash, OPERATION);
      assertThat(token).isEqualTo("session:auth:token:" + hash).doesNotContain("{");
      assertThat(receipt).isEqualTo("session:auth:creation-admission:{" + token + "}:" + OPERATION);
      assertThat(SlotHash.getSlot(receipt)).isEqualTo(SlotHash.getSlot(token));
      assertThat(
              SlotHash.getSlot(
                  AccountTenantCreationAdmissionRedisKeys.receipt(hash, UUID.randomUUID())))
          .isEqualTo(SlotHash.getSlot(token));
    }
    for (String malformed :
        new String[] {null, "A".repeat(64), "a".repeat(63), HASH + "}", "{x}"}) {
      assertThatThrownBy(() -> AccountTenantCreationAdmissionRedisKeys.token(malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> AccountTenantCreationAdmissionRedisKeys.receipt(HASH, new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalDataIsDefensiveAndContainsDistinctFrozenDeadlines() {
    var binding = binding();
    byte[] receipt = binding.canonicalReceipt();
    byte original = receipt[0];
    receipt[0] = 0;
    assertThat(binding.canonicalReceipt()[0]).isEqualTo(original);
    assertThat(new String(binding.canonicalReceipt(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"schema\":\"account-tenant-creation-admission-v1\"")
        .contains("\"targetNamespace\":\"test-game\"");
    assertThat(binding.operationDeadlineEpochMillis())
        .isLessThan(binding.tokenExpiresAtEpochMillis());
    assertThat(binding.receiptCleanupDeadlineEpochMillis() - binding.operationDeadlineEpochMillis())
        .isEqualTo(86_400_000L);
  }

  @Test
  void boundedPolicyRejectsExtendedLogicalWindowRetentionAndUnsafeIntegers() {
    var binding = binding();
    assertThatThrownBy(() -> deadlines(binding, NOW + 300_001, NOW + 300_001 + 86_400_000L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deadlines(binding, NOW, NOW + 86_400_000L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                deadlines(
                    binding,
                    binding.operationDeadlineEpochMillis(),
                    binding.receiptCleanupDeadlineEpochMillis() + 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deadlines(binding, Long.MAX_VALUE, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            deadlines(binding, NOW + 300_000, NOW + 300_000 + 86_400_000L)
                .operationDeadlineEpochMillis())
        .isEqualTo(NOW + 300_000);
  }

  static AccountTenantCreationAdmissionBinding binding() {
    return new AccountTenantCreationAdmissionBinding(
        UUID.fromString("60000000-0000-4000-8000-000000000006"),
        OPERATION,
        ISSUANCE,
        ACCOUNT,
        "test-game",
        UUID.fromString("70000000-0000-4000-8000-000000000007"),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64),
        AccountControlUiCoordination.digest("SHA-256", active()),
        UUID.fromString("80000000-0000-4000-8000-000000000008"),
        HASH,
        NOW + 360_000,
        NOW + 300_000,
        NOW,
        NOW + 240_000,
        NOW + 240_000 + 86_400_000L);
  }

  private static AccountTenantCreationAdmissionBinding deadlines(
      AccountTenantCreationAdmissionBinding original, long deadline, long cleanup) {
    return new AccountTenantCreationAdmissionBinding(
        original.intentRequestId(),
        original.creationOperationId(),
        original.issuanceOperationId(),
        original.actorAccountId(),
        original.targetNamespace(),
        original.gameDesignCreationRequestId(),
        original.gameDesignRequestDigest(),
        original.intentDigest(),
        original.activeRegistryDigest(),
        original.reauthenticationEvidenceId(),
        original.tokenHash(),
        original.tokenCleanupDeadlineEpochMillis(),
        original.tokenExpiresAtEpochMillis(),
        original.preparedAtEpochMillis(),
        deadline,
        cleanup);
  }

  static byte[] active() {
    return AccountControlUiAuthority.canonical(
        Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry("registryVersion", 2),
            Map.entry("tokenHash", HASH),
            Map.entry("kid", "key-1"),
            Map.entry("signerGeneration", "1"),
            Map.entry("issuer", "firemud-account-service"),
            Map.entry("profile", "control-ui"),
            Map.entry("type", "control-ui"),
            Map.entry("audience", "control-ui"),
            Map.entry("accountId", ACCOUNT.toString()),
            Map.entry("jti", "30000000-0000-4000-8000-000000000003"),
            Map.entry("iat", NOW / 1000),
            Map.entry("nbf", NOW / 1000),
            Map.entry("exp", (NOW + 300_000) / 1000),
            Map.entry("tokenGeneration", 1),
            Map.entry("authorityTuple", Map.of("accountAuthorityGeneration", 1)),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", 1),
            Map.entry("operationId", ISSUANCE.toString()),
            Map.entry("requestId", "20000000-0000-4000-8000-000000000002"),
            Map.entry("requestDigest", "d".repeat(64)),
            Map.entry("registryCleanupDeadlineEpochMillis", NOW + 360_000),
            Map.entry("state", "active"),
            Map.entry("authoritySourceVersions", Map.of()),
            Map.entry("authEvidenceBundle", Map.of("bundleVersion", "1")),
            Map.entry("originalSignerReceiptDigest", "e".repeat(64))));
  }
}
