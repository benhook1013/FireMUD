package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class WorldOwnerBindingSerializationTest {
  private static final OwnerBinding OWNER_BINDING =
      new OwnerBinding(
          "firemud",
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"),
          UUID.fromString("00000000-0000-0000-0000-000000000003"),
          42L,
          UUID.fromString("00000000-0000-0000-0000-000000000004"),
          UUID.fromString("00000000-0000-0000-0000-000000000005"),
          "sha256:" + "1".repeat(64),
          UUID.fromString("00000000-0000-0000-0000-000000000006"),
          "sha256:" + "2".repeat(64),
          "sha256:" + "3".repeat(64));

  @Test
  void ownerBytesUseStableDefaultSerializationRegardlessOfApplicationMapperConfiguration() {
    var applicationMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    String serializedOwner = WorldDraftTopologyCommitRepository.ownerBindingJson(OWNER_BINDING);

    assertThat(serializedOwner)
        .isEqualTo(
            "{\"targetNamespace\":\"firemud\","
                + "\"canonicalTenantId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"canonicalVersionId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"versionIdentityOperationId\":\"00000000-0000-0000-0000-000000000003\","
                + "\"gameDesignVersionId\":42,"
                + "\"intakeRequestId\":\"00000000-0000-0000-0000-000000000004\","
                + "\"intakeOperationId\":\"00000000-0000-0000-0000-000000000005\","
                + "\"intakeRequestDigest\":\"sha256:"
                + "1111111111111111111111111111111111111111111111111111111111111111\","
                + "\"sourceOperationId\":\"00000000-0000-0000-0000-000000000006\","
                + "\"sourceEvidenceDigest\":\"sha256:"
                + "2222222222222222222222222222222222222222222222222222222222222222\","
                + "\"intakeReceiptDigest\":\"sha256:"
                + "3333333333333333333333333333333333333333333333333333333333333333\"}");
    assertThat(applicationMapper.writeValueAsString(OWNER_BINDING)).isNotEqualTo(serializedOwner);
  }
}
