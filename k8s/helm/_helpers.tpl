{{/* Common helper templates for FireMUD Helm charts */}}
{{- define "firemud.labels" -}}
app: {{ .Chart.Name }}
{{- end -}}

{{- define "firemud.resources" -}}
{{- if .Values.resources }}
{{ toYaml .Values.resources }}
{{- else }}
requests:
  cpu: "200m"
  memory: "256Mi"
limits:
  cpu: "400m"
  memory: "512Mi"
{{- end }}
{{- end -}}

{{- define "firemud.fullname" -}}
{{ printf "%s-%s" .Release.Name .Chart.Name }}
{{- end -}}

{{- define "firemud.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{ .Values.serviceAccount.name | default (include "firemud.fullname" .) }}
{{- else }}
{{ .Values.serviceAccount.name | default "default" }}
{{- end }}
{{- end -}}
