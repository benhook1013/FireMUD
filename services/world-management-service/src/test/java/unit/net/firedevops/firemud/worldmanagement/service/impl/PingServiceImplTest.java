package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PingServiceImplTest {
  @Test
  void pingReturnsPong() {
    PingServiceImpl service = new PingServiceImpl();
    assertEquals("pong", service.ping());
  }
}
