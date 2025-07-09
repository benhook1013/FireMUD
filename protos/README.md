# FireMUD Proto Definitions

This module contains all gRPC and protocol buffer definitions shared across the FireMUD services.
The Gradle build publishes a `firemud-protos` artifact so that other projects can depend on a single
package rather than copying proto files.

Run `./gradlew :firemud-protos:publish` to deploy the artifact to the GitHub Packages registry.
