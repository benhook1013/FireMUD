package net.firedevops.firemud.tcpproxy.telnet;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = TelnetServerHandlerSpringBootTest.TestConfig.class)
class TelnetServerHandlerSpringBootTest {

  @SpringBootConfiguration
  static class TestConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    LookCacheService lookCacheService() {
      return Mockito.mock(LookCacheService.class);
    }
  }

  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private TcpProxyEventService eventService;

  @Test
  void recordsConnectEventWhenHiddenBootstrapDefaultsApply() {
    AtomicInteger bufferDepth = new AtomicInteger();
    String sessionId = "session-33";
    String tenantId = "tenant-13";
    InetSocketAddress clientAddress = new InetSocketAddress("192.0.2.1", 6000);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            meterRegistry.counter("test.connections"),
            meterRegistry.counter("test.discarded"),
            false,
            meterRegistry,
            () -> true,
            TelnetServerHandler::createWebSocket,
            eventService,
            bufferDepth,
            sessionId,
            tenantId,
            Mockito.mock(LookCacheService.class));

    EmbeddedChannel channel =
        new EmbeddedChannel(
            new LineBasedFrameDecoder(1024, false, true),
            new StringDecoder(StandardCharsets.ISO_8859_1),
            handler) {
          @Override
          public InetSocketAddress remoteAddress() {
            return clientAddress;
          }
        };

    channel.pipeline().fireChannelActive();

    try {
      channel.writeInbound(Unpooled.copiedBuffer("look\r\n", StandardCharsets.ISO_8859_1));

      verify(eventService, timeout(1000))
          .recordConnectEvent(sessionId, tenantId, clientAddress.getAddress().getHostAddress());
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
