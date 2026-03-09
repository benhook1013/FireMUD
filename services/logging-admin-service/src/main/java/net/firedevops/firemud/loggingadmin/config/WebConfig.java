package net.firedevops.firemud.loggingadmin.config;

import net.firedevops.firemud.loggingadmin.security.JwtAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final JwtAuthInterceptor jwtAuthInterceptor;

  @Autowired
  public WebConfig(JwtAuthInterceptor jwtAuthInterceptor) {
    this.jwtAuthInterceptor = jwtAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(jwtAuthInterceptor);
  }
}
