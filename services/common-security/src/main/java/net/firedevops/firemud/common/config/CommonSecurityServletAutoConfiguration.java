package net.firedevops.firemud.common.config;

import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@AutoConfigureAfter(CommonSecurityAutoConfiguration.class)
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSecurityServletAutoConfiguration {

  @Bean
  @ConditionalOnBean(name = "jwtUtil")
  @ConditionalOnProperty(prefix = "firemud.auth.http", name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(HttpJwtAuthInterceptor.class)
  HttpJwtAuthInterceptor httpJwtAuthInterceptor(
      @Qualifier("jwtUtil") JwtUtil jwtUtil, HttpAuthProperties props) {
    return new HttpJwtAuthInterceptor(jwtUtil, props);
  }

  @Bean
  @ConditionalOnBean(HttpJwtAuthInterceptor.class)
  @ConditionalOnMissingBean(name = "firemudHttpAuthWebMvcConfigurer")
  WebMvcConfigurer firemudHttpAuthWebMvcConfigurer(
      HttpJwtAuthInterceptor interceptor, HttpAuthProperties props) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(props.getIncludePathPatterns());
      }
    };
  }
}
