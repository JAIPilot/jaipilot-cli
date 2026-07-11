# AGENTS.md

## Project Overview
- `jaipilot-cli` is a Java 17+ CLI for local Java unit-test generation with Codex.
- The product is intentionally backend-free for local generation: no JAIPilot, Supabase, or custom service calls.
- Treat `AGENTS.md`, `.jaipilot/project-memory.md`, and `.agents/skills/jaipilot-generate/SKILL.md` as the durable context for repeated runs.

## Setup Commands
- Install/build/test: `./mvnw -B verify`
- Run unit tests only: `./mvnw -B test`
- Run one test class: `./mvnw -Dtest=ClassName test`
- Smoke test installer: `./scripts/smoke-test-install.sh`

## Repository Map
- `src/main/java/com/jaipilot/cli` CLI commands and local services.
- `src/test/java/com/jaipilot/cli` JUnit tests.
- `scripts/build-bundled-dist.sh` builds the bundled runtime distribution.
- `scripts/release-build.sh` prepares a semver release.
- `install.sh` installs the latest published CLI release.
- `.agents/skills/jaipilot-generate/SKILL.md` Codex skill entry for the local generation workflow.
- `.jaipilot/project-memory.md` evolving local-memory file for Codex and JAIPilot.

## Code Style And Design Constraints
- Keep Java compatibility at 17+ and Maven wrapper-based workflows.
- Prefer small, targeted changes over broad refactors.
- Preserve public CLI interfaces unless intentionally versioned:
  - CLI command names/options
- Follow existing Java formatting and package structure.
- Prefer explicit durable memory files over hidden agent assumptions:
  - short stable rules in `AGENTS.md`
  - reusable procedures in `.agents/skills/`
  - evolving discovered facts in `.jaipilot/project-memory.md`

## Testing Expectations
- Minimum before merge for behavior changes: `./mvnw -B verify`.
- If install/distribution logic changes, also run `./scripts/smoke-test-install.sh`.

## Safety And Release Guardrails
- Do not commit generated artifacts from `target/`.
- Keep `.classpath.txt` as runtime-generated only; do not keep it in commits.

## Documentation Expectations
- Update `README.md` when changing user-facing CLI behavior, install flow, or release flow.

## Local Agent Workflow
- Prefer `codex` as the first local generation engine.
- Use JaCoCo as the coverage source of truth.
- Default coverage threshold is `80%`.
- Treat `src/test/java` as the default write surface.
- Do not modify production code during unit-test generation unless the user explicitly asks for it.
