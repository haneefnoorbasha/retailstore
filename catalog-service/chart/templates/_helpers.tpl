{{- define "catalog.name" -}}{{- default "catalog" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "catalog.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "catalog.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "catalog.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "catalog.labels" -}}
helm.sh/chart: {{ include "catalog.chart" . }}
{{ include "catalog.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "catalog.selectorLabels" -}}
app.kubernetes.io/name: {{ include "catalog.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "catalog.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "catalog.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}
