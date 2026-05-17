# AGENTS.md

## Project Overview
- `jaipilot-cli` is a Java 17+ CLI plus a GitHub composite action used to generate Java tests in pull requests.
- The repository owns both CLI runtime logic (`src/main/java`) and action automation (`action.yml`, `scripts/`).

## Setup Commands
- Install/build/test: `./mvnw -B verify`
- Run unit tests only: `./mvnw -B test`
- Run one test class: `./mvnw -Dtest=ClassName test`
- Smoke test installer: `./scripts/smoke-test-install.sh`
- Validate auth resolver script: `./scripts/test-resolve-jaipilot-auth.sh`

## Repository Map
- `src/main/java/com/jaipilot/cli` CLI commands, backend client integration, auth/token handling.
- `src/test/java/com/jaipilot/cli` JUnit tests.
- `action.yml` published composite action (`JAIPilot/jaipilot-cli@action-v1`).
- `scripts/resolve-jaipilot-auth.sh` OIDC/token resolution for action runtime auth.
- `scripts/release-push.sh` rolling action tag publish helper (`action-v1`).

## Code Style And Design Constraints
- Keep Java compatibility at 17+ and Maven wrapper-based workflows.
- Prefer small, targeted changes over broad refactors.
- Preserve public CLI and action interfaces unless intentionally versioned:
  - CLI command names/options
  - action input/output names in `action.yml`
- Follow existing Java formatting and package structure.

## Testing Expectations
- Minimum before merge for behavior changes: `./mvnw -B verify`.
- If auth or action logic changes, run:
  - `./scripts/test-resolve-jaipilot-auth.sh`
  - relevant Maven tests covering modified classes.
- If install/distribution logic changes, also run `./scripts/smoke-test-install.sh`.

## Safety And Release Guardrails
- Never log or commit tokens, private keys, or credential-like values.
- Do not commit generated artifacts from `target/`.
- Keep `.classpath.txt` as runtime-generated only; do not keep it in commits.
- For rolling action updates, use `scripts/release-push.sh` rather than ad-hoc tag changes.

## Documentation Expectations
- Update `README.md` and `docs/` when changing user-facing CLI behavior, auth flow, or release/publishing flow.
