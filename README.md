# JAIPilot CLI

`jaipilot verify` runs JaCoCo and PIT for a Maven project and prints a simple `PASS` or `FAIL` report with actionable reasons.

The target project does not need JaCoCo or PIT configured in its own `pom.xml`.

## Requirements

- Java 17+
- A Maven project
- JUnit 4 or JUnit 5 tests

## Install

Homebrew:

```sh
brew install skrcode/tap/jaipilot
```

Fallback from source:

```sh
./scripts/install-global.sh
```

Then make sure `~/.local/bin` is on your `PATH`.

## Usage

Run inside any Maven project:

```sh
jaipilot verify
```

Set thresholds explicitly:

```sh
jaipilot verify \
  --line-coverage-threshold 85 \
  --branch-coverage-threshold 75 \
  --instruction-coverage-threshold 85 \
  --mutation-threshold 80
```

Use a specific Maven executable:

```sh
jaipilot verify --maven-executable /path/to/mvn
```

## What You Get

- progress updates while the command runs
- a final `PASS` or `FAIL`
- exact coverage gaps
- exact mutation failures
- concrete next actions

## Notes

- Maven only
- JUnit 4 and JUnit 5 are supported
- the command uses a temporary mirrored workspace and does not edit the target repo

## Releasing

Push a tag like `v0.1.0`.

The release workflow builds:

- `jaipilot-<version>.zip`
- `jaipilot-<version>.tar.gz`

Then it publishes the GitHub Release and updates the Homebrew tap.

Required secret:

- `GH_PAT` with write access to this repo and `skrcode/homebrew-tap`
