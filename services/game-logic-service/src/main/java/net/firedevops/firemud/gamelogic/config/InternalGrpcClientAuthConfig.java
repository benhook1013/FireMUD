package net.firedevops.firemud.gamelogic.config;

import io.grpc.stub.AbstractStub;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class InternalGrpcClientAuthConfig {

  @Bean
  @Primary
  BlockingGrpcStubCustomizer internalGrpcClientStubCustomizer(
      JwtUtil jwtUtil, ObjectProvider<RuntimeIdentity> runtimeIdentityProvider) {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return GrpcClientAuth.attachInternal(
            stub, jwtUtil, runtimeIdentityProvider.getIfAvailable());
      }
    };
  }
}
