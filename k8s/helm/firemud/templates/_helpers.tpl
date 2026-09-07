{{/* Shared helper templates for the FireMUD preview chart. */}}
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

{{- define "firemud.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default .Release.Name .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "firemud.commonLabels" -}}
app.kubernetes.io/name: firemud
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "firemud.serviceName" -}}
{{- .name -}}
{{- end -}}

{{- define "firemud.grpcSecretName" -}}
{{- default "firemud-grpc-tls" .Values.previewStack.grpcTls.secretName -}}
{{- end -}}

{{- define "firemud.grpcTlsEnv" -}}
- name: FIREMUD_GRPC_CERT_CHAIN_PATH
  value: /tls/client.crt
- name: FIREMUD_GRPC_PRIVATE_KEY_PATH
  value: /tls/client.key
- name: FIREMUD_GRPC_CA_CERT_PATH
  value: /tls/ca.crt
{{- end -}}

{{- define "firemud.telnetTlsEnv" -}}
- name: TCP_PROXY_TLS_ENABLED
  value: "true"
- name: TCP_PROXY_TLS_CERT
  value: /telnet-tls/tls.crt
- name: TCP_PROXY_TLS_KEY
  value: /telnet-tls/tls.key
{{- end -}}

{{- define "firemud.telnetTlsModeEnv" -}}
- name: TCP_PROXY_TELNET_MODE
  value: DIRECT_TLS
{{- end -}}
