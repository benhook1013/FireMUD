# Security Policy

FireMUD takes the security of our platform and players seriously. This document outlines how to report vulnerabilities and summarizes the key protections described in the design documentation.

## Reporting a Vulnerability

If you believe you have discovered a security issue, **do not open a public issue**. Please email [Ben.Hook@firedevops.net](mailto:Ben.Hook@firedevops.net) with details so we can investigate and coordinate a fix.

We will respond promptly and may request additional information to reproduce the problem. Once resolved, credit will be given in the release notes unless you prefer to remain anonymous.

## Supported Versions

Security fixes are applied to the `main` branch and the most recent stable release. Older versions are not supported. Always update to the latest release before reporting a bug to ensure it has not already been addressed.

## Security Practices

The FireMUD architecture is designed around the following controls:

- **Secret management** – JWT signing keys and mTLS certificates are stored as Kubernetes Secrets and rotated automatically by cert-manager. Services hot‑reload updated secrets without downtime. See [Security Architecture](design/architecture/system-architecture-security.md#🔑-token-issuance--secret-storage).
- **Encrypted transport** – External traffic terminates TLS at the load balancer and all internal gRPC calls use mutual TLS. NetworkPolicies restrict access so only internal services can reach each other.
- **Abuse detection** – Login attempts are tracked per IP. Repeated failures trigger temporary blacklisting and notification emails. Suspicious activity is logged for operator review.
- **Logging and auditing** – All failed logins and admin actions are stored in Elasticsearch and surfaced through the Logging & Admin Service dashboard.
- **Rate limiting** – Spring Cloud Gateway applies rate limits and basic abuse protections for public endpoints.
- **Container and dependency scanning** – Pull requests and nightly workflows run Trivy scans. A weekly workflow scans published images. CodeQL analysis runs on every push to `main`.
- **Web security testing** – OWASP ZAP is executed during CI to crawl the web client and gateway for common vulnerabilities.
- **Dependabot** – Automated dependency update PRs help keep libraries patched.

## References

The full security design is documented in [design/architecture/system-architecture-security.md](design/architecture/system-architecture-security.md). Additional operational details can be found in:

- [Logging & Monitoring](design/architecture/system-architecture-logging-monitoring.md)
- [CI/CD Pipeline](design/architecture/system-architecture-cicd.md)
- [Environment & Secrets Management](design/architecture/infrastructure/environment-and-secrets.md)
