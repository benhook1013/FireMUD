package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.junit.jupiter.api.Test;

class GameSessionCanonicalRealmCatalogRepositoryTest {
  private static final String NAMESPACE = "canonical-realm-catalog-test";
  private static final UUID TENANT_ID = uuid(1);
  private static final UUID INTAKE_OPERATION_ID = uuid(2);
  private static final UUID INTAKE_REQUEST_ID = uuid(3);
  private static final UUID CREATE_REQUEST_ID = uuid(4);

  @Test
  void explicitPublicRequestPreservesRealmSelectorAndOpaqueOwnerPolicyExactly() {
    CreateCanonicalRealmCatalogRequest request =
        request("production-realm-west", "Café 🐉", "realm-local-v7");

    assertThat(request.realmSlug()).isEqualTo("production-realm-west");
    assertThat(request.characterCreationPolicy()).isEqualTo("realm-local-v7");
    assertThat(request.visible()).isTrue();
    assertThat(request.publicProduction()).isTrue();
    assertThat(request.stateScope()).isEqualTo("SHARED");
  }

  @Test
  void rejectsMissingPublicStatePrivateLifecycleAndMalformedUtf8Inputs() {
    assertThatThrownBy(() -> request("realm", "Display", "policy", false, true, "SHARED"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("visible public production");
    assertThatThrownBy(() -> request("realm", "Display", "policy", true, false, "SHARED"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("visible public production");
    assertThatThrownBy(() -> request("realm", "Display", "policy", true, true, "PRIVATE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stateScope");
    assertThatThrownBy(
            () ->
                new CreateCanonicalRealmCatalogRequest(
                    CREATE_REQUEST_ID,
                    NAMESPACE,
                    TENANT_ID,
                    INTAKE_OPERATION_ID,
                    "realm",
                    "Display",
                    true,
                    true,
                    "SHARED",
                    "policy",
                    uuid(5),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("private/playtest");
    String malformedUtf8 = "bad" + (char) 0xD800;
    assertThatThrownBy(() -> request(malformedUtf8, "Display", "policy"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
    assertThatThrownBy(() -> request("realm", malformedUtf8, "policy"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
    assertThatThrownBy(() -> request("realm", "Display", malformedUtf8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
  }

  @Test
  void requestDigestIsStableAndBindsEachCreationInputAndExactSourceReceipt() {
    IntakeReceipt source = sourceReceipt();
    CreateCanonicalRealmCatalogRequest original = request("arcology", "The City", "owner-mode");
    String digest = GameSessionCanonicalRealmCatalogRepository.requestDigest(original, source);

    assertThat(GameSessionCanonicalRealmCatalogRepository.requestDigest(original, source))
        .isEqualTo(digest);
    assertThat(
            GameSessionCanonicalRealmCatalogRepository.requestDigest(
                request("another-arcology", "The City", "owner-mode"), source))
        .isNotEqualTo(digest);
    assertThat(
            GameSessionCanonicalRealmCatalogRepository.requestDigest(
                request("arcology", "Another Display", "owner-mode"), source))
        .isNotEqualTo(digest);
    assertThat(
            GameSessionCanonicalRealmCatalogRepository.requestDigest(
                request("arcology", "The City", "owner-mode", true, true, "ISOLATED"), source))
        .isNotEqualTo(digest);
    assertThat(
            GameSessionCanonicalRealmCatalogRepository.requestDigest(
                request("arcology", "The City", "another-owner-mode"), source))
        .isNotEqualTo(digest);
    assertThat(
            GameSessionCanonicalRealmCatalogRepository.requestDigest(
                request("arcology", "The City", "owner-mode", uuid(6)), source))
        .isNotEqualTo(digest);
  }

  @Test
  void snapshotRejectsTenantAndWorldSelectorsThatDifferFromPersistedSource() {
    IntakeReceipt source = sourceReceipt();
    assertThatThrownBy(
            () ->
                new CanonicalRealmCatalogSnapshot(
                    NAMESPACE,
                    TENANT_ID,
                    "different-tenant",
                    "authored-world",
                    uuid(7),
                    "realm",
                    "Realm",
                    true,
                    true,
                    "SHARED",
                    uuid(8),
                    "policy",
                    1,
                    CREATE_REQUEST_ID,
                    "sha256:" + "a".repeat(64),
                    "sha256:" + "b".repeat(64),
                    source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("do not match");
  }

  private static CreateCanonicalRealmCatalogRequest request(
      String realmSlug, String display, String policy) {
    return request(realmSlug, display, policy, true, true, "SHARED");
  }

  private static CreateCanonicalRealmCatalogRequest request(
      String realmSlug,
      String display,
      String policy,
      boolean visible,
      boolean production,
      String scope) {
    return new CreateCanonicalRealmCatalogRequest(
        CREATE_REQUEST_ID,
        NAMESPACE,
        TENANT_ID,
        INTAKE_OPERATION_ID,
        realmSlug,
        display,
        visible,
        production,
        scope,
        policy,
        null,
        null);
  }

  private static CreateCanonicalRealmCatalogRequest request(
      String realmSlug, String display, String policy, UUID requestId) {
    return new CreateCanonicalRealmCatalogRequest(
        requestId,
        NAMESPACE,
        TENANT_ID,
        INTAKE_OPERATION_ID,
        realmSlug,
        display,
        true,
        true,
        "SHARED",
        policy,
        null,
        null);
  }

  private static IntakeReceipt sourceReceipt() {
    String tenantSlug = "stable-tenant";
    String worldSlug = "authored-world";
    String worldDisplayName = "Authored World";
    String sourceTenantKey = "game-design-tenant-42";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, uuid(10), TENANT_ID, tenantSlug, worldSlug, worldDisplayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            uuid(10),
            uuid(11),
            sourceRequestDigest,
            TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            uuid(10),
            uuid(11),
            sourceRequestDigest,
            TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    String intakeRequestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(INTAKE_REQUEST_ID, source);
    String intakeReceiptDigest =
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            INTAKE_OPERATION_ID, intakeRequestDigest, source.evidenceDigest());
    return new IntakeReceipt(
        INTAKE_OPERATION_ID, INTAKE_REQUEST_ID, intakeRequestDigest, source, intakeReceiptDigest);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
