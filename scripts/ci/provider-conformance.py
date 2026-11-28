"""Check native operations against this run's packaged CLI result files."""
import argparse
import json
import os
import platform
from pathlib import Path


def job_identity():
    return {key: os.environ.get(variable, "local") for key, variable in
            [("revision", "GITHUB_SHA"), ("job_run", "GITHUB_RUN_ID"), ("job_attempt", "GITHUB_RUN_ATTEMPT")]}


REGISTRATIONS = Path(__file__).resolve().parents[2] / "src/test/resources/provider-conformance.json"


# These service gates each induce a different outage after a successful write.
OUTAGES = {
    "mongodb.v0.3": ("MONGO_OPERATION_FAILED", ["MONGO_OPERATION_FAILED"]),
    "redis.v0.3": ("PROVIDER_UNAVAILABLE", ["PROVIDER_UNAVAILABLE"]),
    "s3.v0.3": ("S3_REQUEST_FAILED", []),
}


def check_outage(contract, path):
    data = json.loads(path.read_text())
    code, cleanup = OUTAGES[contract]
    failed = data["failure"]
    if (data["status"] != "failed" or failed["code"] != code or
            failed["classification"] != "PROVIDER_RUNTIME_ERROR" or
            [row["failure_code"] for row in data["cleanup_failures"]] != cleanup or
            data["cleanup_failures"] != failed["cleanup_failures"]):
        raise ValueError(f"{contract}: unexpected primary error or cleanup outcome in {path}")
    actual = [p for p in data["provider_results"] if p["provider_contract"] == contract and p["runtime_mode"] == "native"]
    if not actual:
        raise ValueError(f"{contract}: missing native provider result for outage")
    return {"suite": data["suite_id"], "profile": data["profile"], "run": data["run_id"], "status": "failed",
            "primary_failure": failed["code"], "cleanup_failures": cleanup}


def check(contract, required, results):
    observed = set()
    runs = []
    for result in results:
        data = json.loads(result.read_text())
        if data["status"] != "passed":
            raise ValueError(f"{result} is not a passing scenario")
        providers = [row for row in data["provider_results"]
                     if row["provider_contract"] == contract and row["runtime_mode"] == "native"]
        if not providers:
            raise ValueError(f"{result} missing native {contract}")
        targets = {row["target"] for row in providers}
        for step in data["step_results"]:
            if step["target"] in targets and step["status"] == "passed" and step["outputs"]:
                observed.add(step["operation"])
        runs.append({"suite": data["suite_id"], "profile": data["profile"], "run": data["run_id"], "status": "passed"})
    if set(required) != observed:
        raise ValueError(f"{contract}: missing {sorted(set(required)-observed)}, unexpected {sorted(observed-set(required))}")
    return {"contract": contract, "platform": platform.system(), "scenarios": runs,
            "operations": sorted(observed), "proof": "native", **job_identity()}


# Reuse assertions from the normal Maven verification. Only executed, passing JUnit
# cases qualify; removing, ignoring or skipping a named case breaks the gate.
BEHAVIOR = {
    "contract_operation_mode": [
        "ProviderConformanceTest.allPublicContractsAgreeWithTheActualDispatcherAndTestRegistrations",
        "ProviderConformanceTest.rejectsForeignModesBindingsAndInputsBeforeProviderDispatch"],
    "preflight_before_io": [
        "MongoV03AdapterTest.rejectsUntrustedEndpointsKeysAndOperatorsBeforeOpeningAnyConnection",
        "RedisV03AdapterTest.rejectsUnknownCommandsAndUnboundedOrNonliteralInputsBeforeConnecting",
        "RestClientProviderRuntimeJsonEvidenceTest.rejectsUnsupportedOperationWithoutHttpOrEvidence"],
    "runner_bindings": [
        "RedisV03AdapterTest.admitsOnlyTheFixedRunnerUriAndKeepsCredentialsOutOfFailuresAndEvidence",
        "SftpV03AdapterTest.rejectsAlteredBindingsAndMasksTheRunnerKeyFromErrorsAndEvidence",
        "CliProcessProviderRuntimeTest.rejectsExecutableTamperBeforeLaunch"],
    "bounds": [
        "CliProcessProviderRuntimeTest.usesOneDeadlineForAProcessThatDoesNotReadItsStdin",
        "CliProcessProviderRuntimeTest.failsCombinedOutputOverflowWithoutReturningTruncatedSuccessfulOutput",
        "WebSocketV03AdapterTest.limitsCountAcrossQueuedMessagesAndReturnsAfterTheDeclaredScanBudget",
        "SftpV03AdapterTest.rejectsTraversalAndOversizeFixturesBeforeStartingAnUpload",
        "S3V03AdapterTest.capsUploadsAndDownloadsAndUsesDistinctKeysForConcurrentRuns",
        "MongoFixtureDocumentTest.rejectsDeepAndOversizedDocuments",
        "DslV03VerificationRuntimeCommandTest.runV03PollingTimeoutRetriesUntilTimeoutAndKeepsLastObservedEvidence",
        "GrpcMetadataDeadlineContractTest.serverObservesSubsecondDeadlineAndCancellationAfterWarmup"],
    "redaction": [
        "V03RuntimeStatusTest.redactsSensitiveRuntimeOutputsAndProviderEvidenceBeforeWritingRunArtifacts",
        "S3V03AdapterTest.serviceErrorsAndMalformedEndpointsKeepSensitiveTextOutOfEvidence",
        "MongoV03AdapterTest.differentiatesAbsentDocumentAndWrongBsonPayloadAndRedactsDriverErrors",
        "CliProcessProviderRuntimeTest.failsSecretLikeOutputAndPersistsOnlyMaskedText",
        "RestClientProviderRuntimeJsonEvidenceTest.masksAnEchoedJsonStringInBothOutputAndProviderEvidence"],
    "failure_classification": [
        "V03RuntimeStatusTest.rejectsFailureCodesNotDeclaredByTheProviderContract",
        "JdbcExternalEnvSecretRefTest.nativeJdbcConnectionFailureIsNotClassifiedAsSqlExecutionFailure",
        "IbmMqProviderRuntimeTest.mqNativeModeFailsFastWithOwnerActionableConnectionFailure"],
    "end_run": [
        "ProviderConformanceTest.releasesMocksAfterAFailedSuiteWithoutAnExplicitCleanup",
        "ProviderConformanceTest.isolatesMocksAcrossRunsCasesAndTargets",
        "ProviderConformanceTest.reusesTheSameFixedPortAcrossSequentialCasesWithoutReset",
        "CliProcessProviderRuntimeTest.interruptionCleansUpAndAllowsASeparateRecoveryRun",
        "S3V03AdapterTest.cleanupTargetsAcknowledgedUploadEvenWhenAnotherVersionIsCurrent"],
    "primary_and_cleanup_failure": [
        "ProviderConformanceTest.reportsFinalizationFailureOnlyForItsCaseAndPreservesPrimaryFailure",
        "V03RuntimeStatusTest.primaryFailureSurvivesBlockedCleanupAndCleanupStillExecutes",
        "DslV03JdbcRuntimeCommandTest.runV03JdbcPreservesPrimaryFailureWhenCleanupAlsoFails"],
    "public_status": [
        "ProviderSupportStatusTest.descriptorAdmissionStillRequiresReviewedHandoff",
        "NoIbmMqRegistryTest.v03RejectsAnInjectedMqAdapterBeforeExecute"],
}


def check_behavior_reports(reports, contracts):
    from defusedxml import ElementTree

    groups = {**BEHAVIOR, **{key: [row["boundaryTest"]] for key, row in contracts.items()}}
    required = {case for cases in groups.values() for case in cases}
    observations = {key: [] for key in required}
    for path in reports.glob("TEST-*.xml"):
        for case in ElementTree.parse(path, forbid_dtd=True).iter("testcase"):
            key = case.attrib["classname"].rsplit(".", 1)[-1] + "." + case.attrib["name"].split("(")[0]
            if key in observations:
                observations[key].append(all(case.find(tag) is None for tag in ("skipped", "failure", "error")))
    for key, passes in observations.items():
        if not passes or not all(passes):
            raise ValueError(f"required conformance test missing, skipped or failed: {key}")
    return {rule: {key: len(observations[key]) for key in cases} for rule, cases in groups.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--complete", action="store_true")
    parser.add_argument("--outage-result", type=Path)
    parser.add_argument("--surefire-reports", type=Path, default=Path("target/surefire-reports"))
    parser.add_argument("contract", nargs="?")
    parser.add_argument("results", nargs="*", type=Path)
    args = parser.parse_args()
    contracts = {row["contract"]: row for row in json.loads(REGISTRATIONS.read_text())}
    receipt_path = os.environ.get("PROVIDER_CONFORMANCE_RECEIPTS")
    if args.complete:
        if not receipt_path:
            parser.error("set PROVIDER_CONFORMANCE_RECEIPTS to this job's receipt directory")
        seen = set()
        for path in Path(receipt_path).glob("*/*.json"):
            receipt = json.loads(path.read_text())
            contract = receipt["contract"]
            registration = contracts[contract]
            expected_lane = "native" if registration["proof"] == "native" else "portable"
            if (contract in seen or path.parent.name != expected_lane or path.stem != contract or
                    receipt["proof"] != registration["proof"] or not receipt["scenarios"] or
                    set(receipt["operations"]) != set(registration["operations"]) or
                    any(receipt.get(k) != v for k, v in job_identity().items())):
                parser.error(f"duplicate, stale or incomplete receipt: {path}")
            if contract in OUTAGES:
                code, cleanup = OUTAGES[contract]
                if not any(case.get("status") == "failed" and case.get("primary_failure") == code and
                           case.get("cleanup_failures") == cleanup for case in receipt["scenarios"]):
                    parser.error(f"missing confirmed native outage: {contract}")
            seen.add(contract)
        if seen != set(contracts):
            parser.error(f"missing {sorted(set(contracts)-seen)}, unexpected {sorted(seen-set(contracts))}")
        behavior = check_behavior_reports(args.surefire_reports, contracts)
        print(json.dumps({"behavior_checks": behavior, **job_identity()}, sort_keys=True))
        return
    if contracts.get(args.contract, {}).get("proof") != "native" or not args.results:
        parser.error("a registered native contract and at least one fresh result are required")
    receipt = check(args.contract, contracts[args.contract]["operations"], args.results)
    if args.contract in OUTAGES:
        if not args.outage_result:
            parser.error(f"{args.contract} requires its actual outage result")
        receipt["scenarios"].append(check_outage(args.contract, args.outage_result))
    print(json.dumps(receipt, sort_keys=True))
    if receipt_path:
        directory = Path(receipt_path) / "native"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / (args.contract + ".json")).write_text(json.dumps(receipt) + "\n")


if __name__ == "__main__":
    main()
