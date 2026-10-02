package net.firedevops.firemud.gamesession.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionFixtureCapability.CertificatePins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOwnedInitialAdmissionFixtureCapabilityTest {
  private static final String RUN_ID = "compose-smoke-2939";
  private static final String PROJECT_NAME = "firemud-smoke-compose-smoke-2939";
  private static final String OPERATION_ID = "d2db8478-9c56-42ab-99c2-9fbead6841ba";
  private static final String LEAF_SHA = "a".repeat(64);
  private static final String CA_SHA = "b".repeat(64);

  @Test
  void parsesExactCapabilityAndBindsRunProjectSelectorsAndTlsPins() {
    RunOwnedInitialAdmissionFixtureCapability capability =
        RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
            validJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            RUN_ID,
            PROJECT_NAME,
            pins());

    assertThat(capability.runId()).isEqualTo(RUN_ID);
    assertThat(capability.composeProjectName()).isEqualTo(PROJECT_NAME);
    assertThat(capability.operationId()).isEqualTo(OPERATION_ID);
    assertThat(capability.tenantId()).isEqualTo(7L);
    assertThat(capability.gameTemplateId()).isEqualTo(17L);
    assertThat(capability.ownerAccountId()).isEqualTo(27L);
    assertThat(capability.worldSlug()).isEqualTo("demo");
    assertThat(capability.realmSlug()).isEqualTo("production");
    assertThat(capability.visible()).isTrue();
    assertThat(capability.publicProductionRealm()).isTrue();
    assertThat(capability.requiresCharacterSelection()).isFalse();
    assertThat(capability.stateScope()).isEqualTo("SHARED");
    assertThat(capability.characterCreationPolicy()).isEqualTo("ALLOW_NEW");
  }

  @Test
  void rejectsUnknownDuplicateMismatchedAndNoncanonicalCapabilityFields() {
    assertInvalid(validJson().replace("\"schema\":", "\"extra\":true,\"schema\":"));
    assertInvalid(validJson().replace("\"schema\":", "\"schema\":\"wrong\",\"schema\":"));
    assertInvalid(validJson().replace(RUN_ID, "different-run"));
    assertInvalid(validJson().replace(OPERATION_ID, "not-a-uuid"));
    assertInvalid(
        validJson().replace("\"operationId\": \"" + OPERATION_ID + "\"", "\"operationId\": null"));
    assertInvalid(validJson().replace("\"tenantId\": 7", "\"tenantId\": \"7\""));
    assertInvalid(
        validJson().replace("\"stateScope\": \"SHARED\"", "\"stateScope\": \"ISOLATED\""));
    assertInvalid(validJson().replace(LEAF_SHA, "c".repeat(64)));
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
                    validJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RUN_ID,
                    "other-project",
                    pins()))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  @Test
  void rejectsWritableCapabilityFile(@TempDir Path directory) throws Exception {
    Path capabilityFile =
        Files.writeString(directory.resolve("fixture-capability.json"), validJson());

    assertThatThrownBy(
            () -> RunOwnedInitialAdmissionFixtureCapability.readCapability(capabilityFile))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  @Test
  void readsReadOnlyCapabilityFile(@TempDir Path directory) throws Exception {
    String json = validJson();
    Path capabilityFile = Files.writeString(directory.resolve("fixture-capability.json"), json);
    Files.setPosixFilePermissions(capabilityFile, Set.of(PosixFilePermission.OWNER_READ));

    assertThat(RunOwnedInitialAdmissionFixtureCapability.readCapability(capabilityFile))
        .isEqualTo(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void derivesPinsFromActualLeafAndCaCertificatesAndRequiresOneExactUriSan() throws Exception {
    X509Certificate leaf =
        certificate(
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN, new byte[] {1}, -1);
    X509Certificate ca = certificate(null, new byte[] {2}, 1);
    PublicKey publicKey = mock(PublicKey.class);
    when(ca.getPublicKey()).thenReturn(publicKey);
    when(leaf.getSubjectAlternativeNames())
        .thenReturn(
            List.of(List.of(6, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN)));

    CertificatePins pins = RunOwnedInitialAdmissionFixtureCapability.certificatePins(leaf, ca);

    assertThat(pins.leafSha256()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(pins.uriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN);
    assertThat(pins.caSha256()).hasSize(64).matches("[0-9a-f]{64}");

    when(leaf.getSubjectAlternativeNames())
        .thenReturn(
            List.of(
                List.of(6, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN),
                List.of(6, "spiffe://firemud/ns/dev/sa/other-service")));
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.certificatePins(leaf, ca))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  private static CertificatePins pins() {
    return new CertificatePins(
        LEAF_SHA, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN, CA_SHA);
  }

  private static String validJson() {
    return """
        {%n\
          "schema": "%s",%n\
          "runId": "%s",%n\
          "composeProjectName": "%s",%n\
          "operationId": "%s",%n\
          "tenantId": 7,%n\
          "gameTemplateId": 17,%n\
          "ownerAccountId": 27,%n\
          "worldSlug": "demo",%n\
          "worldDisplayName": "Demo World",%n\
          "realmSlug": "production",%n\
          "realmDisplayName": "Live Realm",%n\
          "visible": true,%n\
          "publicProductionRealm": true,%n\
          "requiresCharacterSelection": false,%n\
          "stateScope": "SHARED",%n\
          "characterCreationPolicy": "ALLOW_NEW",%n\
          "gameSessionLeafSha256": "%s",%n\
          "gameSessionUriSan": "%s",%n\
          "caCertificateSha256": "%s"%n\
        }%n\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureCapability.CAPABILITY_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            OPERATION_ID,
            LEAF_SHA,
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            CA_SHA);
  }

  private static X509Certificate certificate(String san, byte[] der, int basicConstraints)
      throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getBasicConstraints()).thenReturn(basicConstraints);
    when(certificate.getEncoded()).thenReturn(der);
    when(certificate.getPublicKey()).thenReturn(mock(PublicKey.class));
    if (san == null) {
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of());
    } else {
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, san)));
    }
    return certificate;
  }

  private static void assertInvalid(String json) {
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RUN_ID,
                    PROJECT_NAME,
                    pins()))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }
}
