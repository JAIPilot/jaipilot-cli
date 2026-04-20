# JAIPilot GitHub Action: Generate and Publish

This repository includes a publishable root Action in [`action.yml`](../action.yml).

## Recommended onboarding: GitHub App auto-install

To avoid per-repo YAML copy/paste, deploy the GitHub App backend endpoints (recommended in `jaipilot-functions`):

- `POST /functions/v1/github-app-webhook`
- `POST /functions/v1/github-actions-token`

On app install events, the service automatically manages:

- `.github/workflows/jaipilot-generate.yml`
- OIDC-based runtime authentication (`POST /functions/v1/github-actions-token`)
- PR fallback when default branch protection blocks direct workflow writes

## Managed workflow behavior

The managed workflow includes:

- trigger: `pull_request` on `opened`, `synchronize`, `reopened`
- permissions: `contents: write`, `pull-requests: write`, `id-token: write`
- OIDC token request from GitHub Actions
- runtime token exchange with `POST /functions/v1/github-actions-token`
- action execution with `jaipilot-auth-token`

Managed file contract:

- marker at top of file: `# managed-by: jaipilot-github-app`
- marker present: workflow is updated in place
- marker absent: app opens/updates a PR instead of force-overwriting

## Legacy/manual fallback (script)

If you are not using auto-install yet, use:

```bash
./scripts/onboard-action-repo.sh \
  --repo <owner/target-repo> \
  --action-ref action-v1
```

This legacy flow remains available for manual onboarding.

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
