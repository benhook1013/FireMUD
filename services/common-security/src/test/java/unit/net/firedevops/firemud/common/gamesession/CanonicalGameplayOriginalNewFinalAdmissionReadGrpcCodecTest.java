package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.FinalResult;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionResponse;
import org.junit.jupiter.api.Test;

class CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodecTest {
  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);

  @Test
  void requestAndActualFinalSessionMetadataRoundTripWithoutCredentialBytes() {
    Selector selector = selector();
    ReadOriginalNewFinalAdmissionRequest request =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(selector);

    assertThat(CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(request))
        .isEqualTo(selector);

    FinalResult result = new FinalResult(selector, 1_000L, 61_000L, 60_000L, OTHER_HASH);
    ReadOriginalNewFinalAdmissionResponse response =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toResponse(selector, result);
    FinalResult parsed =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.fromResponse(selector, response);

    assertThat(parsed).isEqualTo(result);
    String payload = response.getActualFinalAdmissionJson(0).toStringUtf8();
    assertThat(payload)
        .contains("rebindHandleSha256")
        .doesNotContain("rebindHandle\"")
        .doesNotContain("compactJwt")
        .doesNotContain("tokenJti")
        .doesNotContain("lineageId");
  }

  @Test
  void requestRequiresOneVersionAndOneCanonicalSelectorPayload() {
    var request = CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(selector());

    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder().clearSchemaVersion().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .addExpectedOriginalAdmissionJson(
                            request.getExpectedOriginalAdmissionJson(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestRejectsDuplicateUnknownAndNonCanonicalJson() {
    var request = CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(selector());
    String canonical = request.getExpectedOriginalAdmissionJson(0).toStringUtf8();
    String duplicate =
        canonical.substring(0, canonical.length() - 1)
            + ",\"requestId\":\""
            + UUID.randomUUID()
            + "\"}";

    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .setExpectedOriginalAdmissionJson(
                            0, ByteString.copyFrom(duplicate, StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .setExpectedOriginalAdmissionJson(
                            0, ByteString.copyFrom(canonical + " ", StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    String unknown = canonical.substring(0, canonical.length() - 1) + ",\"unexpected\":\"x\"}";
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .setExpectedOriginalAdmissionJson(
                            0, ByteString.copyFrom(unknown, StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseRequiresExactActualAnchorsAndValidContinuityArithmetic() {
    Selector selector = selector();
    var response =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toResponse(
            selector, new FinalResult(selector, 1_000L, 61_000L, 60_000L, OTHER_HASH));
    String original = response.getActualFinalAdmissionJson(0).toStringUtf8();
    String changedHash = original.replace(HASH, OTHER_HASH);

    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.fromResponse(
                    selector,
                    response.toBuilder()
                        .setActualFinalAdmissionJson(
                            0, ByteString.copyFrom(changedHash, StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FinalResult(selector, 1_000L, 60_999L, 60_000L, OTHER_HASH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void controlledResponseRejectsRebindHandleAndSessionHashMismatches() {
    Selector selector = selector();
    var response =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toResponse(
            selector, new FinalResult(selector, 1_000L, 61_000L, 60_000L, OTHER_HASH));
    String original = response.getActualFinalAdmissionJson(0).toStringUtf8();

    String changedHandle =
        original.replace(
            "\"rebindHandleSha256\":\"" + HASH + "\"",
            "\"rebindHandleSha256\":\"" + OTHER_HASH + "\"");
    assertThat(changedHandle).isNotEqualTo(original);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.fromResponse(
                    selector,
                    response.toBuilder()
                        .setActualFinalAdmissionJson(
                            0, ByteString.copyFrom(changedHandle, StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    String changedTokenHash =
        original.replace(
            "\"tokenSha256\":\"" + HASH + "\"", "\"tokenSha256\":\"" + OTHER_HASH + "\"");
    assertThat(changedTokenHash).isNotEqualTo(original);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.fromResponse(
                    selector,
                    response.toBuilder()
                        .setActualFinalAdmissionJson(
                            0, ByteString.copyFrom(changedTokenHash, StandardCharsets.UTF_8))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Selector selector() {
    return new Selector(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        8L,
        HASH,
        HASH,
        1L,
        10L,
        HASH,
        HASH,
        HASH,
        HASH);
  }
}
