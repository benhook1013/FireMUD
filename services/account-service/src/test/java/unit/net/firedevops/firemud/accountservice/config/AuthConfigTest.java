package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.security.JwtAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AuthConfigTest {
  @Test
  void generatesSecretWhenMissingInDev() {
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("dev");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("test", Map.of("firemud.auth.jwt-expiration-ms", "3600000")));
    ctx.setEnvironment(env);
    ctx.register(CommonSecurityAutoConfiguration.class);
    ctx.refresh();

    JwtAuthProperties props = ctx.getBean(JwtAuthProperties.class);

    assertThat(props.getJwtSecret()).isNotBlank();
    assertThat(ctx.getBean(JwtUtil.class)).isNotNull();

    ctx.close();
  }
}
