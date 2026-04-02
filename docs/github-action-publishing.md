# JAIPilot GitHub Action: Generate and Publish

This repository now includes a publishable root Action in [`action.yml`](../action.yml).

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

## Publish a new action release

1. Open Actions tab.
2. Run `Publish Action` workflow manually.
3. Pass version in `x.y.z` format (for example, `1.0.0`).

The workflow will:

- create immutable tag `action-vx.y.z`
- optionally update moving major tag `action-vx`
- create a GitHub Release for `action-vx.y.z`

## Publish in GitHub Marketplace

1. Ensure repository is public.
2. Open your release tag page (`action-vx.y.z`).
3. Publish the action to Marketplace from the release flow.
4. Keep users pinned to the major tag (`@action-v1`).

## Why tags are prefixed with `action-`

This repository already uses `v*` tags for CLI release automation.  
Using `action-v*` avoids collisions with existing release workflows.
