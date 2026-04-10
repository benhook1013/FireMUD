package net.firedevops.firemud.common.config;

import io.grpc.stub.AbstractStub;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CommonSecurityAutoConfiguration {

  @Bean
  @ConditionalOnBean(JwtUtil.class)
  @ConditionalOnMissingBean(BlockingGrpcStubCustomizer.class)
  BlockingGrpcStubCustomizer blockingGrpcStubCustomizer(@Qualifier("jwtUtil") JwtUtil jwtUtil) {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return GrpcClientAuth.attach(stub, jwtUtil);
      }
    };
  }
}
