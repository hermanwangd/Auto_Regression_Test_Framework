# Azure DevOps Services: local CI and post-deployment SIT

Azure DevOps Services YAML is the primary integration target. The existing GitHub workflows remain secondary public verification examples. These templates reuse the existing framework and public-product harnesses; they do not implement another runner.

## Local tests in CI

Create an Azure pipeline pointing at [azure-pipelines.yml](../../azure-pipelines.yml). Its Linux hosted job selects JDK 17, verifies/packages the framework and Swarm module, runs the Python gate/harness boundary tests, and exercises SQLite C CLI and native Go NATS through the packaged module and framework. The two committed local-policy digests are explicit pins. The current build supplies framework/module digests under that reviewed policy; this is local verification authority, not SIT approval.

JUnit XML is published to Azure's Tests view. Canonical evidence and harness receipts are copied into an explicit artifact staging directory and published even on failure. Missing test results or failed tests fail the job. Input caches, downloaded runtimes and raw process logs are excluded from the artifact. Jobs have bounded time and JVM memory; the hosted job owns disposable test resources.

The root YAML includes push triggers and a GitHub-connected PR trigger. For **Azure Repos Git**, configure a target-branch **Build validation policy**; YAML `pr` does not configure Azure Repos PR validation. Repository/service connections, pipeline authorization and branch policies remain owner configuration.

## SIT tests in CD

Use [pipelines/azure/sit-postdeploy.yml](../../pipelines/azure/sit-postdeploy.yml) as a stage template after an existing, successful deployment stage. It does not deploy the product. This inclusion example assumes the caller already defines a real `DeploySit` stage that publishes the approved bundle in the same pipeline run:

```yaml
# Append to the owner's existing stages list; DeploySit must already exist.
- template: pipelines/azure/sit-postdeploy.yml
  parameters:
    deploymentStage: DeploySit
    environmentName: ExistingProtectedSit
    deploymentArtifact: approved-sit-bundle
    handoffSha256: '$(ApprovedSitHandoffSha256)'
```

Replace the example stage/environment names with existing owner-managed resources. Configure Environment approvals/checks in Azure before use; naming an Environment in YAML does not establish its protection. `ApprovedSitHandoffSha256` must be supplied by protected, independent review configuration. **Do not compute this approval digest from the downloaded bundle.** An absent, malformed or mismatched digest cannot pass the existing gate. The deployment owner supplies actual deployed revision, freshness, permissions and shared-environment ownership.

For a product pipeline in another repository, declare an authorized repository resource pinned to the reviewed framework revision, include the template with `@<alias>`, and pass `frameworkRepository: <alias>`. Template inclusion alone does not check out its scripts. The template explicitly checks out that repository to `s/product-test-framework` and runs the gate from that directory. Keep the repository resource ref pinned, including when this template is consumed from the framework's own repository.

The deployment job disables automatic artifact downloads, validates one explicit artifact name, and downloads only that artifact from the **current run**. Bundle/evidence paths include the build ID and job attempt to separate retries and self-hosted runs. It invokes [run-sit-postdeploy.py](../../scripts/cd/run-sit-postdeploy.py) with the protected digest, then collects gate metadata and validated canonical evidence. The input bundle, pinned JAR snapshot and raw child output are excluded. Publishing artifacts never turns a failed gate into success; `collection.json` describes collection only.

The default pool is a Microsoft-hosted Linux agent with preinstalled JDK 17. If SIT requires private network reachability, the owner must provide a reachable Linux pool with Python 3 and JDK 17 installed:

```yaml
    pool:
      name: ExistingSitLinuxPool
    javaHome: /owner/managed/jdk-17
```

The nonempty `javaHome` skips the hosted JDK selection task. This template neither creates an agent nor opens access to SIT. A new `stageName` is required for each additional inclusion in one pipeline.

## Contract and verification limits

The [existing CD contract](ci-cd-product-tests.md#deliberately-finite-initial-contract) defines the exact bundle, pinned readiness plugins, independent plan digest, finite GET/HEAD subset and bounded failure handling. Required readiness runs before provider execution. Missing or unverified state is non-success; no fixture establishes real SIT readiness. This hook composes the framework directly: the Swarm module still rejects SIT execution tuples. Live Cognee, arbitrary protocols, authentication forwarding, shared-data mutation and cleanup are not added by these YAML templates.

The existing [ISTQB gTAA / CT-GenAI mapping](product-testing-module.md) applies to actual responsibilities: Swarm proposes/analyzes reviewed tests and evidence, Cognee provides attributed knowledge, framework extensions adapt to systems, and the framework executes/asserts/reports. CI/CD orchestrates those interfaces. These references are guidance, not certification or complete syllabus coverage.

**Azure execution: NOT_RUN.** No Azure organization/project/pipeline was available for this delivery. Static checks cover YAML structure, shell/Python syntax and integration invariants; they do not prove Azure template expansion, task/service acceptance, credentials, Environment checks or live SIT. Prior successful GitHub Linux execution validates the underlying public harnesses, not these Azure templates. Owners must validate and run the pipeline in their Azure project before relying on it.

These templates target **Azure DevOps Services**. Pipeline Artifact tasks are not a drop-in integration for Azure DevOps Server or Classic Release pipelines.

## Microsoft references

- [Azure Repos Git triggers and branch validation policies](https://learn.microsoft.com/en-us/azure/devops/pipelines/repos/azure-repos-git)
- [JDK selection and JAVA_HOME](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/java-tool-installer-v0?view=azure-pipelines)
- [JUnit publication and failure options](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/publish-test-results-v2?view=azure-pipelines)
- [Download Pipeline Artifact](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/download-pipeline-artifact-v2?view=azure-pipelines)
- [Publish Pipeline Artifact](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/publish-pipeline-artifact-v1?view=azure-pipelines)
- [Deployment runOnce hooks and automatic downloads](https://learn.microsoft.com/en-us/azure/devops/pipelines/yaml-schema/jobs-deployment-strategy-run-once?view=azure-pipelines)
- [Resource-owner approvals and checks](https://learn.microsoft.com/en-us/azure/devops/pipelines/process/checks?view=azure-devops)
