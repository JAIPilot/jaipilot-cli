# JAIPilot Project Memory

This file is the durable local memory for Codex-driven JAIPilot runs in this repository.

## Current Workflow Direction

- Prefer the backend-free local workflow.
- Use `codex` as the generation engine.
- Use the JAIPilot CLI as the orchestrator.
- Do not depend on any custom backend or hosted service for local test generation.

## Command Surface

- `jaipilot`
  Opens the interactive shell.
- `jaipilot generate <class>`
  Generate tests for one class.
- `jaipilot generate --changed`
  Generate tests for uncommitted Java production classes.
- `jaipilot generate --coverage-below 80`
  Generate tests for classes below a coverage threshold using an existing JaCoCo XML report.
- `jaipilot status`
  Show current JaCoCo totals, test presence, and classes below the threshold.
- `jaipilot doctor`
  Check Codex, build, git, and coverage prerequisites.

## Memory To Keep Fresh

Update this file when any of these change:

- preferred build, test, and coverage commands per build tool
- confirmed JaCoCo XML report path
- coverage report discovery rules
- mocking and fixture conventions
- known module-specific quirks
- stable examples of good generated tests

## Current Known Defaults

- Maven wrapper is preferred only when `mvnw` and `.mvn/wrapper/maven-wrapper.properties` both exist.
- Gradle wrapper is preferred only when `gradlew` and `gradle/wrapper/gradle-wrapper.properties` both exist.
- Default coverage threshold is `80%`.
- Codex is expected to complete a preparation pass that leaves the repository buildable, tests runnable, and coverage refreshable before class-specific generation starts.
- Codex is expected to determine the final test file path and class name from repository conventions.
- Codex is expected to own the test-run, fix, and coverage loop during generation.
- The interactive shell stores history at `~/.jaipilot/history`.
- The CLI UX favors structured sections, tables, coverage meters, and spinners over raw tool logs.
- Coverage discovery currently looks for JaCoCo XML reports under:
  - `target/site/jacoco/jacoco.xml`
  - `build/reports/jacoco/**/jacoco.xml`
