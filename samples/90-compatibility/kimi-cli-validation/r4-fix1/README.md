# CLI-01 hardening r4-fix1

本批由精確 r2 source-only base 加已交付 r3 patch 重現後衍生，修正整合相容性、JUnit raw identity、`--test-path`／node selection 凍結，以及每次執行的 evidence 隔離。r2、原 incomplete r3、新 r3 複跑與所有失敗批次均保持不變；本批不重跑 Click、Fire、docopt 或 prompt_toolkit 產品案例。

## Acceptance contract

`pytest_exit_code` 是 observed pytest 結果；`runner_exit_code` 與 `accepted` 是獨立 acceptance 決策。只有以下條件全部成立才回傳 `0`／`accepted=true`：

- 當次 UUID JUnit bytes 已保存並 readback，raw testcase identities 唯一且完整，outcomes 與 suite counters 一致。
- Manifest 每個 required case 以 classname／name／file 精確匹配且實際 `passed`；unknown、missing、duplicate、blocked、skipped、failed、NOT RUN 均 fail closed。
- 有效 test path、node selection 與 selected source SHA-256 在 launch 前符合 manifest；未授權 override 不會啟動 pytest。
- Manifest、runner、tests、locks、runtime／pytest config 與有效 source closure 前後 SHA-256 不變。
- Runtime pin gate 通過，且 observed pytest exit 為 0。

其他情況回傳 `3`（harness／runtime／preflight failure）或 `4`（acceptance rejected）。`latest` 僅是便利 pointer，不是 acceptance evidence source。

## Evidence layout

每次執行寫入 `evidence/runs/<suite>/<run_id>/`，包含：

- `run_metadata.json`、`case_evidence.json`、當次 `junit-<run_id>.xml`；
- pytest stdout／stderr、pre-execution freeze、runtime gate、environment；
- launch 前保存的 manifest、selected source、locks 與 runtime config；
- `SHA256SUMS.txt` 與人讀摘要。

Run directory 必須能 exclusive 建立；既有 UUID directory 會直接拒絕，不新增或覆寫其中的舊 evidence。

## 官方 hash-pinned 安裝與重跑

```bash
python3 -m venv /tmp/swarm-cli01-hardening-r4-venv
/tmp/swarm-cli01-hardening-r4-venv/bin/python -m pip install \
  --isolated --require-hashes --only-binary=:all: \
  --index-url https://pypi.org/simple \
  -r requirements-click-hashes.txt
/tmp/swarm-cli01-hardening-r4-venv/bin/python scripts/run_suite.py \
  --suite hardening --requirements requirements-click.lock \
  --timeout-seconds 180
```

本 fix1 僅修正兩項整合缺陷：manifest `case_id` 必須唯一，以及有效 runtime config／requirements lock 必須納入 pre/post freeze。舊 R4 的 32／32 是帶三筆共用 `R4-401` 缺陷的歷史結果，未回寫。

最終 manifest 包含原 r2 六個、原 r3 九個、r4 十七個及 fix1 三個 targeted controls，共 35 個 unique case IDs／JUnit identities。新增案例只驗證 harness integration／negative controls，不冒充新產品覆蓋，不計入既有 116 denominator，也不構成 Swarm Native PASS 或 SIT PASS。
