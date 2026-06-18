{{- define "checkout.name" -}}{{- default "checkout" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "checkout.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "checkout.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "checkout.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "checkout.labels" -}}
helm.sh/chart: {{ include "checkout.chart" . }}
{{ include "checkout.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "checkout.selectorLabels" -}}
app.kubernetes.io/name: {{ include "checkout.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "checkout.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "checkout.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}
