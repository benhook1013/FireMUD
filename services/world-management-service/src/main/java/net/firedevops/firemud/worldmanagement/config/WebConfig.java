package net.firedevops.firemud.worldmanagement.config;

import net.firedevops.firemud.worldmanagement.security.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final JwtAuthInterceptor jwtAuthInterceptor;

  public WebConfig(JwtAuthInterceptor jwtAuthInterceptor) {
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(jwtAuthInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns("/actuator/**", "/ping", "/ping/", "/.well-known/**");
  }
}
