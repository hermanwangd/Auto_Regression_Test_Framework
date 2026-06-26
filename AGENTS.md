# Repository Guidelines

## Project Structure & Module Organization

This repository is currently a starter workspace. Keep the top level small and use predictable directories:

- `src/` for application or library source code.
- `tests/` for automated tests that mirror the `src/` layout.
- `specs/` for feature specifications, regression requirements, and acceptance criteria.
- `scripts/` for repeatable local utilities and maintenance commands.
- `assets/` for static fixtures, sample data, or generated examples that are safe to commit.

Do not commit generated outputs, caches, virtual environments, dependency folders, or machine-specific files.

## Build, Test, and Development Commands

No build system is present yet. When adding one, document the canonical commands here.

Examples to add when applicable:

- `make test` or `npm test`: run the full automated test suite.
- `make lint` or `npm run lint`: check formatting and static issues.
- `make dev` or `npm run dev`: start a development server or watcher.
- `python -m pytest tests/`: run Python tests directly if the project uses pytest.

Prefer deterministic scripts that do not require hidden local state.

## Coding Style & Naming Conventions

Use clear names and keep modules focused on one responsibility. Prefer `snake_case` for Python files and functions, `camelCase` for JavaScript or TypeScript variables, and `PascalCase` for classes and components.

Use project formatters and linters once introduced. Keep configuration in committed files such as `pyproject.toml`, `package.json`, `.prettierrc`, or equivalent.

## Testing Guidelines

Place tests under `tests/`, matching the source module when possible. Use names that describe behavior, for example `test_regression_runner_rejects_missing_spec.py` or `regression-runner.test.ts`.

Regression tests should include the input spec, expected behavior, and at least one failure-path case. Keep fixtures small. If a test needs large data, document how to fetch or regenerate it.

## Commit & Pull Request Guidelines

Git history was not readable from this workspace, so no existing commit convention could be inferred. Use concise imperative messages such as `Add regression spec scaffold` or `Fix test fixture loading`.

Pull requests should include a short summary, test evidence, linked issue or spec when relevant, and screenshots or logs for user-visible behavior changes.

## Agent-Specific Instructions

When running commands in this repository, avoid memory-heavy jobs. Keep processes under 8 GB of RAM to reduce the risk of system instability. Prefer bounded file scans, targeted tests, and incremental verification.
