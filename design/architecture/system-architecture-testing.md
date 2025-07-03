# 🧪 FireMUD System Architecture: Testing Strategy

FireMUD employs a layered testing approach to keep services reliable while avoiding excessive CI/CD costs. This document describes the scope of each test type, the tooling in use, and how these tests fit into our development workflow.

---

## 📝 Testing Scope

Each microservice has its own unit and integration tests. Cross‑service scenarios are also covered in a dedicated suite. Load tests run independently using Gatling.

- **Unit tests** live under each service in `src/test/java/unit/`.
- **Integration tests** for that service live in `src/test/java/integration/` and may start Redis, Postgres, or other dependencies on demand.
- **Cross-service integration tests** exercise workflows that span multiple services. They are triggered via separate Gradle tasks and can orchestrate several services with Docker or Testcontainers.
- **Load tests** reside in `src/gatling` and simulate real usage patterns. These tests are run manually when preparing a major release.

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

Separate jobs exist for unit, integration, and cross-service tests during manual merge review, but they are not executed in CI.

---

## 🚦 CI/CD Integration

To keep cloud usage minimal, GitHub Actions only builds and deploys services. Developers run the full test suite locally before merging. The [CI/CD Pipeline](./system-architecture-cicd.md) document references this manual step.

Load testing is executed on demand outside of CI and does not block deployments.

---

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [System Architecture Overview](./system-architecture-overview.md)
