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

{{- define "firemud.certificateIdentityMode" -}}
{{- $certificateIdentity := .Values.previewStack.certificateIdentity | default (dict) -}}
{{- $mode := default "standalone" $certificateIdentity.mode -}}
{{- if or (eq $mode "standalone") (eq $mode "hosted-controller") -}}
{{- $mode -}}
{{- else -}}
{{- fail (printf "previewStack.certificateIdentity.mode must be standalone or hosted-controller (got %q)" $mode) -}}
{{- end -}}
{{- end -}}

{{- define "firemud.hostedControllerMode" -}}
{{- eq (include "firemud.certificateIdentityMode" . | trim) "hosted-controller" -}}
{{- end -}}

{{- define "firemud.gatewayWsServerSecretName" -}}
{{- printf "%s-gateway-internal-ws" .Release.Name -}}
{{- end -}}

{{- define "firemud.gatewayWsClientSecretName" -}}
{{- printf "%s-tcp-proxy-bridge" .Release.Name -}}
{{- end -}}

{{- define "firemud.telnetTlsSecretName" -}}
{{- $telnetTls := .Values.previewStack.telnetTls | default (dict) -}}
{{- if $telnetTls.enabled -}}
{{- if eq (include "firemud.hostedControllerMode" . | trim) "true" -}}
{{- printf "%s-telnet-tls" .Release.Name -}}
{{- else -}}
{{- $secretName := required "previewStack.telnetTls.secretName is required when Telnet TLS is enabled" $telnetTls.secretName -}}
{{- if and (ne $secretName "__TELNET_TLS_SECRET_NAME__") (not (hasSuffix "-telnet-tls" $secretName)) -}}
{{- fail "previewStack.telnetTls.secretName must end with -telnet-tls when Telnet TLS is enabled" -}}
{{- end -}}
{{- $secretName -}}
{{- end -}}
{{- else -}}
{{- $telnetTls.secretName | default "" -}}
{{- end -}}
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

{{- define "firemud.gatewayWsServerEnv" -}}
{{- $root := .root -}}
{{- $preview := $root.Values.preview | default (dict) -}}
{{- $prNumber := get $preview "prNumber" -}}
{{- if or (not (hasKey $preview "prNumber")) (and (empty $prNumber) (ne (toString $prNumber) "0")) -}}
{{- fail "preview.prNumber is required when Gateway WebSocket TLS is enabled" -}}
{{- end -}}
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED
  value: "true"
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_BIND_ADDRESS
  value: "0.0.0.0"
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT
  value: {{ .targetPort | quote }}
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_CERT_CHAIN_PATH
  value: /gateway-ws-server-tls/tls.crt
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_PRIVATE_KEY_PATH
  value: /gateway-ws-server-tls/tls.key
- name: FIREMUD_GATEWAY_TCP_PROXY_TLS_CLIENT_CA_PATH
  value: /gateway-ws-server-tls/ca.crt
- name: FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT
  value: {{ ternary "dev-demo-cluster" "pr-preview" (eq (toString $prNumber) "0") }}
- name: FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE
  value: production_uri
- name: FIREMUD_GATEWAY_TCP_PROXY_TRUST_URI_SAN
  value: {{ printf "spiffe://firemud/ns/%s/sa/tcp-proxy-service" $root.Release.Namespace | quote }}
{{- end -}}

{{- define "firemud.gatewayWsClientEnv" -}}
{{- $root := .root -}}
- name: GATEWAY_WS_URL
  value: {{ printf "wss://spring-cloud-gateway-mtls.%s.svc.cluster.local:%v/ws/game" $root.Release.Namespace .servicePort | quote }}
- name: FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH
  value: /gateway-ws-client-tls/tls.crt
- name: FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH
  value: /gateway-ws-client-tls/tls.key
- name: FIREMUD_GATEWAY_WS_CA_CERT_PATH
  value: /gateway-ws-client-tls/ca.crt
{{- end -}}
