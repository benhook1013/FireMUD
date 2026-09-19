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

{{- define "firemud.gatewayWsServerSecretName" -}}
{{- printf "%s-gateway-internal-ws" .Release.Name -}}
{{- end -}}

{{- define "firemud.gatewayWsClientSecretName" -}}
{{- printf "%s-tcp-proxy-bridge" .Release.Name -}}
{{- end -}}

{{- define "firemud.gatewayWsServerEnv" -}}
{{- $root := .root -}}
{{- $preview := $root.Values.preview | default (dict) -}}
{{- $prNumber := get $preview "prNumber" -}}
{{- if or (not (hasKey $preview "prNumber")) (and (empty $prNumber) (ne (toString $prNumber) "0")) -}}
{{- fail "preview.prNumber is required when Gateway WebSocket TLS is enabled" -}}
{{- end -}}
{{- $prNumberText := toString $prNumber -}}
{{- if and (ne $prNumberText "0") (not (regexMatch "^[1-9][0-9]*$" $prNumberText)) -}}
{{- fail "preview.prNumber must be 0 or a positive integer when Gateway WebSocket TLS is enabled" -}}
{{- end -}}
{{- $gatewayWsTls := $root.Values.previewStack.gatewayWsTls | default (dict) -}}
{{- $trustEnvironment := get $gatewayWsTls "trustEnvironment" -}}
{{- if or (empty $trustEnvironment) (not (has $trustEnvironment (list "pr-preview" "dev-demo-cluster"))) -}}
{{- fail "previewStack.gatewayWsTls.trustEnvironment must be pr-preview or dev-demo-cluster when Gateway WebSocket TLS is enabled" -}}
{{- end -}}
{{- $expectedTrustEnvironment := ternary "dev-demo-cluster" "pr-preview" (eq $prNumberText "0") -}}
{{- if ne $trustEnvironment $expectedTrustEnvironment -}}
{{- fail (printf "previewStack.gatewayWsTls.trustEnvironment must be %s for preview.prNumber %s" $expectedTrustEnvironment $prNumberText) -}}
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
  value: {{ $trustEnvironment | quote }}
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
