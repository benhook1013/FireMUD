# 🧪 FireMUD System Architecture: Testing Strategy

FireMUD employs a layered testing approach to keep services reliable while avoiding excessive CI/CD costs. This document describes the scope of each test type, the tooling in use, and how these tests fit into our development workflow.

---

## 📝 Testing Scope

Each microservice has its own unit and integration tests. Cross‑service scenarios are also covered in a dedicated suite. Load tests run independently using Gatling in a separate `load-testing` module.

- **Unit tests** live under each service in `src/test/java/unit/`.
- **Integration tests** for that service live in `src/test/java/integration/` and may start Redis, Postgres, or other dependencies on demand.
- **Cross-service integration tests** exercise workflows that span multiple services. They are triggered via separate Gradle tasks and can orchestrate several services with Docker or Testcontainers.
- **Load tests** reside in `dev-tools/load-testing/src/gatling` and simulate real usage patterns. These tests are run manually when preparing a major release.

Test data seeding strategies are still under discussion. For now, tests set up their own minimal fixtures programmatically.

---

## 🛠 Tooling and Gradle Layout

- **JUnit & Mockito** provide the core framework for unit and integration tests.
- **Spring Test** bootstraps service contexts and external resources.
- **Gatling** drives load testing scenarios.

The repository uses a hierarchical Gradle setup. The root `build.gradle` delegates to per‑service builds so each service defines its own `test`, `integrationTest`, and cross‑service tasks. This allows running:

```bash
./gradlew :service-name:test
./gradlew :service-name:integrationTest
./gradlew crossServiceTest
```

Unit and integration tests run automatically in GitHub Actions through the Gradle `check` task. Cross-service tests can be triggered manually when preparing a release.

## 💡 Cross-Service Integration Plan

For workflows that span multiple services, such as account creation and world provisioning, we start several containers at once using **Testcontainers**. Each container joins a shared network so gRPC calls function just like in production.

### Example Workflow

1. Launch PostgreSQL and Redis containers.
2. Start Account, Game Session, and World Management services.
3. Perform a registration and login sequence to verify saga state.

```kotlin
val network = Network.newNetwork()
val postgres = PostgreSQLContainer<Nothing>("postgres:15").withNetwork(network)
val accountService = GenericContainer("account-service:latest").withNetwork(network)
```

These tests run via `./gradlew crossServiceTest` and validate saga orchestration logic.

---

## 🚦 CI/CD Integration

GitHub Actions executes formatting and lint checks, builds the code, and runs all unit and integration tests via `./gradlew check`. Coverage reports are generated and a Trivy security scan is executed. See the [CI/CD Pipeline](./system-architecture-cicd.md) document for the workflow definition.

Load testing is executed on demand outside of CI and does not block deployments.

### High-Concurrency Load Testing

Gatling scenarios simulate thousands of concurrent connections to measure service limits and uncover bottlenecks. Results guide scaling decisions and database indexing.

### Security Testing

OWASP ZAP crawls the web client and Gateway endpoints during CI. Penetration tests and rate-limiting checks run before major releases.

---

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Testing & Continuous Delivery](./user-journeys.md#17-testing--continuous-delivery)
