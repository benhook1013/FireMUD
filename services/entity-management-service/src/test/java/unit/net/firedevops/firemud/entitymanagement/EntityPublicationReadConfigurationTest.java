package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

class EntityPublicationReadConfigurationTest {
  @Test
  void onlyPingAndTheMtlsGuardedDigestBypassJwtAtTheTransport() {
    YamlMapFactoryBean yaml = new YamlMapFactoryBean();
    yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
    Map<String, Object> properties = yaml.getObject();

    assertThat(properties).isNotNull();
    Map<?, ?> firemud = (Map<?, ?>) properties.get("firemud");
    Map<?, ?> auth = (Map<?, ?>) firemud.get("auth");
    Map<?, ?> grpc = (Map<?, ?>) auth.get("grpc");
    List<String> methods =
        ((List<?>) grpc.get("public-methods")).stream().map(String::valueOf).toList();
    assertThat(methods)
        .containsExactly(
            "entity_management.v1.EntityManagementService/Ping",
            PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD);
  }
}
