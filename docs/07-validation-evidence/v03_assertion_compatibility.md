# v0.3 assertion compatibility inventory

At commit `f702b06699baf724f1de1b438115940a0bd3c18b`, no tracked v0.3 case explicitly requires a numeric string to equal a number, a boolean string to equal a boolean, or a present null to count as absent. All 39 `equals` assertions outside the negative-fixture directory get their actual value from a step. Static inspection therefore supports strict typing for the checked-in expectations, but cannot establish the type returned by every deployed provider.

## Corpus

The 56 v0.3 manifests comprise 53 leaf suites and 3 suite groups. Leaf `tests[].ref` entries resolve to 56 distinct case files, including `src/test/resources/reviewed-v2-layout/cases/total.yaml`. These account for every standalone v0.3 case document found in Git. Counts use file paths because different suites reuse case IDs. Java test text blocks, generated runs, v0.2 fixtures and downstream suites are outside this inventory.

| Verify entries | Outside `samples/80-negative/` | In `samples/80-negative/` | Total |
| --- | ---: | ---: | ---: |
| Case files | 39 | 17 | 56 |
| Cases with an `assert` map | 31 | 5 | 36 |
| `equals` | 39 | 4 | 43 |
| `json_match` | 10 | 0 | 10 |
| `file_diff` | 3 | 0 | 3 |
| `schema_match` | 2 | 0 | 2 |
| `matches` | 1 | 0 | 1 |
| Deliberate misspelling `equlas` | 0 | 1 | 1 |
| **Assertion-shaped entries** | **55** | **5** | **60** |

Of the 60 entries, 59 declare `type: assertion`. The negative `missing_verify_type` case supplies one of the four negative `equals` entries. There are also 24 `provider_check` entries. No case uses `not_equals`, `gt`, `gte`, `lt`, `lte`, `exists` or `not_exists`.

The local CLI validated all 56 manifests with their default profile on the baseline and the proposed change. The exit code matched for every manifest (33 returned zero and 23 returned nonzero on both versions). The corpus includes negative fixtures and reviewed layouts with separate admission requirements. This comparison measures validation only.

## Operand and schema evidence

- **Numeric and boolean expectations.** Of the 39 equality checks outside the negative directory, 19 expect integer literals, 4 expect boolean literals and 14 expect strings. The other two load the JSON integer `0` from the Kafka `partition.json` and `offset.json` oracles. The JDBC sample compares `row_count` to integer `1`. MongoDB and Redis compare `matched` to YAML booleans. The built-in JDBC runtime emits a numeric row count and the MongoDB/Redis adapters construct booleans. The CLI case compares its numeric exit code to `1` and its text stdout to the five letters `false` followed by a newline. Its empty stderr expectation is an empty string. [JDBC case](../../samples/20-provider-capability-p0/data/jdbc/test_cases/order_query_success.yaml) · [MongoDB case](../../samples/20-provider-capability-p0/data/mongodb/test_cases/owned_order.yaml) · [CLI case](../../samples/20-provider-capability-p0/cli/cli_process/test_cases/cli.yaml) · [Kafka oracles](../../samples/41-product-testing/kafka_native_owned/expected_results/)
- **Runtime-dependent values.** All 39 actual operands are `step://` references. For example, Mailpit compares two HTTP body `/total` values to integers. A response containing `"total": "1"` could pass the baseline equality fallback against `1`. The case supplies no captured response proving the deployed service returned that string. The negative forward-reference case deliberately names an unavailable future step. Its `equals` check supplies no evidence of a missing-as-null convention. The intentional product assertion-failure fixture expects the string `WRONG`. [Mailpit case](../../samples/20-provider-capability-p0/http/mailpit/test_cases/recovery_mail.yaml) · [Forward-reference fixture](../../samples/80-negative/refs/forward_step_ref/test_cases/forward_step_ref.yaml) · [Product failure fixture](../../samples/41-product-testing/assertion_failure/test_cases/golden_success.yaml)
- **Null and presence.** No assertion sets an `actual` or `expected` literal to null. The baseline evaluator treats both a present Java null and the missing-value sentinel as absent. No tracked case exercises this distinction. The two scalar `expected_ref` entries resolve to integer artifacts. [Baseline evaluator](https://github.com/hermanwangd/Auto_Regression_Test_Framework/blob/f702b06699baf724f1de1b438115940a0bd3c18b/src/main/java/com/specdriven/regression/contract/v03/assertion/V03AssertionEvaluator.java)
- **Schema keywords.** The two `schema_match` entries resolve through their respective manifests to distinct JSON files. Both use root `type: object`, `required` and `properties`. Their direct property schemas use only `type` with values `string`, `array` or `object`. The property named `items` is a data field. Neither schema contains the JSON Schema `items` keyword or an unrecognized keyword. Both checked-in payloads contain every required field with its declared type. [Artifact-compare schema](../../samples/20-provider-capability-p0/verification/artifact_compare/expected_results/expected_schema.json) · [Common-verify schema](../../samples/20-provider-capability-p0/verification/common_verify/expected_results/expected_schema.json)

## Reproduce the inventory

Run from the repository root with Python 3 and PyYAML. The script reads the pinned Git archive and compares manifest references against all discovered case paths.

```sh
python3 - <<'PY'
import posixpath, subprocess, tarfile
from collections import Counter
import yaml

suites, cases = {}, {}
with subprocess.Popen(["git", "archive", "f702b066"], stdout=subprocess.PIPE) as git:
    with tarfile.open(fileobj=git.stdout, mode="r|") as tree:
        for entry in tree:
            if not entry.isfile() or not entry.name.endswith((".yaml", ".yml", ".json")):
                continue
            raw = tree.extractfile(entry).read()
            if b"manifest_version" not in raw and b"dsl_version" not in raw:
                continue
            doc = yaml.safe_load(raw)
            if isinstance(doc, dict) and doc.get("manifest_version") == "v0.3":
                suites[entry.name] = doc
            if isinstance(doc, dict) and doc.get("dsl_version") == "v0.3":
                cases[entry.name] = doc
    assert git.wait() == 0
refs = [posixpath.normpath(posixpath.join(posixpath.dirname(path), test["ref"]))
        for path, suite in suites.items() for test in suite.get("tests", [])]
assert len(refs) == len(cases) and set(refs) == set(cases)
print("manifests, groups, case files", len(suites),
      sum("child_suites" in s for s in suites.values()), len(cases))
for negative in (False, True):
    subset = [case for path, case in cases.items() if ("/80-negative/" in path) == negative]
    nodes = [v for c in subset for v in c.get("verify", [])]
    assertions = [v["assert"] for v in nodes if "assert" in v]
    print("negative directory", negative, len(subset), Counter(v.get("type") for v in nodes))
    print("cases with assertions", sum(any("assert" in v for v in c.get("verify", [])) for c in subset))
    print("operators", Counter(a["operator"] for a in assertions))
    print("equality expected", Counter(type(a["expected"]).__name__ if "expected" in a
                                       else a["expected_ref"] for a in assertions if a["operator"] == "equals"))
PY
```
