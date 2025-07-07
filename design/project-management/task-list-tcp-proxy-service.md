# TCP Proxy Service Task List

- **Prepare Helm chart for TCP Proxy Service**
- **Implement dedicated TCP Proxy Service bridging Telnet clients to the Gateway**
- **Define Telnet bridge gRPC APIs for TCP Proxy Service**
- **Develop TCP Proxy Service**
  - Implement Telnet networking and WebSocket bridging
  - Buffer Telnet input and discard on disconnect to support reconnection
  - Initialize `TcpProxyServiceApplication` with Netty server (implement connection pipeline)
  - Enforce Telnet command whitelist and input sanitization
  - Implement connection throttling and rate limits
  - Support TLS termination for secure Telnet clients
