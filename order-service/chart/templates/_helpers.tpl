{{- define "orders.name" -}}{{- default "orders" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "orders.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "orders.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "orders.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "orders.labels" -}}
helm.sh/chart: {{ include "orders.chart" . }}
{{ include "orders.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "orders.selectorLabels" -}}
app.kubernetes.io/name: {{ include "orders.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "orders.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "orders.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}
