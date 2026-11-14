package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.junit.jupiter.api.Test;

class AuthoredWorldVersionStateEvidenceTest {
  private static final UUID CANONICAL_VERSION_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID READ_REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REGISTRATION_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void evidenceDigestMatchesFixedLengthFramedVector() {
    var request = independentVectorRequest();
    var evidence =
        AuthoredWorldVersionStateEvidence.create(
            request,
            independentVectorSource(),
            CANONICAL_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            9_223_372_036_854_775_804L);

    assertThat(evidence.evidenceDigest())
        .isEqualTo("sha256:4b3ca54e03589b89bbe98ac1979ec573ea749eeb4d03484e2678cf8e0cd90710");
    assertThat(evidence.sourceEvidence().requestDigest())
        .isEqualTo("sha256:c8853aa8d2fbdf1bc7905c80d931bdedf32b16c89e49a2c08e821a039b175e23");
    assertThat(evidence.sourceEvidence().evidenceDigest())
        .isEqualTo("sha256:75961752d010261b637ece6ce33eeb714ea032986836bf118397473cc8a2895b");
  }

  @Test
  void changedRequestStateAndEpochChangeTheOuterDigest() {
    var original = request("cafe-coast", 19L);
    String originalDigest =
        AuthoredWorldVersionStateEvidence.evidenceDigest(
            original,
            CANONICAL_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            7L);

    assertThat(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                request("cafe-cliff", 19L),
                CANONICAL_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                7L))
        .isNotEqualTo(originalDigest);
    assertThat(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                request("cafe-coast", 20L),
                CANONICAL_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                7L))
        .isNotEqualTo(originalDigest);
    assertThat(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                original,
                CANONICAL_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                7L))
        .isNotEqualTo(originalDigest);
    assertThat(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                original,
                CANONICAL_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                8L))
        .isNotEqualTo(originalDigest);
  }

  @Test
  void completeUnicodeSourceReceiptUsesItsIndependentCanonicalDigest() {
    AuthoredWorldSourceEvidence source = sourceEvidence("Café 🐉");

    assertThat(source.worldDisplayName()).isEqualTo("Café 🐉");
    assertThat(source.requestDigest())
        .isEqualTo("sha256:c40306b291f9cb7dfbdb0c78520bd71cb795883a4e1a66feb8588222566542fd");
    assertThat(source.evidenceDigest())
        .isEqualTo("sha256:c1e76186517dbcefaa2ac5b3f4903b324cab7f6c8fdc14b29b81f3d664cf3a82");
  }

  @Test
  void rejectsInvalidCountersStateAndSourceEchoes() {
    assertThatThrownBy(() -> request("cafe-coast", 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("versionId");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateEvidence.evidenceDigest(
                    request("cafe-coast", 19L),
                    CANONICAL_VERSION_ID,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
                    7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lifecycle state");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateEvidence.create(
                    request("cafe-coast", 19L),
                    sourceEvidence("Café 🐉"),
                    CANONICAL_VERSION_ID,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("versionStateEpoch");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateEvidence.create(
                    request("other-world", 19L),
                    sourceEvidence("Café 🐉"),
                    CANONICAL_VERSION_ID,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source receipt");
    AuthoredWorldSourceEvidence source = sourceEvidence("Café 🐉");
    AuthoredWorldVersionStateEvidence.Request reusedRegistrationIdentity =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            "test",
            REGISTRATION_REQUEST_ID,
            TENANT_ID,
            "cafe-coast",
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            19L);
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateEvidence.create(
                    reusedRegistrationIdentity,
                    source,
                    CANONICAL_VERSION_ID,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original source registration request");
  }

  @Test
  void canonicalVersionIdentityIsRequiredAndDigestBound() {
    var request = independentVectorRequest();
    var state = VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT;
    assertThatThrownBy(
            () -> AuthoredWorldVersionStateEvidence.evidenceDigest(request, null, state, 7L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateEvidence.evidenceDigest(
                    request, new UUID(0, 0), state, 7L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                request, CANONICAL_VERSION_ID, state, 7L))
        .isNotEqualTo(
            AuthoredWorldVersionStateEvidence.evidenceDigest(
                request, uuid("66666666-6666-4666-8666-666666666666"), state, 7L));
  }

  private static AuthoredWorldVersionStateEvidence.Request request(
      String worldSlug, long versionId) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        "test",
        READ_REQUEST_ID,
        TENANT_ID,
        worldSlug,
        SOURCE_OPERATION_ID,
        SOURCE_DIGEST,
        versionId);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence(String displayName) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "test", REGISTRATION_REQUEST_ID, TENANT_ID, "tenant-one", "cafe-coast", displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        "test",
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "tenant-one",
        "cafe-coast",
        displayName,
        42L,
        "game-owner-tenant",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            "test",
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "tenant-one",
            "cafe-coast",
            displayName,
            42L,
            "game-owner-tenant",
            "NEW_GAME_ROW"));
  }

  private static AuthoredWorldVersionStateEvidence.Request independentVectorRequest() {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        "firemud",
        uuid("33333333-3333-4333-8333-333333333333"),
        uuid("11111111-1111-4111-8111-111111111111"),
        "violet-wilds",
        uuid("22222222-2222-4222-8222-222222222222"),
        "sha256:75961752d010261b637ece6ce33eeb714ea032986836bf118397473cc8a2895b",
        9_223_372_036_854_775_805L);
  }

  private static AuthoredWorldSourceEvidence independentVectorSource() {
    UUID canonicalTenantId = uuid("11111111-1111-4111-8111-111111111111");
    UUID registrationRequestId = uuid("44444444-4444-4444-8444-444444444444");
    UUID sourceOperationId = uuid("22222222-2222-4222-8222-222222222222");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "firemud",
            registrationRequestId,
            canonicalTenantId,
            "corpus-demo",
            "violet-wilds",
            "Café 🐉");
    return new AuthoredWorldSourceEvidence(
        1,
        "firemud",
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        "corpus-demo",
        "violet-wilds",
        "Café 🐉",
        9_223_372_036_854_775_806L,
        "source-owner-key",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            "firemud",
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "corpus-demo",
            "violet-wilds",
            "Café 🐉",
            9_223_372_036_854_775_806L,
            "source-owner-key",
            "NEW_GAME_ROW"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
