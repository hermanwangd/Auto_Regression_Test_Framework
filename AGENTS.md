# Repository Guidelines

## Project Structure & Module Organization

This repository is currently a starter workspace for a Spring Boot 3.x / Java 17+ implementation. Keep the top level small and use predictable directories:

- `src/main/java/` for Spring Boot application and framework source code.
- `src/main/resources/` for configuration templates and classpath resources.
- `src/test/java/` for automated tests that mirror the main package layout.
- `specs/` for feature specifications, regression requirements, and acceptance criteria.
- `scripts/` for repeatable local utilities and maintenance commands.
- `assets/` for static fixtures, sample data, or generated examples that are safe to commit.

Do not commit generated outputs, caches, virtual environments, dependency folders, or machine-specific files.

## Build, Test, and Development Commands

This project uses Maven Wrapper with Spring Boot 3.x and Java 17+.

- `./mvnw test`: run fast unit/component tests through Maven Surefire.
- `./mvnw verify`: run unit/component tests, package the jar, and run Failsafe `*IT` framework integration tests.
- `./mvnw package`: compile, test, and package the Spring Boot jar without the Failsafe integration-test phase.
- `./mvnw spring-boot:run`: start the application locally.
- `./mvnw test -Dtest=RegressionCommandTest`: run a focused CLI behavior test.
- `java -jar target/spec-driven-auto-regression-0.1.0-SNAPSHOT.jar check-readiness --root .`: run the packaged CLI.

Prefer deterministic scripts that do not require hidden local state.

## Coding Style & Naming Conventions

Use clear names and keep modules focused on one responsibility. Use Java package names in lowercase, `PascalCase` for classes, `camelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants.

Use project formatters and linters once introduced. Keep configuration in committed files such as `pom.xml`, `build.gradle`, Checkstyle, Spotless, or equivalent.

## Testing Guidelines

Place tests under `src/test/java/`, matching the source package when possible. Use `*Test` for Surefire unit/component tests and `*IT` for Failsafe framework integration tests. Keep framework verification fixtures under `src/test/resources/framework-verification/`.

Regression tests should include the input spec, expected behavior, and at least one failure-path case. Keep fixtures small. If a test needs large data, document how to fetch or regenerate it.

## Commit & Pull Request Guidelines

Git history was not readable from this workspace, so no existing commit convention could be inferred. Use concise imperative messages such as `Add regression spec scaffold` or `Fix test fixture loading`.

Pull requests should include a short summary, test evidence, linked issue or spec when relevant, and screenshots or logs for user-visible behavior changes.

## Agent-Specific Instructions

When running commands in this repository, avoid memory-heavy jobs. Keep processes under 8 GB of RAM to reduce the risk of system instability. Prefer bounded file scans, targeted tests, and incremental verification.
