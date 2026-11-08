# A09 V1/V2 preserved publication

Owned compatibility artifacts; not canonical Framework or Module results.

- `v1-artifact.zip.base64`: complete V1 ZIP and all original receipts.
- `v2-artifact.zip.base64`: complete V2 ZIP, five single-operation drafts, raw receipts, negative tests, pins, diff and manifest.
- Sidecars expose manifests, expected/provenance and execution boundaries.

Join wrapped base64 lines and decode with a standard base64 decoder. Verify V1 ZIP SHA256 `1bc790c7730719b619e3716b271f8037122a07e1e0f9cd74b3f893d69c0e937b` and V2 ZIP SHA256 `4af858f3819e8c6519b90b1c78fab75c0d1646617dda53aa846b41a8594e5eb1`. V1 has 19 ZIP entries; V2 has 94. V2 manifest covers 93 files excluding itself.

Framework/Module: `NOT_RUN`; canonical run_id: `null`; oracle independence: `UNKNOWN`. V2 valid scope is original validation + test-owned substitutes + in-process compatibility, not original DB/auth/server or upstream pinned runtime.
