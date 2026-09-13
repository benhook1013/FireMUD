# PR CI timing inventory — benhook1013/FireMUD
Measured at 2026-09-13T13:09:31.645492Z; completed runs created since 2026-09-06T13:09:31.645492Z (7 days), latest 20 per workflow. Durations use API start to completion; p90 uses linear interpolation. Status buckets are success, failure/cancelled, and skipped.

## Workflow-level timing

| Workflow | Runs | Success | Failure/cancelled | Skipped |
|---|---:|---|---|---|
| CI — Validation | 20 (failure:5, success:11, cancelled:4) | n11 avg16.9m med15.2m p9025.1m min/max11.3/25.8m | n9 avg2.5m med2.3m p903.4m min/max1.2/5.2m | — |
| CodeQL Analysis | 20 (success:17, cancelled:2, failure:1) | n17 avg4.9m med6.7m p907.6m min/max0.3/8.3m | n3 avg2.2m med1.1m p904.2m min/max0.6/5.0m | — |
| License Checks | 20 (success:17, cancelled:3) | n17 avg5.4m med4.8m p9010.6m min/max0.4/10.9m | n3 avg4.5m med5.0m p907.4m min/max0.6/8.0m | — |
| Security Checks | 20 (success:18, cancelled:2) | n18 avg1.7m med1.5m p902.7m min/max1.1/4.1m | n2 avg1.6m med1.6m p901.7m min/max1.4/1.8m | — |
| PR Smoke Gate | 20 (success:18, cancelled:2) | n18 avg5.0m med0.9m p9012.5m min/max0.3/14.5m | n2 avg2.8m med2.8m p904.5m min/max0.6/4.9m | — |
| PR Preview Environment | 20 (success:18, failure:1, skipped:1) | n18 avg6.6m med0.6m p9019.0m min/max0.3/22.2m | n1 avg6.7m med6.7m p906.7m min/max6.7/6.7m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Validate Kustomize Overlays | 20 (skipped:6, success:12, cancelled:2) | n12 avg6.4m med6.5m p908.0m min/max3.9/9.6m | n2 avg6.1m med6.1m p907.2m min/max4.8/7.5m | n6 avg0.1m med0.1m p900.2m min/max0.0/0.3m |
| ZAP Baseline | 4 (success:1, failure:3) | n1 avg1.6m med1.6m p901.6m min/max1.6/1.6m | n3 avg0.5m med0.4m p900.5m min/max0.4/0.6m | — |
| Build Runtime Images | 20 (success:12, cancelled:4, skipped:4) | n12 avg12.5m med11.9m p9014.1m min/max10.2/19.6m | n4 avg12.0m med9.4m p9020.3m min/max4.8/24.3m | n4 avg0.7m med0.0m p901.8m min/max0.0/2.5m |
| Static Analysis Summary | 20 (success:9, cancelled:11) | n9 avg0.3m med0.3m p900.4m min/max0.3/0.4m | n11 avg1.2m med0.3m p903.6m min/max0.1/4.2m | — |
| Publish PR Runtime Images | 20 (success:20) | n20 avg3.0m med2.9m p903.8m min/max2.3/4.3m | — | — |

## Job-level timing

Exact API job names are retained; matrix expansions are represented as returned.

### CI — Validation

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Buf Generation | n8 avg0.3m med0.3m p900.4m min/max0.3/0.5m | n7 avg0.3m med0.3m p900.3m min/max0.3/0.4m | — |
| Build and Test (${{ matrix.service }}) | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n4 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — |
| Build and Test (account-service) | n5 avg4.2m med5.2m p905.7m min/max1.7/5.8m | — | — |
| Build and Test (automation-scripting-service) | n5 avg4.4m med6.2m p906.3m min/max1.4/6.3m | — | — |
| Build and Test (entity-management-service) | n5 avg4.2m med4.4m p905.7m min/max1.7/5.7m | — | — |
| Build and Test (game-design-service) | n5 avg4.1m med4.6m p905.8m min/max1.4/5.9m | — | — |
| Build and Test (game-logic-service) | n5 avg3.7m med3.9m p904.9m min/max1.4/5.0m | — | — |
| Build and Test (game-session-service) | n5 avg9.2m med10.9m p9011.5m min/max2.0/11.7m | — | — |
| Build and Test (logging-admin-service) | n5 avg3.9m med4.3m p905.1m min/max1.5/5.2m | — | — |
| Build and Test (social-groups-service) | n5 avg4.1m med4.4m p905.5m min/max1.5/5.5m | — | — |
| Build and Test (spring-cloud-gateway) | n5 avg4.3m med5.0m p905.3m min/max1.5/5.4m | — | — |
| Build and Test (tcp-proxy-service) | n5 avg7.5m med7.6m p909.3m min/max5.2/9.5m | — | — |
| Build and Test (world-management-service) | n5 avg4.0m med5.2m p905.4m min/max1.6/5.4m | — | — |
| Detect CI-Relevant Changes | n10 avg0.2m med0.2m p900.3m min/max0.0/0.3m | n8 avg0.2m med0.2m p900.3m min/max0.0/0.3m | — |
| Dev Tool Contract Checks | n8 avg10.1m med10.5m p9011.0m min/max8.0/11.8m | n8 avg1.1m med0.6m p902.5m min/max0.0/4.4m | — |
| Dockerfile Lint | n9 avg0.3m med0.3m p900.3m min/max0.0/0.3m | n7 avg0.3m med0.3m p900.3m min/max0.2/0.3m | — |
| Flyway Migration Sanity Checks | n8 avg1.0m med0.8m p901.5m min/max0.7/1.6m | n8 avg0.8m med0.8m p901.1m min/max0.0/1.4m | — |
| Frontend Checks | n8 avg0.6m med0.9m p901.1m min/max0.0/1.2m | n8 avg0.7m med0.9m p901.1m min/max0.0/1.2m | — |
| GitHub Workflow Lint | n8 avg0.6m med0.6m p900.6m min/max0.5/0.6m | n8 avg0.5m med0.6m p900.6m min/max0.0/0.7m | — |
| Helm Chart Lint | n9 avg0.2m med0.3m p900.3m min/max0.0/0.4m | n7 avg0.3m med0.3m p900.4m min/max0.2/0.4m | — |
| Helm Render Validation | n8 avg0.3m med0.3m p900.3m min/max0.2/0.4m | n8 avg0.3m med0.3m p900.4m min/max0.0/0.4m | — |
| Metrics Cardinality Policy Check | n9 avg0.2m med0.2m p900.3m min/max0.0/0.3m | n8 avg0.2m med0.2m p900.3m min/max0.0/0.3m | — |
| Python Script Validation | n8 avg0.3m med0.3m p900.3m min/max0.2/0.3m | n8 avg0.3m med0.3m p900.3m min/max0.0/0.4m | — |
| ShellCheck | n8 avg0.5m med0.5m p900.6m min/max0.4/0.7m | n8 avg0.4m med0.5m p900.6m min/max0.0/0.6m | — |
| Validate Documentation | n8 avg0.6m med0.8m p900.9m min/max0.0/0.9m | n8 avg0.8m med0.9m p901.0m min/max0.0/1.0m | — |
| Validation Gate | n11 avg3.9m med0.2m p9011.3m min/max0.1/19.2m | n8 avg0.4m med0.2m p900.8m min/max0.1/2.2m | — |
| Validation Summary | n8 avg0.3m med0.2m p900.4m min/max0.1/0.8m | n7 avg0.2m med0.2m p900.3m min/max0.2/0.3m | — |
| YAML Lint | n9 avg0.3m med0.3m p900.4m min/max0.0/0.5m | n7 avg0.4m med0.4m p900.4m min/max0.3/0.4m | — |
| gRPC Transport Configuration Sanity Checks | n8 avg1.0m med0.8m p901.4m min/max0.7/1.6m | n7 avg1.1m med0.9m p901.7m min/max0.7/1.8m | — |
| github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (Validation Summary)' || 'Validation Summary' | n2 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — | — |

## Step timing highlights
Top five step/action names by average measured duration for CI — Validation; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| 🧪 Validate dev tool contracts | n8 avg576.9s med601.5s | n7 avg47.7s med6.0s | — | 677.0s (run 34757747153) |
| 🧪 Run Gradle Checks | n55 avg245.3s med254.0s | — | — | 652.0s (run 34757747153) |
| Preserve successful required gate on metadata-only edit | n11 avg224.2s med0.0s | n8 avg13.9s med0.0s | — | 1140.0s (run 34748724773) |
| 🧪 Check Flyway Migration Versions | n8 avg32.4s med18.5s | n7 avg23.9s med20.0s | — | 69.0s (run 34756730754) |
| 🧪 Check gRPC Transport Configuration | n8 avg28.1s med21.0s | n7 avg35.0s med21.0s | — | 73.0s (run 34756667861) |

Slowest individual measured step: **Preserve successful required gate on metadata-only edit** — 1140.0s (run 34748724773; job Validation Gate).

### CodeQL Analysis

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| CodeQL Analysis (${{ matrix.language }}) | n5 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n2 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — |
| CodeQL Analysis (java) | n11 avg6.5m med6.5m p907.4m min/max5.5/7.7m | n1 avg4.4m med4.4m p904.4m min/max4.4/4.4m | — |
| CodeQL Analysis (javascript-typescript) | n5 avg1.2m med1.3m p901.3m min/max1.1/1.3m | — | — |
| CodeQL Gate | n17 avg0.2m med0.2m p900.4m min/max0.1/0.6m | n3 avg0.3m med0.2m p900.5m min/max0.2/0.5m | — |
| Detect CodeQL-Relevant Changes | n14 avg0.3m med0.3m p900.4m min/max0.2/0.4m | n2 avg0.1m med0.1m p900.3m min/max0.0/0.3m | — |

## Step timing highlights
Top five step/action names by average measured duration for CodeQL Analysis; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| 📊 Perform CodeQL Analysis | n16 avg251.6s med320.5s | n1 avg203.0s med203.0s | — | 422.0s (run 34756730793) |
| 🔍 Initialize CodeQL (Java PR Fast Path) | n16 avg12.3s med14.0s | n1 avg23.0s med23.0s | — | 25.0s (run 34755721702) |
| Pre Harden runner | n47 avg6.6s med6.0s | n5 avg5.0s med5.0s | — | 10.0s (run 34757747148) |
| 🔍 Initialize CodeQL | n16 avg5.0s med0.0s | n1 avg0.0s med0.0s | — | 19.0s (run 34757617044) |
| Checkout code | n14 avg4.8s med5.0s | n1 avg5.0s med5.0s | — | 5.0s (run 34757941673) |

Slowest individual measured step: **📊 Perform CodeQL Analysis** — 422.0s (run 34756730793; job CodeQL Analysis (java)).

### License Checks

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Detect License-Relevant Changes | n14 avg0.3m med0.3m p900.4m min/max0.0/0.4m | n3 avg0.2m med0.3m p900.3m min/max0.0/0.3m | — |
| License Gate | n17 avg0.4m med0.2m p900.5m min/max0.1/3.3m | n3 avg0.3m med0.2m p900.4m min/max0.2/0.5m | — |
| License Gate (Gradle) | n14 avg5.1m med7.4m p909.7m min/max0.0/10.2m | n2 avg5.5m med5.5m p906.4m min/max4.4/6.6m | — |
| License Gate (NPM) | n12 avg1.4m med0.0m p904.2m min/max0.0/4.2m | n2 avg2.1m med2.1m p903.8m min/max0.0/4.2m | — |

## Step timing highlights
Top five step/action names by average measured duration for License Checks; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Run ORT License Scan | n12 avg405.2s med489.0s | n3 avg272.7s med233.0s | — | 579.0s (run 34754906040) |
| Preserve successful required gate on metadata-only edit | n17 avg12.1s med0.0s | n3 avg5.3s med0.0s | — | 187.0s (run 34748724984) |
| ⚙️ Set Up Gradle | n12 avg6.0s med5.0s | n3 avg5.7s med6.0s | — | 11.0s (run 34757747199) |
| Pre Harden runner | n42 avg6.0s med5.0s | n8 avg6.2s med6.5s | — | 11.0s (run 34756741524) |
| Post ⚙️ Set Up Gradle | n12 avg5.8s med5.5s | n3 avg2.7s med0.0s | — | 8.0s (run 34757747199) |

Slowest individual measured step: **Run ORT License Scan** — 579.0s (run 34754906040; job License Gate (Gradle)).

### Security Checks

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Detect Security-Relevant Changes | n17 avg0.2m med0.2m p900.3m min/max0.0/0.6m | n2 avg0.2m med0.2m p900.3m min/max0.0/0.3m | — |
| Secret Compliance Validation | n2 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — | — |
| Secret Compliance Validation / Secret Compliance Validation | n14 avg0.3m med0.3m p900.3m min/max0.2/0.3m | n1 avg0.2m med0.2m p900.2m min/max0.2/0.2m | — |
| Security Gate | n18 avg0.3m med0.2m p900.5m min/max0.1/1.1m | n2 avg0.8m med0.8m p901.2m min/max0.3/1.3m | — |
| Security Summary | n15 avg0.2m med0.2m p900.2m min/max0.0/0.2m | n1 avg0.2m med0.2m p900.2m min/max0.2/0.2m | — |
| Trivy Filesystem Scan | n17 avg0.5m med0.6m p900.7m min/max0.0/0.8m | n2 avg0.4m med0.4m p900.7m min/max0.0/0.8m | — |

## Step timing highlights
Top five step/action names by average measured duration for Security Checks; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Pre Harden runner | n74 avg6.3s med6.0s | n6 avg7.2s med7.0s | — | 12.0s (run 34757747289) |
| Preserve successful required gate on metadata-only edit | n18 avg5.3s med0.0s | n2 avg31.0s med31.0s | — | 62.0s (run 34756679651) |
| 💾 Cache Trivy DB | n14 avg4.4s med5.0s | n1 avg2.0s med2.0s | — | 6.0s (run 34757617164) |
| 💾 Cache Web Client Dependencies | n14 avg3.2s med3.5s | n1 avg1.0s med1.0s | — | 5.0s (run 34757617164) |
| Post 💾 Cache Trivy DB | n14 avg2.6s med2.0s | n1 avg6.0s med6.0s | — | 6.0s (run 34756667966) |

Slowest individual measured step: **Preserve successful required gate on metadata-only edit** — 62.0s (run 34756679651; job Security Gate).

### PR Smoke Gate

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Detect Smoke-Relevant Changes | n15 avg0.2m med0.2m p900.3m min/max0.0/0.3m | n2 avg0.1m med0.1m p900.3m min/max0.0/0.3m | — |
| Smoke Gate | n18 avg4.3m med0.3m p9011.7m min/max0.2/12.0m | n2 avg2.4m med2.4m p903.9m min/max0.5/4.3m | — |
| Smoke Summary | n14 avg0.2m med0.2m p900.2m min/max0.2/0.3m | n1 avg0.2m med0.2m p900.2m min/max0.2/0.2m | — |
| Smoke Summary (Pending) | n17 avg0.2m med0.2m p900.3m min/max0.0/0.3m | n2 avg0.1m med0.1m p900.2m min/max0.0/0.3m | — |
| github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (Smoke Summary)' || 'Smoke Summary' | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — | — |

## Step timing highlights
Top five step/action names by average measured duration for PR Smoke Gate; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Track full-stack smoke result from Build Runtime Images | n18 avg218.4s med0.0s | n2 avg124.5s med124.5s | — | 710.0s (run 34755721712) |
| Preserve successful required gate on metadata-only edit | n18 avg25.1s med0.0s | n2 avg8.0s med8.0s | — | 434.0s (run 34748724753) |
| Pre Harden runner | n60 avg6.8s med7.0s | n5 avg6.4s med7.0s | — | 12.0s (run 34755721712) |
| Set up job | n60 avg1.6s med1.5s | n5 avg2.0s med2.0s | — | 4.0s (run 34754906153) |
| 💬 Publish Smoke Summary Comment | n14 avg1.5s med1.0s | n1 avg3.0s med3.0s | — | 3.0s (run 34757543737) |

Slowest individual measured step: **Track full-stack smoke result from Build Runtime Images** — 710.0s (run 34755721712; job Smoke Gate).

### PR Preview Environment

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Deploy Preview Release | n16 avg4.8m med0.2m p9017.4m min/max0.0/19.6m | n1 avg6.3m med6.3m p906.3m min/max6.3/6.3m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Destroy Preview Release | n13 avg0.0m med0.0m p900.0m min/max0.0/0.3m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m | — |
| Preview Plan | n18 avg0.3m med0.2m p900.3m min/max0.2/0.3m | n1 avg0.2m med0.2m p900.2m min/max0.2/0.2m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |

## Step timing highlights
Top five step/action names by average measured duration for PR Preview Environment; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Wait for preview runtime images | n8 avg421.4s med399.5s | n1 avg311.0s med311.0s | — | 946.0s (run 34748609330) |
| Deploy preview release | n8 avg86.6s med132.5s | n1 avg0.0s med0.0s | — | 150.0s (run 34746750939) |
| Reset preview namespace for clean deploy | n8 avg18.5s med22.0s | n1 avg32.0s med32.0s | — | 35.0s (run 34748609330) |
| Smoke hosted preview over TCP | n8 avg8.6s med13.0s | n1 avg0.0s med0.0s | — | 15.0s (run 34757747182) |
| Pre Harden runner | n18 avg6.5s med7.0s | n1 avg5.0s med5.0s | — | 10.0s (run 34757362466) |

Slowest individual measured step: **Wait for preview runtime images** — 946.0s (run 34748609330; job Deploy Preview Release).

### Validate Kustomize Overlays

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (validate-overlays)' || 'validate-overlays' | — | — | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| validate-overlays | n12 avg6.0m med6.4m p907.0m min/max3.8/8.0m | n2 avg6.1m med6.1m p907.1m min/max4.8/7.3m | — |

## Step timing highlights
Top five step/action names by average measured duration for Validate Kustomize Overlays; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| 🏗️ Build service jars for overlay validation | n12 avg274.5s med286.5s | n2 avg326.0s med326.0s | — | 416.0s (run 34756648048) |
| 🏗️ Build PR images for overlay validation | n12 avg39.8s med39.5s | n2 avg0.0s med0.0s | — | 51.0s (run 34746750908) |
| ✅ Validate overlays and image availability | n12 avg12.2s med12.5s | n2 avg0.0s med0.0s | — | 16.0s (run 34755721705) |
| ⚙️ Set Up Gradle | n12 avg6.9s med7.0s | n2 avg6.5s med6.5s | — | 11.0s (run 34755721705) |
| Pre Harden runner | n12 avg6.3s med5.5s | n2 avg4.5s med4.5s | — | 9.0s (run 34754906053) |

Slowest individual measured step: **🏗️ Build service jars for overlay validation** — 416.0s (run 34756648048; job validate-overlays).

### ZAP Baseline

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| ZAP Baseline (web-client) | n1 avg1.5m med1.5m p901.5m min/max1.5/1.5m | n3 avg0.4m med0.4m p900.5m min/max0.3/0.5m | — |

## Step timing highlights
Top five step/action names by average measured duration for ZAP Baseline; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| 🔎 Run OWASP ZAP Baseline | n1 avg68.0s med68.0s | n3 avg0.0s med0.0s | — | 68.0s (run 34756730782) |
| 📦 Install Frontend Dependencies | n1 avg7.0s med7.0s | n3 avg4.7s med5.0s | — | 7.0s (run 34756730782) |
| Pre Harden runner | n1 avg5.0s med5.0s | n3 avg7.3s med6.0s | — | 11.0s (run 34352309010) |
| ⬇️ Checkout Code | n1 avg2.0s med2.0s | n3 avg3.0s med3.0s | — | 3.0s (run 34541416361) |
| Post ⚙️ Set Up Node | n1 avg2.0s med2.0s | n3 avg0.0s med0.0s | — | 2.0s (run 34756730782) |

Slowest individual measured step: **🔎 Run OWASP ZAP Baseline** — 68.0s (run 34756730782; job ZAP Baseline (web-client)).

### Build Runtime Images

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Build Runtime Base Image | n5 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Build Runtime Images (${{ matrix.service }}) | n6 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Build Trusted Hosted Identity Controller | n5 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n4 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Derive Runtime Image Metadata | n12 avg0.2m med0.2m p900.2m min/max0.1/0.3m | n4 avg6.2m med0.3m p9017.1m min/max0.2/24.3m | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| PR Full-Stack Smoke | n12 avg11.5m med11.3m p9013.7m min/max9.8/14.2m | n4 avg5.6m med6.0m p909.4m min/max0.0/10.2m | n2 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Publish Trusted Hosted Identity Controller | n7 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n1 avg0.0m med0.0m p900.0m min/max0.0/0.0m |
| Published Runtime Full-Stack Smoke | n8 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n3 avg0.0m med0.0m p900.0m min/max0.0/0.0m | n2 avg0.0m med0.0m p900.0m min/max0.0/0.0m |

## Step timing highlights
Top five step/action names by average measured duration for Build Runtime Images; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Build local PR runtime images | n12 avg394.5s med383.5s | n3 avg387.7s med427.0s | — | 531.0s (run 34738304649) |
| Run credential-free full-stack smoke | n12 avg144.8s med141.5s | n3 avg33.3s med0.0s | — | 165.0s (run 34748609497) |
| Export fixed-tag preview image artifact | n12 avg48.6s med48.5s | n3 avg0.0s med0.0s | — | 57.0s (run 34746577805) |
| Upload preview image artifact | n12 avg41.8s med43.5s | n3 avg0.0s med0.0s | — | 49.0s (run 34754906198) |
| Build controller image for credential-free local validation | n11 avg19.4s med19.0s | n3 avg0.0s med0.0s | — | 23.0s (run 34739413418) |

Slowest individual measured step: **Build local PR runtime images** — 531.0s (run 34738304649; job PR Full-Stack Smoke).

### Static Analysis Summary

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Static Analysis Summary | n9 avg0.2m med0.2m p900.3m min/max0.2/0.3m | n11 avg1.2m med0.2m p903.6m min/max0.0/4.1m | — |

## Step timing highlights
Top five step/action names by average measured duration for Static Analysis Summary; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Pre Harden runner | n9 avg6.6s med7.0s | n7 avg4.9s med5.0s | — | 10.0s (run 34756779696) |
| 💬 Publish Static Analysis Summary Comment | n9 avg1.8s med2.0s | n7 avg97.4s med81.0s | — | 237.0s (run 34758249982) |
| Set up job | n9 avg1.7s med2.0s | n10 avg1.1s med1.0s | — | 3.0s (run 34757984698) |
| Post Harden runner | n9 avg1.2s med1.0s | n7 avg1.3s med1.0s | — | 2.0s (run 34758428268) |
| Complete job | n9 avg0.2s med0.0s | n7 avg0.0s med0.0s | — | 1.0s (run 34757109254) |

Slowest individual measured step: **💬 Publish Static Analysis Summary Comment** — 237.0s (run 34758249982; job Static Analysis Summary).

### Publish PR Runtime Images

| Job/check | Success (avg/median/p90/min/max) | Failure/cancelled (avg/median/p90/min/max) | Skipped (avg/median/p90/min/max) |
|---|---|---|---|
| Publish Fixed PR Image Tags | n20 avg2.9m med2.9m p903.6m min/max2.3/4.1m | — | — |

## Step timing highlights
Top five step/action names by average measured duration for Publish PR Runtime Images; full named-step records and all status splits are in raw JSON.

| Step/action | Success count, avg, median | Failure/cancelled count, avg, median | Skipped count, avg, median | Slowest measured sample |
|---|---|---|---|
| Publish fixed PR image tags | n20 avg96.0s med96.0s | — | — | 124.0s (run 34748927225) |
| Download successful PR image artifact | n20 avg37.4s med26.5s | — | — | 85.0s (run 34730314062) |
| Load fixed-tag images | n20 avg28.1s med25.5s | — | — | 38.0s (run 34755359267) |
| Pre Harden runner | n20 avg6.3s med6.0s | — | — | 12.0s (run 34726385508) |
| Set up job | n20 avg1.8s med2.0s | — | — | 4.0s (run 34739075012) |

Slowest individual measured step: **Publish fixed PR image tags** — 124.0s (run 34748927225; job Publish Fixed PR Image Tags).
