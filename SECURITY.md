# Security Policy

FireMUD takes the security of our platform and players seriously. This document outlines how to report vulnerabilities and summarizes the key protections described in the design documentation.

## Reporting a Vulnerability

If you believe you have discovered a security issue, **do not open a public issue**.

### Preferred Method: GitHub Private Reporting

This repository has [GitHub's private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability) enabled.

Click the **"Report a vulnerability"** button on the repository’s main page to securely notify us through GitHub. This provides a secure and trackable way for us to investigate and respond.

### Alternative Method: Email

If you prefer not to use GitHub, please email [security@firedevops.net](mailto:security@firedevops.net) with details so we can coordinate a fix. We will respond promptly and may request additional information to reproduce the issue.

Once resolved, credit will be given in the release notes unless you prefer to remain anonymous.

## Supported Versions

Security fixes are applied to actively maintained branches. In practice this means the main development line and any explicitly supported release line. Older versions are not supported.

## Security Practices

The FireMUD architecture is designed around the following controls:

- **Secret management** – JWT signing keys and mTLS certificates are stored as Kubernetes Secrets and rotated automatically by cert-manager. See [Security Architecture](design/architecture/system-architecture-security.md).
- **Encrypted transport** – TLS is terminated at the load balancer; internal gRPC calls use mutual TLS. NetworkPolicies restrict access between services.
- **Abuse detection** – Login attempts are tracked and rate-limited per IP. Suspicious behavior triggers blacklisting and notifications.
- **Logging and auditing** – Admin actions and failed logins are logged and surfaced through the Logging & Admin workflows and observability stack.
- **Rate limiting** – Spring Cloud Gateway applies IP-based rate limits on public endpoints.
- **Container and dependency scanning** – CI includes container and dependency security scanning, including scheduled review of published artifacts and dependencies.
- **Web security testing** – CI includes automated web security scanning for the web client surface.
- **Dependency advisory and license review** – CI includes license scanning and dependency advisory review.
- **Dependabot** – Automatically keeps dependencies patched via security PRs.

## References

The full security design is documented in [Security Architecture](design/architecture/system-architecture-security.md). Related topics:

- [Logging & Monitoring](design/architecture/system-architecture-logging-monitoring.md)
- [CI/CD Pipeline](design/architecture/system-architecture-cicd.md)
- [Environment & Secrets Management](design/architecture/infrastructure/environment-and-secrets.md)
