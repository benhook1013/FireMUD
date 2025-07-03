# 🛡️ FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, and protects network traffic. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, and cross-service trust.

---

## 🔑 Token Issuance & Secret Storage

- The **Account Service** signs JWTs used for internal gRPC authorization.
- Signing keys are stored as **Kubernetes Secrets** and mounted into the service at runtime.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A simple **JWKS** endpoint exposes the public keys so other services can validate tokens.

### Key Rotation

1. Upload a new signing key as a Kubernetes Secret.
2. Update the Account Service deployment to mount the new key alongside the old one.
3. Publish the updated public key via the JWKS endpoint.
4. After existing tokens expire, remove the old key from the deployment and Secret store.

> Tokens are short-lived, so key rotation does not disrupt active sessions.

---

## 🔒 TLS Termination & Internal Encryption

- External `https/wss` traffic is terminated at the **Ingress** or load balancer.
- Spring Cloud Gateway communicates with backend services over **TLS** to protect gameplay traffic from eavesdropping.
- gRPC calls between microservices use **mutual TLS (mTLS)**. Certificates are issued by an internal CA and distributed through Kubernetes Secrets.

---

## 🤝 Cross-Service Trust

- Services verify JWTs using the public keys from the Account Service’s JWKS endpoint.
- mTLS ensures that only authenticated services can initiate gRPC requests.
- Network policies restrict which pods may communicate, preventing lateral movement in the cluster.

---

## 🌐 Network Security Policies

- **Kubernetes NetworkPolicies** define allowed ingress/egress for each namespace.
- Only the Gateway and trusted services can reach gameplay services on their gRPC ports.
- Admin tools and observability stacks are isolated in separate namespaces with stricter rules.

---

## ✅ Summary

| Topic                | Strategy |
|----------------------|---------------------------------------------------------------|
| JWT Secret Storage   | Kubernetes Secrets mounted into the Account Service |
| Key Rotation         | Upload new secret → publish public key → remove old secret |
| TLS Termination      | Ingress/load balancer terminates external TLS |
| Service Encryption   | mTLS for all gRPC traffic |
| Cross-Service Trust  | JWT validation + mTLS certificates |
| Network Policies     | Kubernetes NetworkPolicies restrict pod communication |

📚 Related:

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
