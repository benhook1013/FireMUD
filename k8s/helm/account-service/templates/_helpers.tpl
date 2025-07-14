{{/* Common helper templates for FireMUD Helm charts */}}
{{- define "firemud.labels" -}}
app: {{ .Chart.Name }}
{{- end -}}

{{- define "firemud.resources" -}}
requests:
  cpu: "200m"
  memory: "256Mi"
limits:
  cpu: "400m"
  memory: "512Mi"
{{- end -}}
