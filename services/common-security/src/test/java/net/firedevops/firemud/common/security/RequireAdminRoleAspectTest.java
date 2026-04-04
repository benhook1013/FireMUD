package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class RequireAdminRoleAspectTest {

  @Test
  void deniesWithoutAdminRole() {
    ExampleService proxy = proxy(new ExampleService());

    SessionContext.clear();

    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, proxy::adminOnly);
    assertEquals(Status.PERMISSION_DENIED.getCode(), ex.getStatus().getCode());
  }

  @Test
  void allowsWithAdminRole() {
    ExampleService proxy = proxy(new ExampleService());
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    try {
      assertEquals("ok", proxy.adminOnly());
    } finally {
      SessionContext.clear();
    }
  }

  private ExampleService proxy(ExampleService target) {
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.addAspect(new RequireAdminRoleAspect());
    return factory.getProxy();
  }

  static class ExampleService {
    @RequireAdminRole
    public String adminOnly() {
      return "ok";
    }
  }
}
