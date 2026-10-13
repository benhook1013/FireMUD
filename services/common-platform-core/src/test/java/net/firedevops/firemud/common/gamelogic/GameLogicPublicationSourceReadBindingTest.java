package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.Test;

class GameLogicPublicationSourceReadBindingTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID ACTOR = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID FENCE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID INTAKE_REQUEST = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID COMMIT = uuid("77777777-7777-4777-8777-777777777777");
  private static final String EXPECTED_PUBLICATION_PREIMAGE_HEX =
      "32373a7075626c69636174696f6e446967657374526571756573742f7631383a74656e616e74496433363a"
          + "31313131313131312d313131312d343131312d383131312d313131313131313131313131393a73636f7065"
          + "4b696e6431323a46554c4c5f56455253494f4e393a76657273696f6e4964323a343131333a626173655665"
          + "7273696f6e4964303a31383a736372697074506174636856657273696f6e303a31363a7075626c69736852"
          + "657175657374496432313a7075626c69636174696f6e2d726571756573742d3132333a6465726976656457"
          + "6f726b666c6f774964656e7469747938323a7075626c6973683a31313131313131312d313131312d343131"
          + "312d383131312d3131313131313131313131313a7075626c6973682d726571756573743a7075626c696361"
          + "74696f6e2d726571756573742d31";
  private static final String EXPECTED_PREIMAGE_SHA256 =
      "sha256:01107d5d37c8c9f721efc6cc0e530fbaeda1526d3889b2f00875249723d24c92";
  private static final String EXPECTED_AUTHORIZATION_SHA256 =
      "sha256:808aaeb1127a83093afa0c025b5701fd3826f7e46f80512ab89aa89fc736db47";

  @Test
  void freezesExactThreeFramePreimageAndRoundTripsTheRetainedAuthorization() throws Exception {
    var publicationRequest = publicationRequest(TENANT.toString(), "41");
    var authorization = authorization(OPERATION, FENCE, COMMIT, TENANT, 41);
    var binding = new GameLogicPublicationSourceReadBinding(publicationRequest, authorization);
    var fromAuthorizationBytes =
        new GameLogicPublicationSourceReadBinding(
            publicationRequest, authorization.canonicalBytes());
    byte[] publicationBytes = publicationRequest.canonicalPreimage();
    byte[] authorizationBytes = authorization.canonicalBytes();

    assertThat(publicationBytes).hasSize(315);
    assertThat(HexFormat.of().formatHex(publicationBytes))
        .isEqualTo(EXPECTED_PUBLICATION_PREIMAGE_HEX);
    assertThat(authorizationBytes).hasSize(1946);
    assertThat(authorization.digest()).isEqualTo(EXPECTED_AUTHORIZATION_SHA256);
    byte[] authorizationPrefix =
        HexFormat.of()
            .parseHex(
                "0000002a6163636f756e742d67616d652d6c6f6769632d696e74616b652d617574686f72697a6174696f6e2f7631");
    byte[] authorizationSuffix =
        HexFormat.of()
            .parseHex(
                "0000000750524553454e540000000131000000013100000006414253454e5400000003010203");
    assertThat(java.util.Arrays.copyOf(authorizationBytes, authorizationPrefix.length))
        .containsExactly(authorizationPrefix);
    assertThat(
            java.util.Arrays.copyOfRange(
                authorizationBytes,
                authorizationBytes.length - authorizationSuffix.length,
                authorizationBytes.length))
        .containsExactly(authorizationSuffix);

    byte[] expectedPreimage =
        concat(
            asciiFrame(GameLogicPublicationSourceReadBinding.SCHEMA),
            asciiFrame(publicationBytes),
            asciiFrame(authorizationBytes));
    assertThat(expectedPreimage).hasSize(2310);
    assertThat(new String(expectedPreimage, 0, 40, StandardCharsets.US_ASCII))
        .isEqualTo("37:game-logic-publication-source-read/v1");
    assertThat(new String(expectedPreimage, 40, 4, StandardCharsets.US_ASCII)).isEqualTo("315:");
    assertThat(new String(expectedPreimage, 359, 5, StandardCharsets.US_ASCII)).isEqualTo("1946:");
    assertThat(binding.canonicalBytes()).containsExactly(expectedPreimage);
    assertThat(sha256(expectedPreimage)).isEqualTo(EXPECTED_PREIMAGE_SHA256);
    assertThat(binding.digest()).isEqualTo(EXPECTED_PREIMAGE_SHA256);
    assertThat(fromAuthorizationBytes.canonicalBytes()).containsExactly(expectedPreimage);

    var decoded =
        GameLogicPublicationSourceReadBinding.fromStored(publicationRequest, expectedPreimage);
    assertThat(decoded.publicationRequest()).isSameAs(publicationRequest);
    assertThat(decoded.authorization().canonicalBytes())
        .containsExactly(authorization.canonicalBytes());
    assertThat(decoded.canonicalBytes()).containsExactly(expectedPreimage);
    assertThat(decoded.digest()).isEqualTo(binding.digest());
    byte[] returnedBytes = binding.canonicalBytes();
    returnedBytes[0] ^= 1;
    assertThat(binding.canonicalBytes()).containsExactly(expectedPreimage);
  }

  @Test
  void rejectsMismatchedTenantVersionAndScriptPatchScope() {
    var authorization = authorization(OPERATION, FENCE, COMMIT, TENANT, 41);
    assertThatThrownBy(
            () ->
                new GameLogicPublicationSourceReadBinding(
                    publicationRequest("88888888-8888-4888-8888-888888888888", "41"),
                    authorization))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameLogicPublicationSourceReadBinding(
                    publicationRequest(TENANT.toString(), "42"), authorization))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameLogicPublicationSourceReadBinding(
                    PublicationDigestRequestBinding.patch(
                        TENANT.toString(), "41", "patch-1", "pub-1"),
                    authorization))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bindsOperationFenceAndSelectedCommitBytesWithoutTreatingThemAsAuthority() {
    var publicationRequest = publicationRequest(TENANT.toString(), "41");
    var original =
        new GameLogicPublicationSourceReadBinding(
            publicationRequest, authorization(OPERATION, FENCE, COMMIT, TENANT, 41));

    var differentOperation =
        new GameLogicPublicationSourceReadBinding(
            publicationRequest,
            authorization(uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), FENCE, COMMIT, TENANT, 41));
    var differentFence =
        new GameLogicPublicationSourceReadBinding(
            publicationRequest,
            authorization(
                OPERATION, uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), COMMIT, TENANT, 41));
    var differentCommit =
        new GameLogicPublicationSourceReadBinding(
            publicationRequest,
            authorization(
                OPERATION, FENCE, uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), TENANT, 41));

    assertThat(differentOperation.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(differentOperation.digest()).isNotEqualTo(original.digest());
    assertThat(differentFence.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(differentFence.digest()).isNotEqualTo(original.digest());
    assertThat(differentCommit.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(differentCommit.digest()).isNotEqualTo(original.digest());
  }

  @Test
  void rejectsMalformedNoncanonicalTruncatedTrailingAndOversizedFrames() {
    var publicationRequest = publicationRequest(TENANT.toString(), "41");
    byte[] valid =
        new GameLogicPublicationSourceReadBinding(
                publicationRequest, authorization(OPERATION, FENCE, COMMIT, TENANT, 41))
            .canonicalBytes();

    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest, concat(bytes("0"), valid)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest, concat(bytes("+"), valid)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest, java.util.Arrays.copyOf(valid, valid.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest,
                    concat(
                        asciiFrame(GameLogicPublicationSourceReadBinding.SCHEMA),
                        asciiFrame(publicationRequest.canonicalPreimage()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest, concat(valid, asciiFrame(bytes("extra")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest,
                    concat(
                        asciiFrame(GameLogicPublicationSourceReadBinding.SCHEMA),
                        asciiFrame(publicationRequest.canonicalPreimage()),
                        bytes("4194305:"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadBinding.fromStored(
                    publicationRequest,
                    new byte[GameLogicPublicationSourceReadBinding.MAX_TOTAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);

    for (UUID identity : List.of(OPERATION, FENCE, COMMIT)) {
      assertThatThrownBy(
              () ->
                  GameLogicPublicationSourceReadBinding.fromStored(
                      publicationRequest,
                      replaceFirst(
                          valid,
                          bytes(identity.toString()),
                          bytes("x" + identity.toString().substring(1)))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static PublicationDigestRequestBinding publicationRequest(String tenant, String version) {
    return PublicationDigestRequestBinding.full(tenant, version, "publication-request-1");
  }

  private static GameLogicIntakeAuthorizationBinding authorization(
      UUID operation, UUID fence, UUID commit, UUID tenant, long versionRowId) {
    DraftCommitBinding selected = selectedBinding(tenant, commit, versionRowId);
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                java.util.Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selected.canonicalJson(),
                    "bindingDigest",
                    selected.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    "99999999-9999-4999-8999-999999999999",
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    var evidence =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            ACTOR.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        operation, fence, INTAKE_REQUEST, ACTOR, source, List.of(evidence));
  }

  private static DraftCommitBinding selectedBinding(UUID tenant, UUID commit, long versionRowId) {
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, VERSION, versionRowId, "private-tenant", 7, "private-tenant", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        commit,
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                VERSION.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static byte[] asciiFrame(String value) {
    return asciiFrame(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] asciiFrame(byte[] value) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(value);
    return output.toByteArray();
  }

  private static byte[] concat(byte[]... values) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (byte[] value : values) output.writeBytes(value);
    return output.toByteArray();
  }

  private static byte[] replaceFirst(byte[] original, byte[] find, byte[] replacement) {
    if (find.length != replacement.length) {
      throw new IllegalArgumentException("Replacement must preserve byte length");
    }
    byte[] result = original.clone();
    for (int start = 0; start <= result.length - find.length; start++) {
      int index = 0;
      while (index < find.length && result[start + index] == find[index]) index++;
      if (index == find.length) {
        System.arraycopy(replacement, 0, result, start, replacement.length);
        return result;
      }
    }
    throw new IllegalArgumentException("Expected fixture bytes were not found");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String sha256(byte[] value) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
