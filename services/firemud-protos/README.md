# FireMUD Protos

This module packages all protobuf definitions under the top-level `protos/` directory. Use it when other projects need the raw `.proto` files without cloning the entire repository.

## Publishing

Artifacts are published as `firemud-protos` to the GitHub Packages registry:

```bash
./gradlew :firemud-protos:publish -Pgpr.user=<username> -Pgpr.key=<token>
```

The resulting JAR contains only `.proto` resources. Generated sources are not included so consuming projects can apply their own code generation steps.
