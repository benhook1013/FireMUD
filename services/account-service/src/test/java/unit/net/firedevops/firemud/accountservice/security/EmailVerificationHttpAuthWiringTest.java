package unit.net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import net.firedevops.firemud.accountservice.controller.AuthController;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual Account YAML and servlet-auth composition with a mocked verification owner. */
class EmailVerificationHttpAuthWiringTest {
  private static final String PATH = "/auth/verify-email";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicPostReachesMappedVerificationOwnerWithoutBearer(boolean productionLayer) {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    configuredRunner(productionLayer, jwtUtil)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HttpJwtAuthInterceptor.class);
              HttpAuthProperties properties = context.getBean(HttpAuthProperties.class);
              assertThat(properties.isEnabled()).isTrue();
              assertThat(properties.getPublicRoutes())
                  .filteredOn(route -> PATH.equals(route.getPathPattern()))
                  .extracting(HttpAuthProperties.HttpPublicRoute::getMethod)
                  .containsExactly("POST");
              AccountService owner = mock(AccountService.class);
              var mvc =
                  MockMvcBuilders.standaloneSetup(new AuthController(owner))
                      .addInterceptors(context.getBean(HttpJwtAuthInterceptor.class))
                      .build();

              mvc.perform(
                      post(PATH)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"token\":\"exact-verification-token\"}"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.status").value("SUCCESS"));

              verify(owner).verifyEmail(new VerifyEmailRequest("exact-verification-token"));
              verifyNoMoreInteractions(owner);
              verifyNoInteractions(jwtUtil);
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicPostPreservesVerificationOwnersInvalidTokenOutcome(boolean productionLayer) {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    configuredRunner(productionLayer, jwtUtil)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HttpJwtAuthInterceptor.class);
              AccountService owner = mock(AccountService.class);
              VerifyEmailRequest invalid = new VerifyEmailRequest("invalid-token");
              doThrow(new IllegalArgumentException("Invalid token"))
                  .when(owner)
                  .verifyEmail(invalid);
              var mvc =
                  MockMvcBuilders.standaloneSetup(new AuthController(owner))
                      .setControllerAdvice(new GlobalExceptionHandler())
                      .addInterceptors(context.getBean(HttpJwtAuthInterceptor.class))
                      .build();

              mvc.perform(
                      post(PATH)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"token\":\"invalid-token\"}"))
                  .andExpect(status().isBadRequest());

              verify(owner).verifyEmail(invalid);
              verifyNoMoreInteractions(owner);
              verifyNoInteractions(jwtUtil);
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void getIsNotWhitelistedWithoutBearer(boolean productionLayer) {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    configuredRunner(productionLayer, jwtUtil)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HttpJwtAuthInterceptor.class);
              HttpJwtAuthInterceptor interceptor = context.getBean(HttpJwtAuthInterceptor.class);
              MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
              MockHttpServletResponse response = new MockHttpServletResponse();

              assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
              assertThat(response.getStatus()).isEqualTo(401);
              verifyNoInteractions(jwtUtil);
            });
  }

  private static WebApplicationContextRunner configuredRunner(
      boolean productionLayer, JwtUtil jwtUtil) {
    return new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class,
                CommonSecurityServletAutoConfiguration.class))
        .withBean("jwtUtil", JwtUtil.class, () -> jwtUtil)
        .withInitializer(
            context -> {
              try {
                YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                for (var source :
                    loader.load(
                        "account-application",
                        new FileSystemResource("src/main/resources/application.yml"))) {
                  context.getEnvironment().getPropertySources().addLast(source);
                }
                if (productionLayer) {
                  context.getEnvironment().setActiveProfiles("prod");
                  for (var source :
                      loader.load(
                          "account-application-prod",
                          new FileSystemResource("src/main/resources/application-prod.yml"))) {
                    context.getEnvironment().getPropertySources().addFirst(source);
                  }
                }
              } catch (IOException exception) {
                throw new IllegalStateException("Could not load Account HTTP auth YAML", exception);
              }
            });
  }
}
