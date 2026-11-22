{{- define "framework-runner.admit" -}}
{{- if .Values.deployment.enabled -}}
{{- if not (regexMatch "^[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}$" .Values.image) -}}{{ fail "immutable accepted image binding required" }}{{- end -}}
{{- range $name, $value := .Values.run -}}{{- if not (regexMatch "^[a-z0-9][a-z0-9-]{0,39}$" $value) -}}{{ fail "run identity binding required" }}{{- end -}}{{- end -}}
{{- if not (regexMatch "^[0-9a-f]{64}$" .Values.pins.frameworkSha256) -}}{{ fail "accepted framework pin required" }}{{- end -}}
{{- if not (regexMatch "^[0-9a-f]{64}$" .Values.pins.handoffSha256) -}}{{ fail "independent handoff pin required" }}{{- end -}}
{{- if and (eq .Values.deployment.phase "collect") (not (regexMatch "^[0-9a-f]{64}$" .Values.pins.collectionSpecSha256)) -}}{{ fail "independent collection spec required" }}{{- end -}}
{{- if or (not .Values.storage.inputClaim) (not .Values.storage.outputClaim) (eq .Values.storage.inputClaim .Values.storage.outputClaim) (not .Values.storage.exclusive) (ne (int .Values.storage.writableUid) 65532) -}}{{ fail "exclusive preprovisioned writable retained storage required" }}{{- end -}}
{{- if ne .Values.transport "retained_source_only" -}}{{ fail "full transport NOT_IMPLEMENTED; explicitly bind retained_source_only" }}{{- end -}}
{{- if or .Values.secretRefs (ne .Values.secretMaterializer "none") -}}{{ fail "secret materializer NOT_IMPLEMENTED" }}{{- end -}}
{{- end -}}
{{- end -}}
