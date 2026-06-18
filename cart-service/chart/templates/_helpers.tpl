{{- define "carts.name" -}}{{- default "carts" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "carts.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "carts.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "carts.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "carts.labels" -}}
helm.sh/chart: {{ include "carts.chart" . }}
{{ include "carts.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "carts.selectorLabels" -}}
app.kubernetes.io/name: {{ include "carts.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "carts.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "carts.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}
