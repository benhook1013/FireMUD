package net.firedevops.firemud.tcpproxy.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
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
    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            mock(GrpcChannelFactory.class),
            mock(GrpcTlsMaterialResolver.class));
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

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }
}
