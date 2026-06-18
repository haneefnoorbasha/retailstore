{{- define "experience.name" -}}{{- default "experience" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "experience.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "experience.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "experience.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "experience.labels" -}}
helm.sh/chart: {{ include "experience.chart" . }}
{{ include "experience.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "experience.selectorLabels" -}}
app.kubernetes.io/name: {{ include "experience.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "experience.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "experience.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}
