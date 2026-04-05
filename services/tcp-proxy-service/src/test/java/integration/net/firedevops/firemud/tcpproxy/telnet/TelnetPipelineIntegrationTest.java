package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TelnetPipelineIntegrationTest {

  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);

  private TelnetServerHandler.WebSocketConnector stubConnector(WebSocket ws) {
    return (gatewayWsUrl, clientIp, proxyConnectionId, gameInstanceId, tenantId, listener) ->
        CompletableFuture.completedFuture(ws);
  }

  @Test
  void rawBytesFlowThroughPipelineWithNegotiationResponses() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebSocket ws = Mockito.mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    Mockito.when(ws.sendText(Mockito.anyString(), Mockito.eq(true))).thenReturn(future);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("connections"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            stubConnector(ws),
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "sess-1",
            "tenant-1",
            lookCacheService);

    EmbeddedChannel channel =
        new EmbeddedChannel(
            new LineBasedFrameDecoder(1024, false, true),
            new StringDecoder(StandardCharsets.ISO_8859_1),
            handler);
    handler.setWebSocket(ws);

    byte[] payload = {(byte) 255, (byte) 253, (byte) 1, 'l', 'i', 'n', 'e', '\r', '\n'};
    channel.writeInbound(Unpooled.wrappedBuffer(payload));

    String guidance = channel.readOutbound();
    assertEquals(
        "OK CONNECTED\n"
            + "Type WORLDS to list available worlds.\n"
            + "Type LOGIN <email> <password> to authenticate.\n"
            + "Type PLAY <world> after LOGIN to enter a world.\n"
            + "Type HELP for commands.\n",
        guidance);

    ByteBuf response = channel.readOutbound();
    assertEquals((byte) 255, response.readByte());
    assertEquals((byte) 251, response.readByte());
    assertEquals((byte) 1, response.readByte());
    response.release();

    Mockito.verify(ws).sendText("line\n", true);
  }

  @Test
  void unsupportedSubNegotiationInPipelineIncrementsMetric() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebSocket ws = Mockito.mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    Mockito.when(ws.sendText(Mockito.anyString(), Mockito.eq(true))).thenReturn(future);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("connections"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            stubConnector(ws),
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "sess-1",
            "tenant-1",
            lookCacheService);

    EmbeddedChannel channel =
        new EmbeddedChannel(
            new LineBasedFrameDecoder(1024, false, true),
            new StringDecoder(StandardCharsets.ISO_8859_1),
            handler);
    handler.setWebSocket(ws);

    byte[] payload = {
      (byte) 255, (byte) 250, (byte) 99, 'x', (byte) 255, (byte) 240, 'c', 'm', 'd', '\r', '\n'
    };
    channel.writeInbound(Unpooled.wrappedBuffer(payload));

    assertEquals(1.0, registry.counter("discarded").count());
    Mockito.verify(ws).sendText("cmd\n", true);
  }
}
