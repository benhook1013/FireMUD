# Spring Cloud Gateway Task List

- **Prepare Helm chart for Spring Cloud Gateway**
- **Finalize Spring Cloud Gateway design**
- **Develop Spring Cloud Gateway**
  - Handle API routing and request validation
  - Terminate TLS and forward traffic to internal services using mTLS
  - Collect connection metrics and throttle abusive clients
  - Create gateway route configuration files for all services
  - Add baseline route configuration for Spring Cloud Gateway
  - Create `GatewayController` endpoints for dynamic route management
    - Allow creation of custom gateway routes via API
  - Add gRPC `GatewayManagementService` for remote route configuration
