# Automation Scripting Service

Design documentation lives at:
[📄 Automation Scripting Service Design](../../design/architecture/microservices/automation-scripting-service/README.md)

## Fairness Quotas

`ScriptQuotaService` limits how many times a script may execute within a
configurable window. Counters are stored in Redis using keys of the form
`script_quota:{tenantId}:{scriptId}`. When the quota is exceeded the event is
ignored and `script_quota_denied_total` is incremented.
`sagas.active` metric.

This README is a stub. **Do not place design details here.**
