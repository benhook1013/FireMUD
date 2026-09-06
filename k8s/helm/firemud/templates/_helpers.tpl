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

{{- define "firemud.hostedControllerMode" -}}
{{- eq (default "standalone" .Values.previewStack.certificateIdentity.mode) "hosted-controller" -}}
{{- end -}}

{{- define "firemud.ingressTlsSecretName" -}}
{{- default (printf "%s-tls" .Release.Name) .Values.previewStack.ingress.tlsSecretName -}}
{{- end -}}

{{- define "firemud.telnetTlsSecretName" -}}
{{- default (printf "%s-telnet-tls" .Release.Name) .Values.previewStack.telnetTls.secretName -}}
{{- end -}}
