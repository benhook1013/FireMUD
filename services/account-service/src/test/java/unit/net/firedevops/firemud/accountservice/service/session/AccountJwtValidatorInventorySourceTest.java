package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtValidatorInventorySourceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> CONTAINER_FAMILIES =
      List.of("containers", "initContainers", "ephemeralContainers");

  @Test
  void rejectsSigningSecretEnvironmentAndMountReferencesInEveryContainerFamily() throws Exception {
    for (String family : CONTAINER_FAMILIES) {
      assertRejected(
          podSpec(family, "{\"envFrom\":[{\"secretRef\":{\"name\":\"jwt-signing-keys\"}}]}"));
      assertRejected(
          podSpec(
              family,
              "{\"env\":[{\"name\":\"SIGNING_KEY\",\"valueFrom\":{"
                  + "\"secretKeyRef\":{\"name\":\"jwt-signing-keys\",\"key\":\"key\"}}}]}"));
      assertRejected(
          podSpec(
              family,
              "{\"volumeMounts\":[{\"name\":\"jwt-signing-keys\","
                  + "\"mountPath\":\"/private\"}]}"));
    }
  }

  @Test
  void rejectsSigningSecretInProjectedVolumeSources() throws Exception {
    JsonNode podSpec =
        JSON.readTree(
            "{\"containers\":[{\"name\":\"jwt-validator\"}],\"volumes\":["
                + "{\"name\":\"private-material\",\"projected\":{\"sources\":["
                + "{\"secret\":{\"name\":\"jwt-signing-keys\"}}]}}]}");

    assertRejected(podSpec);
  }

  @Test
  void acceptsPublicJwksSecretVolumesAndProjectedSources() throws Exception {
    JsonNode podSpec =
        JSON.readTree(
            "{\"containers\":[{\"name\":\"jwt-validator\",\"volumeMounts\":["
                + "{\"name\":\"jwt-jwks\",\"mountPath\":\"/var/run/secrets/firemud/jwks\","
                + "\"readOnly\":true}]}],\"volumes\":["
                + "{\"name\":\"jwt-jwks\",\"secret\":{\"secretName\":\"jwt-jwks\"}},"
                + "{\"name\":\"jwt-jwks-projected\",\"projected\":{\"sources\":["
                + "{\"secret\":{\"name\":\"jwt-jwks\"}}]}}]}");

    assertThatCode(() -> AccountJwtValidatorInventorySource.rejectSigningMaterialPodSpec(podSpec))
        .doesNotThrowAnyException();
  }

  private static JsonNode podSpec(String family, String container) throws Exception {
    String regularContainers =
        "containers".equals(family) ? "" : "\"containers\":[{\"name\":\"jwt-validator\"}],";
    return JSON.readTree("{" + regularContainers + "\"" + family + "\":[" + container + "]}");
  }

  private static void assertRejected(JsonNode podSpec) {
    assertThatThrownBy(
            () -> AccountJwtValidatorInventorySource.rejectSigningMaterialPodSpec(podSpec))
        .isInstanceOf(AccountJwtValidatorInventorySource.InventoryUnavailableException.class);
  }
}
