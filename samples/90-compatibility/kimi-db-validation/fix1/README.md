# DB-02-runtime-hardening-r2-fix1 source-only publication derivative

sqlite-utils 4.2.1 的 focused integration 修正與獨立衍生 source／evidence。以 r2 source ZIP（SHA-256：`fc5379e015c635931424fa0d410ea9b71e98eeb82c73fe6f72e2f381f2b1fef0`）為 base；原 SWARM-DB-01、SWARM-DB-02 六份 ZIP、12／12 manifest、r1／r2 source／evidence 與所有失敗紀錄不修改。本目錄不改 Peewee、DuckDB、核心 runner／validator／DSL 或其他隊範圍。

## Source-only publication derivative

本目錄是為公開 GitHub 交接製作的 45-file source-only publication derivative。歷史 raw evidence、rejected runs、raw logs、source-digest history、原始 ZIP 的 `source-manifest.json`／`SHA256SUMS`、baseline，以及含私有路徑的 raw provenance 均刻意不附；原 producer artifacts 與 evidence 保持不變。

原 producer 在 source digest `a6c6b52cb90942fc6a00ec20145e81982cbc7355b08032f74086d8f3c485875e` 下取得 controls **17 executed／17 passed** 與 sqlite-utils derived smoke **4 executed／4 passed**。這些只屬於上述原 digest 的 producer 結果；本次 publication delta 沒有重跑產品或 controls，**不得把 17／17 或 4／4 轉稱為本 derivative 新 digest 已跑通的結果**。

## 凍結範圍

- 原四案 `case_manifest.json` 與測試 oracle 不變，SHA-256：`84c8f685a7751de223cbd84dc494a603879e3c1035c3f23bcce2415305feec49`。
- `requirements.lock` 不變，13 個 distributions 全部以官方 PyPI SHA-256 pin，SHA-256：`33a1109241880795416534548bbd83c312928b9aa08090954c9c7aa1f52d3112`。
- sqlite-utils source tag／commit：`4.2.1`／`28dc6278cc03a9245325d056e6986818544abc68`；SPDX Apache-2.0，授權文字位於 `licenses/`。
- Click 只是傳遞依賴；沒有 Click／Typer／Rich 案例。
- `control-source-manifest.json` 凍結 runner、validator、policies、fixtures、locks／config 與兩份 case manifests 的內容 SHA-256；其 bytes digest 記在 `provenance/control-source-digest.sha256`。

## r2-fix1 hardened runner

`run_suite.py` 與 `run_controls.py` 共用以下 fail-closed policy：

- UUID run token、exclusive run／case-output directories，逐案綁定 run token、manifest SHA-256、source digest 與 frozen JUnit identity。
- trusted wheel SHA-256、wheel／installed RECORD coverage、installed bytes 與必要 metadata 全量核對；只接受明確分類的 RECORD self、generated pyc、console scripts、INSTALLER／REQUESTED。
- pytest 與 runtime import probe 都以 `python -B -I -S` 啟動，清除 ambient Python／pytest 環境，停用 plugin autoload；記錄並限制 sys.path、distribution／import origins 與 plugin inventory。
- fix1 在安裝後保存並移除 target runtime 的 `.pyc`／`.pyo`；approved process 以同一個 `approved_runtime.py` 移除 `SourcelessFileLoader`，因此 `-B` 的禁寫之外，也不再從 target runtime 讀入既有 sourceless bytecode。
- approved import 前重新掃描 target runtime 的 `.py`／`.pyc`／`.pyo`／`.so`／`.pyd`：所有 bytecode 都拒絕，其餘 importables 必須精確對應 installed distributions 的 RECORD 路徑。查驗失敗時不執行 target imports。
- JUnit 必須有精確 testcase nodes，failure／error／skipped、missing／duplicate／unknown identity、properties 或 counters 不一致都拒絕。
- 每個 subprocess 使用獨立 session／process group；timeout 後只清理本 run 的 TERM／KILL process tree，所有 wait 都有上限，stdout／stderr 使用一般檔案避免 inherited-PIPE 無限等待。
- 每 run 保存 controlled source 與 control-source manifest 的內容 snapshot，執行前後逐檔驗證；任何 mutation 都整批拒絕。

## 重跑

首次執行且 `.wheelhouse` 沒有 wheels 時需要網路，從官方 PyPI 以 `--require-hashes` 下載。指定 runtime 若無相符 marker 會先保留性 rename，再從 locked wheelhouse 離線乾淨安裝；即使有 marker，每次仍重新驗證全部 trusted wheels／RECORD／isolation。

```bash
python3 run_controls.py \
  --runtime /tmp/db02-r2-fix1-control-runtime \
  --timeout 180

python3 run_suite.py \
  --runtime /tmp/db02-r2-fix1-product-runtime \
  --install-timeout 300 \
  --test-timeout 120
```

若 `/tmp` runtime 不存在，可先從已驗證的 `.wheelhouse` 重建 control runtime：

```bash
python3 -m pip install \
  --no-index --find-links .wheelhouse \
  --target /tmp/db02-r2-fix1-control-runtime \
  --disable-pip-version-check --require-hashes \
  -r requirements.lock
```

## Targeted controls 與衍生結果

`controls/manifest.json` 保留原 15 個 positive／negative controls，新增 `HR2F1-PYC-016` 與 `HR2F1-IMPORTABLE-017`：前者以 magic 有效但內容變更且列入 RECORD 的 pyc 驗證 approved inventory 仍拒絕；後者以未列入 RECORD 的 importable 驗證在 target import 前拒絕且不執行 poison。Controls 與 sqlite-utils 四案分開計數。

原 producer artifacts 保留所有 rejected runs、raw logs、當時 source manifest／digest 與修正理由；後續修正不覆寫失敗證據，也不改產品案例 expected。最終 accepted run IDs 與逐項 closure 記在原 producer 的 `evidence/required-evidence-closure.json`，不隨本 derivative 發布。

## 證據與 practical boundary

- 原 producer 的 `evidence/`：runtime 安裝／準備／驗證、sqlite-utils 衍生 runs、raw logs、JUnit、case records、probe、source snapshots 與 closure；本 derivative 刻意不附。
- 原 producer 的 `controls-evidence/`：所有 rejected／accepted control runs 與 launcher logs；本 derivative 刻意不附。
- 原 producer 的歷史 `provenance/`：r2→fix1 baseline、r2 source／evidence 逐檔 hashes，以及每次 rejected run 對應的 source digest history；本 derivative 只保留 `source-lock.json`、frozen manifest hash 與目前 control-source digest。
- `.wheelhouse/`：原 producer 實際使用的 13 個 locked wheels；不隨本 derivative 發布，逐 wheel 驗證結果保留在原 producer evidence。

本修正只涵蓋 `requirements.lock` 的 13 個 target distributions、其 installed RECORD 與 target runtime import paths。RECORD data files 仍由原有 verifier 核對；本次 wheels 為 pure-Python，approved target module origins 必須是 runtime 內的 `.py`。inventory 掃描 `.py`／`.pyc`／`.pyo`／`.so`／`.pyd`，但不是通用 supply-chain 掃描器。

明示信任邊界是 Python interpreter 與其 startup、stdlib、本 testware source root，以及 OS／filesystem 的正常行為。本修正不宣稱保護整台主機、防禦惡意同機使用者、驗證 interpreter 啟動前已載入的全部 bytes、native library 內部行為或所有動態 OS 資源載入；也不宣稱 target runtime 外所有可能載入的 bytes 都已驗證。

全部結果只是 derived package/runtime evidence 與 targeted regression controls。既有 116 denominator 不變；不是 Native PASS、SIT PASS、正式接納或獨立 reviewer 簽核。沒有任何 remote 寫入。
