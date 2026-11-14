package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EmailLinkConfigurationTest {
  @Test
  void localAndProductionLinksUseReadOnlyFrontendLandingsAndPublicPostCompletion() {
    assertProfile(
        "application.yml",
        "http://localhost:5173/verify-email#token=%s",
        "http://localhost:5173/reset-password#token=%s");
    assertProfile(
        "application-prod.yml",
        "${FIREMUD_MAIL_VERIFICATION_URL:https://firemud.com/verify-email#token=%s}",
        "${FIREMUD_MAIL_RESET_URL:https://firemud.com/reset-password#token=%s}");
  }

  @Test
  void configuredPublicPostIsAcceptedWithoutBearerButGetAndNeighborsAreDenied() throws Exception {
    for (String resource : List.of("application.yml", "application-prod.yml")) {
      Properties properties = load(resource);
      HttpAuthProperties auth = new HttpAuthProperties();
      List<HttpAuthProperties.HttpPublicRoute> routes = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        String prefix = "firemud.auth.http.public-routes[" + index + "]";
        String method = properties.getProperty(prefix + ".method");
        String path = properties.getProperty(prefix + ".path-pattern");
        if (method == null || path == null) {
          continue;
        }
        HttpAuthProperties.HttpPublicRoute route = new HttpAuthProperties.HttpPublicRoute();
        route.setMethod(method);
        route.setPathPattern(path);
        routes.add(route);
      }
      auth.setPublicRoutes(routes);
      HttpJwtAuthInterceptor interceptor =
          new HttpJwtAuthInterceptor(Mockito.mock(JwtUtil.class), auth);

      assertThat(
              interceptor.preHandle(
                  request("POST", "/auth/verify-email"), new MockHttpServletResponse(), null))
          .as(resource)
          .isTrue();
      assertThat(
              interceptor.preHandle(
                  request("POST", "/auth/complete-password-reset"),
                  new MockHttpServletResponse(),
                  null))
          .as(resource)
          .isTrue();
      for (MockHttpServletRequest denied :
          List.of(
              request("GET", "/auth/verify-email"),
              request("GET", "/auth/reset-password"),
              request("POST", "/auth/verify-email/internal"))) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(denied, response, null)).as(resource).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
      }
    }
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static void assertProfile(String resource, String verificationUrl, String resetUrl) {
    Properties properties = load(resource);
    assertThat(properties.getProperty("firemud.mail.verification-url")).isEqualTo(verificationUrl);
    assertThat(properties.getProperty("firemud.mail.reset-url")).isEqualTo(resetUrl);

    boolean publicVerificationPost = false;
    boolean publicVerificationGet = false;
    for (int index = 0; index < 32; index++) {
      String prefix = "firemud.auth.http.public-routes[" + index + "]";
      if (!"/auth/verify-email".equals(properties.getProperty(prefix + ".path-pattern"))) {
        continue;
      }
      publicVerificationPost |= "POST".equals(properties.getProperty(prefix + ".method"));
      publicVerificationGet |= "GET".equals(properties.getProperty(prefix + ".method"));
    }
    assertThat(publicVerificationPost).isTrue();
    assertThat(publicVerificationGet).isFalse();
  }

  private static Properties load(String resource) {
    YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
    Path moduleLocal = Path.of("src", "main", "resources", resource);
    Path repositoryLocal =
        Path.of("services", "account-service", "src", "main", "resources", resource);
    loader.setResources(
        new FileSystemResource(Files.exists(moduleLocal) ? moduleLocal : repositoryLocal));
    Properties properties = loader.getObject();
    assertThat(properties).isNotNull();
    return properties;
  }
}
