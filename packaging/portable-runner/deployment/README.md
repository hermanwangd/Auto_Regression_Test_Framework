# Portable runner deployment source candidate

Disabled-by-default Helm chart and Ansible role/playbook for the existing framework runner. SUT services and executable product testware are external. Cross-service suites remain in an existing owner product repository at tests/sit; service cases remain in their own repositories. Immutable bundle assembly preserves repo/commit/path/testID and input hashes. ProductKnowledge holds curated relations and immutable references; Cognee is a governed retrieval index.

## Operational status

This source package is not an operationally accepted K3s/VM deployment. Real SSH transfer and full Kubernetes artifact retrieval are not connected. The current managed executor lacks the process-children observation interface: corrected supervision blocks before launching a JVM. Ansible local syntax/staging and labeled unit models have been tested; they do not establish native VM supervision or retrieval.

Actual Helm lint/template have not run. The official binary URL failed CONNECT403; a reviewed official-source attempt verified Helm v3.17.3 commit e4da49785aa6e6ee2b86efd5dd9e43400318262b and module checksum, then stopped on a dependency CDN403. No mirror or network change was used. The chart currently declares Kubernetes >=1.29 and <1.33; the operator-reported Kimi1.37.2 lies outside that range. No implicit range expansion is approved.

Framework runner source and builder are published; the verified local OCI archive has not been delivered or published. A legal protected CD bundle still needs a real readiness-plugin JAR and trusted deployed-SUT version binding. The external synthetic inputs are not a ready CD closure. No company SIT, native Swarm agent, live Cognee, ADO or hypervisor VM execution is established here.

## Use and review

Read [operations](docs/operations.md) and [coverage scope](docs/coverage.md) before proposing any deployment. Python3.12, independently provisioned Ansible2.18.6 and Helm3.17.3 were the tooling targets; this package does not install them. Source checks target Linux/nonroot with the documented process-observation interfaces. From this directory, run `env -u DEPLOYMENT_RESIDUAL_BASELINE python3 -B -m unittest discover -s tests -v`. The residual-baseline selector must be unset in the source-only package; its historical comparison tree is intentionally excluded. Platform-dependent controls may explicitly skip or block; inspect results rather than treating them as remote acceptance.

The source selection excludes task-only installers, freezer/reproduction tools, generated reports and local historical logs. Review JSON schemas and actual CLI help for bindings; never copy test-example digests into deployment values. No credentials, company bindings or actual product inventory are supplied.

`tests/review_regressions.py --source current` uses the current source. Its `--source original36` comparison mode requires separately supplied historical fixtures and is unavailable in this source-only kit.
