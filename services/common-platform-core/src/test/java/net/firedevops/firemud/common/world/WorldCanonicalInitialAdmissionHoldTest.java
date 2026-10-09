package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialAdmissionHoldTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID OTHER_UUID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String OTHER_REQUEST_DIGEST = "b".repeat(64);
  private static final String NO_PRIOR_VECTOR =
      "{\"request\":{\"activeLifecycleEpoch\":\"7\",\"canonicalGameInstanceId\":\"44444444-4444-4444-8444-444444444444\",\"canonicalTenantId\":\"11111111-1111-4111-8111-111111111111\",\"canonicalVersionId\":\"55555555-5555-4555-8555-555555555555\",\"expectedCatalogRevision\":\"12\",\"expectedPriorPointerVersion\":null,\"initialAdmissionOrigin\":\"NO_PRIOR_POINTER\",\"initialAdmissionRequestDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"initialAdmissionRequestId\":\"gs-initial-admission-17\",\"playableStateNamespaceId\":\"33333333-3333-4333-8333-333333333333\",\"playableStateScope\":\"SHARED\",\"realmId\":\"22222222-2222-4222-8222-222222222222\",\"targetNamespace\":\"prod\",\"worldSlug\":\"green-hollow\"},\"schema\":\"world-canonical-initial-admission-hold-request/v1\"}";
  private static final String EXPECT_CLOSED_VECTOR =
      "{\"request\":{\"activeLifecycleEpoch\":\"7\",\"canonicalGameInstanceId\":\"44444444-4444-4444-8444-444444444444\",\"canonicalTenantId\":\"11111111-1111-4111-8111-111111111111\",\"canonicalVersionId\":\"55555555-5555-4555-8555-555555555555\",\"expectedCatalogRevision\":\"12\",\"expectedPriorPointerVersion\":\"13\",\"initialAdmissionOrigin\":\"EXPECT_CLOSED\",\"initialAdmissionRequestDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"initialAdmissionRequestId\":\"gs-initial-admission-17\",\"playableStateNamespaceId\":\"33333333-3333-4333-8333-333333333333\",\"playableStateScope\":\"SHARED\",\"realmId\":\"22222222-2222-4222-8222-222222222222\",\"targetNamespace\":\"prod\",\"worldSlug\":\"green-hollow\"},\"schema\":\"world-canonical-initial-admission-hold-request/v1\"}";

  @Test
  void emitsLiteralCanonicalVectorsForBothApprovedOrigins() {
    Request noPrior = noPriorRequest();
    Request expectClosed = expectClosedRequest(13L);

    assertThat(utf8(noPrior.canonicalRequestBytes())).isEqualTo(NO_PRIOR_VECTOR);
    assertThat(utf8(expectClosed.canonicalRequestBytes())).isEqualTo(EXPECT_CLOSED_VECTOR);
    assertThat(noPrior.holdBindingDigest())
        .isEqualTo("sha256:c6add7b8085af587261e3f41a01568e2a02f66b3f5ae3c4852dbc5124421f10a");
    assertThat(expectClosed.holdBindingDigest())
        .isEqualTo("sha256:6905884b086d38cb14fc9886b1b7e8b125358d9c33dc23f0300c3c387a022b72");
    assertThat(noPrior.initialAdmissionRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(noPrior.holdBindingDigest())
        .isNotEqualTo("sha256:" + noPrior.initialAdmissionRequestDigest());
  }

  @Test
  void rejectsUnpairedSurrogatesAtRequestConstruction() {
    assertThatThrownBy(() -> noPriorRequest("gs-initial-admission-" + (char) 0xd800))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> noPriorRequest("gs-initial-admission-" + (char) 0xdc00))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsSupplementaryCodePointAndRoundTripsCanonicalRequest() {
    String requestId = "gs-initial-admission-" + new String(Character.toChars(0x1f680));
    Request request = noPriorRequest(requestId);

    assertThat(Request.fromStored(request.canonicalRequestBytes())).isEqualTo(request);
  }

  @Test
  void requestAndAcquiredIdentityRoundTripAndExactRetryKeepTheirCanonicalIdentity() {
    Request noPrior = noPriorRequest();
    Request request = expectClosedRequest(13L);
    Request decodedNoPrior = Request.fromStored(noPrior.canonicalRequestBytes());
    Request decodedRequest = Request.fromStored(request.canonicalRequestBytes());
    HoldIdentity acquired = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    HoldIdentity decoded = HoldIdentity.fromStored(acquired.canonicalBytes());
    HoldIdentity exactRetry = new HoldIdentity(decodedRequest, HOLD_ID, HOLD_FENCE);

    assertThat(decodedNoPrior).isEqualTo(noPrior);
    assertThat(decodedRequest).isEqualTo(request);
    assertThat(decoded).isEqualTo(acquired);
    assertThat(decoded.canonicalBytes()).containsExactly(acquired.canonicalBytes());
    assertThat(exactRetry.canonicalBytes()).containsExactly(acquired.canonicalBytes());
    assertThat(decoded.holdId()).isEqualTo(HOLD_ID);
    assertThat(decoded.holdFence()).isEqualTo(HOLD_FENCE);
    assertThat(decoded.holdBindingDigest()).isEqualTo(request.holdBindingDigest());
  }

  @Test
  void everySupportedRequestFieldChangesTheCanonicalHoldBinding() {
    Request base = noPriorRequest();
    assertBindingDiffers(
        base,
        request(
            "staging",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            OTHER_UUID,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "blue-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            OTHER_UUID,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            OTHER_UUID,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            OTHER_UUID,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            OTHER_UUID,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            8L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-18",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            OTHER_REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null));
    assertBindingDiffers(base, expectClosedRequest(13L));
    assertBindingDiffers(expectClosedRequest(13L), expectClosedRequest(14L));
    assertBindingDiffers(
        base,
        request(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            14L,
            null));
  }

  @Test
  void rejectsNilNoncanonicalAndInvalidScopeOrOriginInputs() {
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    NIL_UUID,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "gs-initial-admission-17",
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "gs-initial-admission-17",
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    null,
                    "PRIVATE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHARED");
    assertThatThrownBy(
            () -> Request.fromStored(utf8Bytes(NO_PRIOR_VECTOR.replace("SHARED", "ISOLATED"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHARED");
    assertThatThrownBy(
            () ->
                Request.fromStored(
                    utf8Bytes(NO_PRIOR_VECTOR.replace("NO_PRIOR_POINTER", "FIRST_OPEN"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialAdmissionOrigin");
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "gs-initial-admission-17",
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NO_PRIOR_POINTER");
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "gs-initial-admission-17",
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.EXPECT_CLOSED,
                    12L,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires");
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "gs-initial-admission-17",
                    "A".repeat(64),
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase");
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    " ",
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonempty");
    assertThatThrownBy(
            () ->
                request(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    GAME_INSTANCE,
                    VERSION,
                    7L,
                    "r".repeat(129),
                    REQUEST_DIGEST,
                    InitialAdmissionOrigin.NO_PRIOR_POINTER,
                    12L,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounded");
    assertThatThrownBy(() -> new HoldIdentity(noPriorRequest(), NIL_UUID, HOLD_FENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("holdId");
    assertThatThrownBy(() -> new HoldIdentity(noPriorRequest(), HOLD_ID, NIL_UUID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("holdFence");
  }

  @Test
  void parserRejectsInvalidUuidsOptionalRepresentationsCountersAliasesAndUnknownInput() {
    assertInvalidRequest(NO_PRIOR_VECTOR.replace(TENANT.toString(), NIL_UUID.toString()));
    UUID alphaTenant = uuid("abcdefab-cdef-4abc-8def-abcdefabcdef");
    String alphaTenantVector = utf8(noPriorRequestWithTenant(alphaTenant).canonicalRequestBytes());
    assertInvalidRequest(
        alphaTenantVector.replace(alphaTenant.toString(), alphaTenant.toString().toUpperCase()));
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace(
            "\"expectedPriorPointerVersion\":null", "\"expectedPriorPointerVersion\":\"1\""));
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace(
            "\"initialAdmissionOrigin\":\"NO_PRIOR_POINTER\"",
            "\"initialAdmissionOrigin\":\"EXPECT_CLOSED\""));
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace(
            "\"expectedPriorPointerVersion\":null", "\"expectedPriorPointerVersion\":1"));
    assertInvalidRequest(NO_PRIOR_VECTOR.replace(",\"expectedPriorPointerVersion\":null", ""));
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace("\"canonicalGameInstanceId\"", "\"gameInstanceId\""));
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace(
            "\"worldSlug\":", "\"worldSlug\":\"green-hollow\",\"worldSlugAlias\":"));
    assertInvalidRequest(NO_PRIOR_VECTOR + "{}");
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace(",\"worldSlug\":", ",\"targetNamespace\":\"prod\",\"worldSlug\":"));

    for (String value : new String[] {"0", "-1", "01", "9223372036854775808"}) {
      assertInvalidRequest(
          NO_PRIOR_VECTOR.replace(
              "\"activeLifecycleEpoch\":\"7\"", "\"activeLifecycleEpoch\":\"" + value + "\""));
      assertInvalidRequest(
          NO_PRIOR_VECTOR.replace(
              "\"expectedCatalogRevision\":\"12\"",
              "\"expectedCatalogRevision\":\"" + value + "\""));
      assertInvalidRequest(
          EXPECT_CLOSED_VECTOR.replace(
              "\"expectedPriorPointerVersion\":\"13\"",
              "\"expectedPriorPointerVersion\":\"" + value + "\""));
    }
    assertInvalidRequest(
        NO_PRIOR_VECTOR.replace("\"activeLifecycleEpoch\":\"7\"", "\"activeLifecycleEpoch\":7"));

    String identity =
        utf8(new HoldIdentity(noPriorRequest(), HOLD_ID, HOLD_FENCE).canonicalBytes());
    assertThatThrownBy(
            () ->
                HoldIdentity.fromStored(
                    utf8Bytes(
                        identity.replace(
                            "\"holdBindingDigest\":\"" + noPriorRequest().holdBindingDigest(),
                            "\"holdBindingDigest\":\"sha256:" + "0".repeat(64)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                HoldIdentity.fromStored(
                    utf8Bytes(
                        identity.replace(
                            "\"holdId\":",
                            "\"legacyHoldId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"holdId\":"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("closed object shape");
  }

  private static Request noPriorRequest() {
    return noPriorRequest("gs-initial-admission-17");
  }

  private static Request noPriorRequest(String requestId) {
    return request(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        GAME_INSTANCE,
        VERSION,
        7L,
        requestId,
        REQUEST_DIGEST,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static Request expectClosedRequest(long expectedPriorPointerVersion) {
    return request(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        GAME_INSTANCE,
        VERSION,
        7L,
        "gs-initial-admission-17",
        REQUEST_DIGEST,
        InitialAdmissionOrigin.EXPECT_CLOSED,
        12L,
        expectedPriorPointerVersion);
  }

  private static Request noPriorRequestWithTenant(UUID tenant) {
    return request(
        "prod",
        tenant,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        GAME_INSTANCE,
        VERSION,
        7L,
        "gs-initial-admission-17",
        REQUEST_DIGEST,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static Request request(
      String namespace,
      UUID tenant,
      String slug,
      UUID realm,
      UUID playableNamespace,
      UUID gameInstance,
      UUID version,
      long epoch,
      String requestId,
      String requestDigest,
      InitialAdmissionOrigin origin,
      long catalogRevision,
      Long priorPointerVersion) {
    return request(
        namespace,
        tenant,
        slug,
        realm,
        playableNamespace,
        gameInstance,
        version,
        epoch,
        requestId,
        requestDigest,
        origin,
        catalogRevision,
        priorPointerVersion,
        "SHARED");
  }

  private static Request request(
      String namespace,
      UUID tenant,
      String slug,
      UUID realm,
      UUID playableNamespace,
      UUID gameInstance,
      UUID version,
      long epoch,
      String requestId,
      String requestDigest,
      InitialAdmissionOrigin origin,
      long catalogRevision,
      Long priorPointerVersion,
      String scope) {
    return new Request(
        namespace,
        tenant,
        slug,
        realm,
        playableNamespace,
        scope,
        gameInstance,
        version,
        epoch,
        requestId,
        requestDigest,
        origin,
        catalogRevision,
        priorPointerVersion);
  }

  private static void assertBindingDiffers(Request original, Request changed) {
    assertThat(changed.canonicalRequestBytes()).isNotEqualTo(original.canonicalRequestBytes());
    assertThat(changed.holdBindingDigest()).isNotEqualTo(original.holdBindingDigest());
  }

  private static void assertInvalidRequest(String json) {
    assertThatThrownBy(() -> Request.fromStored(utf8Bytes(json)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
