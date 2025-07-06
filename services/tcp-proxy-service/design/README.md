# 🔗 Design Document for TCP Proxy Service

The design for this service is located here:

[📄 Central Architecture: TCP Proxy Service Design](../../../design/architecture/microservices/tcp-proxy-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`tcp_proxy_service.proto`](../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -plaintext localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```
