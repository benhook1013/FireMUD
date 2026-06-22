package net.firedevops.firemud.tcpproxy.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TcpProxyEventClientTest {

  @Test
  void notifyDisconnectUsesBoundedDeadlineAndCanonicalRequestFields() {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    BlockingGrpcStubCustomizer stubCustomizer = BlockingGrpcStubCustomizer.noop();
    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            mock(GrpcChannelFactory.class),
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub stub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub deadlineStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    when(stub.withDeadlineAfter(2000L, TimeUnit.MILLISECONDS)).thenReturn(deadlineStub);
    when(deadlineStub.notifyDisconnect(any()))
        .thenReturn(NotifyDisconnectResponse.newBuilder().build());
    setField(client, "stub", stub);

    client.notifyDisconnect("42", "7", "proxy-1", 9L);

    ArgumentCaptor<net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest> requestCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.class);
    verify(stub).withDeadlineAfter(2000L, TimeUnit.MILLISECONDS);
    verify(deadlineStub).notifyDisconnect(requestCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("42", requestCaptor.getValue().getSessionId());
    org.junit.jupiter.api.Assertions.assertEquals(
        "42", requestCaptor.getValue().getGameInstanceId());
    org.junit.jupiter.api.Assertions.assertEquals("7", requestCaptor.getValue().getTenantId());
    org.junit.jupiter.api.Assertions.assertEquals(
        "proxy-1", requestCaptor.getValue().getProxyConnectionId());
    org.junit.jupiter.api.Assertions.assertEquals(
        9L, requestCaptor.getValue().getDisconnectSequence());
  }

  @Test
  void reloadChannelCustomizesOutboundStub() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    io.grpc.ManagedChannel channel = mock(io.grpc.ManagedChannel.class);
    when(channelFactory.buildChannel(any(), any(Integer.class), any(), any(Boolean.class), any()))
        .thenReturn(channel);
    BlockingGrpcStubCustomizer stubCustomizer = mock(BlockingGrpcStubCustomizer.class);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub customizedStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    when(stubCustomizer.customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class)))
        .thenReturn(customizedStub);

    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            channelFactory,
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);

    invokeReloadChannel(client);

    verify(stubCustomizer).customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class));
    org.junit.jupiter.api.Assertions.assertSame(customizedStub, getField(client, "stub"));
  }

  @Test
  void reloadChannelKeepsExistingStateWhenStubCustomizationFails() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    io.grpc.ManagedChannel previousChannel = mock(io.grpc.ManagedChannel.class);
    io.grpc.ManagedChannel newChannel = mock(io.grpc.ManagedChannel.class);
    when(channelFactory.buildChannel(any(), any(Integer.class), any(), any(Boolean.class), any()))
        .thenReturn(newChannel);
    BlockingGrpcStubCustomizer stubCustomizer = mock(BlockingGrpcStubCustomizer.class);
    when(stubCustomizer.customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class)))
        .thenThrow(new IllegalStateException("customizer failed"));
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub previousStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);

    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            channelFactory,
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);
    setField(client, "channel", previousChannel);
    setField(client, "stub", previousStub);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> invokeReloadChannel(client));

    org.junit.jupiter.api.Assertions.assertSame(previousChannel, getField(client, "channel"));
    org.junit.jupiter.api.Assertions.assertSame(previousStub, getField(client, "stub"));
    verify(newChannel).shutdown();
    verify(previousChannel, org.mockito.Mockito.never()).shutdown();
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }

  private static Object getField(Object target, String fieldName) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to read field " + fieldName, e);
    }
  }

  private static void invokeReloadChannel(TcpProxyEventClient client) {
    try {
      var method = TcpProxyEventClient.class.getDeclaredMethod("reloadChannel");
      method.setAccessible(true);
      method.invoke(client);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to invoke reloadChannel", e);
    }
  }
}
