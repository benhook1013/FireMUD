package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import org.junit.jupiter.api.Test;

class CreateCanonicalRealmCatalogRequestSnapshotBoundsTest {
  @Test
  void rejectsRealmSlugsThatAreNotCanonicalSelectors() {
    assertThatThrownBy(() -> request("Production", "Production", "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical lower-case slug");
    assertThatThrownBy(() -> request("production_realm", "Production", "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical lower-case slug");
  }

  @Test
  void rejectsTextOutsideCurrentOpenSnapshotBounds() {
    assertThatThrownBy(() -> request("production", " Production", "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realmDisplayName");
    assertThatThrownBy(() -> request("production", "Production ", "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realmDisplayName");
    assertThatThrownBy(() -> request("production", "   ", "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realmDisplayName");
    assertThatThrownBy(() -> request("production", "Production", " ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characterCreationPolicy");
    assertThatThrownBy(() -> request("production", "Production", "ALLOW_NEW "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characterCreationPolicy");
    assertThatThrownBy(() -> request("production", "Production", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characterCreationPolicy");
    assertThatThrownBy(() -> request("production", "Production", "P".repeat(33)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 32 characters");
  }

  @Test
  void acceptsCanonicalMaximumValuesThatTheOpenSnapshotCanRead() {
    String maxDisplayName = "\uD83D\uDE00".repeat(100);
    CreateCanonicalRealmCatalogRequest request =
        request("a".repeat(64), maxDisplayName, "P".repeat(32));

    assertThat(maxDisplayName.codePointCount(0, maxDisplayName.length())).isEqualTo(100);
    assertThat(maxDisplayName.length()).isEqualTo(200);
    assertThat(maxDisplayName.getBytes(StandardCharsets.UTF_8)).hasSize(400);
    assertThat(CanonicalCurrentOpenPointerSnapshotDigest.digest(projection(request))).hasSize(64);

    String overCodePointLimit = "界".repeat(101);
    assertThatThrownBy(() -> request("a".repeat(64), overCodePointLimit, "P".repeat(32)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realmDisplayName");
  }

  @Test
  void rejectsMalformedUtf8Text() {
    String malformed = "bad\uD800";

    assertThatThrownBy(() -> request("production", malformed, "ALLOW_NEW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
    assertThatThrownBy(() -> request("production", "Production", malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
  }

  private static CreateCanonicalRealmCatalogRequest request(
      String realmSlug, String realmDisplayName, String characterCreationPolicy) {
    return new CreateCanonicalRealmCatalogRequest(
        UUID.randomUUID(),
        "gameplay",
        UUID.randomUUID(),
        UUID.randomUUID(),
        realmSlug,
        realmDisplayName,
        true,
        true,
        "SHARED",
        characterCreationPolicy,
        null,
        null);
  }

  private static CanonicalCurrentOpenPointerSnapshotDigest.Projection projection(
      CreateCanonicalRealmCatalogRequest request) {
    return new CanonicalCurrentOpenPointerSnapshotDigest.Projection(
        3,
        request.targetNamespace(),
        request.canonicalTenantId(),
        "world",
        "World",
        UUID.randomUUID(),
        request.realmSlug(),
        request.realmDisplayName(),
        UUID.randomUUID(),
        request.stateScope(),
        1L,
        1L,
        "OPEN",
        request.visible(),
        request.publicProduction(),
        null,
        request.characterCreationPolicy(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "initial-request",
        "a".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        1L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "b".repeat(64),
        "game-session-canonical-initial-admission",
        "World-held initial admission",
        null,
        "sha256:" + "c".repeat(64),
        1L,
        Instant.parse("2026-10-07T00:00:00Z"));
  }
}
