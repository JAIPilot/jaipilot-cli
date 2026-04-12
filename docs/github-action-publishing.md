# JAIPilot GitHub Action: Generate and Publish

This repository now includes a publishable root Action in [`action.yml`](../action.yml).

## Zero-manual-repo onboarding via GitHub App

If you want install-time onboarding (no per-repo YAML editing), run the GitHub App service in
[`github-app/README.md`](../github-app/README.md). The app can automatically create/update
the target repo workflow file on installation events.

## One-command onboarding for another repository

Instead of manually creating workflow YAML in every repository, use:

```bash
./scripts/onboard-action-repo.sh \
  --repo <owner/target-repo> \
  --action-ref action-v1 \
  --license-key "<JAIPILOT_LICENSE_KEY>"
```

This command:

- creates or updates `.github/workflows/jaipilot-generate.yml` in the target repo
- points it to this action release
- optionally sets `JAIPILOT_LICENSE_KEY` as a repository secret

## Use the action in a workflow

```yaml
name: JAIPilot Generate Tests

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  jaipilot:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          # Needed so the action can commit to the PR branch.
          ref: ${{ github.head_ref }}

      - name: Run JAIPilot generate for all non-test Java classes
        uses: <OWNER>/<REPO>@action-v1
        with:
          jaipilot-license-key: ${{ secrets.JAIPILOT_LICENSE_KEY }}
```

The action automatically:

- finds all `.java` files excluding test classes (`*Test.java`, `*Tests.java`, `*IT.java`, `*ITCase.java`) and files under `src/test`
- runs `jaipilot generate` for each class
- commits generated test changes
- pushes the commit back to the same branch that triggered the workflow

## Publish the rolling action release

1. Open Actions tab.
2. Run `Publish Action` workflow manually.
3. Keep `release_tag` as `action-v1` (or set another major channel like `action-v2`).

The workflow will:

- force-update the rolling tag (default: `action-v1`) to the latest commit
- create the GitHub Release the first time, then update that same release on later publishes

## Publish in GitHub Marketplace

1. Ensure repository is public.
2. Open your release tag page (`action-v1` by default).
3. Publish the action to Marketplace from the release flow.
4. Keep users pinned to the same major tag (`@action-v1`).

## Why tags are prefixed with `action-`

This repository already uses `v*` tags for CLI release automation.  
Using `action-v*` avoids collisions with existing release workflows.
