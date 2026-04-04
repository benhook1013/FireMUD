package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.security.JwtAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers web MVC interceptors. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final JwtAuthInterceptor jwtAuthInterceptor;

  @Autowired
  public WebConfig(JwtAuthInterceptor jwtAuthInterceptor) {
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(jwtAuthInterceptor)
        .excludePathPatterns(
            "/auth/login",
            "/auth/player-bootstrap",
            "/auth/connect-token",
            "/auth/request-password-reset",
            "/auth/complete-password-reset",
            "/auth/request-email-verification",
            "/auth/verify-email",
            "/auth/recover-username",
            "/accounts",
            "/ping",
            "/.well-known/**");
  }
}
