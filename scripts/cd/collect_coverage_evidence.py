"""Stage the allowlisted gate evidence for the Azure post-deployment job."""
import json
import os
from pathlib import Path
import shutil


def collect(source, target):
    source = source.resolve()
    target.mkdir(parents=True, exist_ok=False)
    files = []
    gate = source / 'gate-result.json'
    metadata = {}
    if gate.is_file() and not gate.is_symlink():
        metadata = json.loads(gate.read_text())
        files.append(gate)
    # The existing gate sets this only after canonical validation/report.
    if metadata.get('framework_verdict') in ('passed', 'failed', 'blocked', 'skipped'):
        canonical = source / 'runtime/target/regression'
        files.extend(p for p in canonical.rglob('*') if p.is_file())
        # Derived report pairs remain outside the frozen canonical run tree.
        coverage = source / 'runtime/target/coverage'
        for name in ('coverage.json', 'coverage.md'):
            files.extend(p for p in coverage.rglob(name) if p.is_file())
    collected = []
    for path in files:
        for node in (path, *path.parents):
            if node.is_symlink():
                raise RuntimeError('Symlink evidence is prohibited')
            if node == source:
                break
        path.resolve(strict=True).relative_to(source)
        relative = path.relative_to(source)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        collected.append(str(relative))
    (target / 'collection.json').write_text(json.dumps({'files': collected, 'claim': 'artifact_collection_only'}, indent=2) + '\n')


if __name__ == "__main__":
    collect(Path(os.environ["CD_EVIDENCE_ROOT"]), Path(os.environ["CD_STAGED_ROOT"]))
