package unit.net.firedevops.firemud.common.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import org.junit.jupiter.api.Test;

/**
 * Controlled exact catalog bytes exercise the value/codec, not an installed schema or FRESH proof.
 */
class SocialRuntimeFamilyCatalogTest {
  private static final String CATALOG =
      """
      {"schema":"social-runtime-family-catalog/v1","owner":"social-groups-service",
       "catalogEpoch":"1","families":[
        {"familyId":"account_friend_links","relation":"account_friend_links","component":"",
         "role":"SOCIAL_RELATIONSHIP","freshDisposition":"ACCOUNT_PAIR_SCOPE_DRIFT","targetOwner":"NONE"},
        {"familyId":"account_friend_links.account_id","relation":"account_friend_links","component":"account_id",
         "role":"ACCOUNT_IDENTITY_CARRIER","freshDisposition":"ACCOUNT_IDENTITY_CARRIER_ONLY","targetOwner":"ACCOUNT_SERVICE"},
        {"familyId":"account_friend_links.friend_account_id","relation":"account_friend_links","component":"friend_account_id",
         "role":"ACCOUNT_IDENTITY_CARRIER","freshDisposition":"ACCOUNT_IDENTITY_CARRIER_ONLY","targetOwner":"ACCOUNT_SERVICE"}],
       "schemaFacts":[
        {"kind":"COLUMN","identity":"account_friend_links.account_id","definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
        {"kind":"COLUMN","identity":"account_friend_links.friend_account_id","definitionDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
        {"kind":"TABLE","identity":"account_friend_links","definitionDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}],
       "unimplementedContracts":["CHAT_RESTRICTION_AND_REVISION","DIRECTIONAL_BLOCK_LIFECYCLE",
        "ENTITY_ESCROW_REFERENCE_FAMILIES","GAMEPLAY_WHISPER_NAMESPACE_SCOPE",
        "SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS","SOCIAL_PLAYER_STANDING_OWNER",
        "SOCIAL_SAGA_ADOPTER_CLASSIFICATION"]}
      """;

  @Test
  void fixedRequestFramingAndStandardDigestVectorAreStable() {
    var request = new Request(UUID.fromString("11111111-1111-4111-8111-111111111111"), "test");
    assertThat(
            java.util.HexFormat.of()
                .formatHex(SocialRuntimeFamilyCatalogCodec.requestBytes(request)))
        .isEqualTo("53435231111111111111411181111111111111110000000474657374");
    assertThat(SocialRuntimeFamilyCatalog.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void exactTypedRoundtripPreservesOriginalBytesIdentityDigestsAndBlockingScope() {
    byte[] source = CATALOG.getBytes(StandardCharsets.UTF_8);
    var catalog = SocialRuntimeFamilyCatalog.fromStored(source);
    var request = new Request(UUID.randomUUID(), "workload-test");
    var evidence = new Evidence(request, UUID.randomUUID(), catalog);
    assertThat(catalog.catalogEpoch()).isEqualTo(1);
    assertThat(catalog.families()).hasSize(3);
    assertThat(catalog.unimplementedContracts())
        .containsExactly(
            SocialRuntimeFamilyCatalog.ContractGap.CHAT_RESTRICTION_AND_REVISION,
            SocialRuntimeFamilyCatalog.ContractGap.DIRECTIONAL_BLOCK_LIFECYCLE,
            SocialRuntimeFamilyCatalog.ContractGap.ENTITY_ESCROW_REFERENCE_FAMILIES,
            SocialRuntimeFamilyCatalog.ContractGap.GAMEPLAY_WHISPER_NAMESPACE_SCOPE,
            SocialRuntimeFamilyCatalog.ContractGap.SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS,
            SocialRuntimeFamilyCatalog.ContractGap.SOCIAL_PLAYER_STANDING_OWNER,
            SocialRuntimeFamilyCatalog.ContractGap.SOCIAL_SAGA_ADOPTER_CLASSIFICATION);
    assertThat(catalog.families().getFirst().freshDisposition())
        .isEqualTo(SocialRuntimeFamilyCatalog.FreshDisposition.ACCOUNT_PAIR_SCOPE_DRIFT);
    assertThat(catalog.digest()).isEqualTo(SocialRuntimeFamilyCatalog.sha256(source));
    var wire = SocialRuntimeFamilyCatalogCodec.toResponse(request, evidence);
    assertThat(SocialRuntimeFamilyCatalogCodec.fromResponse(request, wire)).isEqualTo(evidence);
    assertThat(
            SocialRuntimeFamilyCatalogCodec.fromRequest(
                SocialRuntimeFamilyCatalogCodec.toRequest(request)))
        .isEqualTo(request);
    assertThat(
            SocialRuntimeFamilyCatalogCodec.evidenceFromStored(
                    SocialRuntimeFamilyCatalogCodec.evidenceBytes(evidence))
                .digest())
        .isEqualTo(evidence.digest());
    source[0] = 0;
    byte[] returned = catalog.exactBytes();
    returned[0] = 0;
    assertThat(catalog.exactBytes()).isEqualTo(CATALOG.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> catalog.families().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void refusesUnknownDuplicateMissingUnsupportedOrInconsistentCatalogFacts() {
    for (String invalid :
        new String[] {
          CATALOG.replace("\"owner\":", "\"extra\":true,\"owner\":"),
          CATALOG.replace(
              "\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"1\",\"catalogEpoch\":\"1\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"0\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":1"),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"9223372036854775808\""),
          CATALOG.replace("social-runtime-family-catalog/v1", "social-runtime-family-catalog/v2"),
          CATALOG.replace("social-groups-service", "game-session-service"),
          CATALOG.replace("ACCOUNT_PAIR_SCOPE_DRIFT", "CATALOG_ONLY"),
          CATALOG.replace("SOCIAL_RELATIONSHIP", "UNKNOWN"),
          CATALOG.replace("account_friend_links.friend_account_id", "renamed_friend_account_id"),
          CATALOG.replace("ACCOUNT_SERVICE", "NONE"),
          CATALOG.replace(
              "\"kind\":\"TABLE\",\"identity\":\"account_friend_links\"",
              "\"kind\":\"TABLE\",\"identity\":\"unknown_table\""),
          CATALOG.replace(
              "\"unimplementedContracts\":[", "\"unimplementedContracts\":[\"UNKNOWN_GAP\",")
        }) {
      assertThatThrownBy(
              () -> SocialRuntimeFamilyCatalog.fromStored(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void familyAndFactBoundsAreIndependentOfRowAbsence() {
    String family =
        """
        {"familyId":"account_friend_links","relation":"account_friend_links","component":"",
         "role":"SOCIAL_RELATIONSHIP","freshDisposition":"ACCOUNT_PAIR_SCOPE_DRIFT","targetOwner":"NONE"}
        """;
    String fact =
        """
        {"kind":"TABLE","identity":"account_friend_links","definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        """;
    String prefix =
        "{\"schema\":\"social-runtime-family-catalog/v1\",\"owner\":\"social-groups-service\",\"catalogEpoch\":\"1\",";
    String gaps =
        "\"unimplementedContracts\":[\"CHAT_RESTRICTION_AND_REVISION\",\"DIRECTIONAL_BLOCK_LIFECYCLE\","
            + "\"ENTITY_ESCROW_REFERENCE_FAMILIES\",\"GAMEPLAY_WHISPER_NAMESPACE_SCOPE\","
            + "\"SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS\",\"SOCIAL_PLAYER_STANDING_OWNER\","
            + "\"SOCIAL_SAGA_ADOPTER_CLASSIFICATION\"]}";
    String manyFamilies =
        prefix
            + "\"families\":["
            + String.join(",", java.util.Collections.nCopies(513, family))
            + "],\"schemaFacts\":["
            + fact
            + "],"
            + gaps;
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalog.fromStored(
                    manyFamilies.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    String compactFact =
        "{\"kind\":\"TYPE\",\"identity\":\"x\",\"definitionDigest\":\"sha256:"
            + "a".repeat(64)
            + "\"}";
    String manyFacts =
        prefix
            + "\"families\":["
            + family
            + "],\"schemaFacts\":["
            + String.join(",", java.util.Collections.nCopies(16385, compactFact))
            + "],"
            + gaps;
    assertThatThrownBy(
            () -> SocialRuntimeFamilyCatalog.fromStored(manyFacts.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateUnknownOversizedAndChangedCorrelationWireValues() {
    var request = new Request(UUID.randomUUID(), "test");
    var wire = SocialRuntimeFamilyCatalogCodec.toRequest(request);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder()
                        .addCanonicalRequestBytes(wire.getCanonicalRequestBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    9, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromRequest(
                    wire.toBuilder()
                        .setCanonicalRequestBytes(0, ByteString.copyFrom(new byte[1025]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var evidence = new Evidence(request, UUID.randomUUID(), catalog());
    var response = SocialRuntimeFamilyCatalogCodec.toResponse(request, evidence);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(UUID.randomUUID(), "test"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(request.readRequestId(), "other"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .addCanonicalResponseBytes(response.getCanonicalResponseBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    9, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesTruncationTrailingDataDigestTamperingInvalidUtf8AndSourceBounds() {
    var evidence =
        new Evidence(new Request(UUID.randomUUID(), "test"), UUID.randomUUID(), catalog());
    byte[] bytes = SocialRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.evidenceFromStored(
                    Arrays.copyOf(bytes, bytes.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalogCodec.evidenceFromStored(
                    Arrays.copyOf(bytes, bytes.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    int digestOffset =
        4 + 4 + SocialRuntimeFamilyCatalogCodec.requestBytes(evidence.request()).length + 16 + 4;
    bytes[digestOffset + 7] = bytes[digestOffset + 7] == 'a' ? (byte) 'b' : (byte) 'a';
    assertThatThrownBy(() -> SocialRuntimeFamilyCatalogCodec.evidenceFromStored(bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SocialRuntimeFamilyCatalog.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SocialRuntimeFamilyCatalog.fromStored(
                    new byte[SocialRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0, 0), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(UUID.randomUUID(), "test/sa/social-groups-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SocialRuntimeFamilyCatalog catalog() {
    return SocialRuntimeFamilyCatalog.fromStored(CATALOG.getBytes(StandardCharsets.UTF_8));
  }
}
