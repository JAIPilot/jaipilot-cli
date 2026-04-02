# JAIPilot GitHub App Bootstrap Service

This service lets a GitHub App automatically onboard repositories to JAIPilot by:

- creating/updating `.github/workflows/jaipilot-generate.yml`
- pointing that workflow to your published action reference
- optionally dispatching workflow runs after install or pull request events

## Why this exists

Without this service, every repository must manually add workflow YAML.  
With this app, installation can auto-bootstrap repositories with no per-repo YAML editing.

## Required GitHub App permissions

Repository permissions:

- `Contents: Read and write` (required to create/update workflow file)
- `Actions: Read and write` (required only if dispatch is enabled)

Webhook events:

- `installation`
- `installation_repositories`
- `pull_request` (needed only for pull-request-time bootstrap/dispatch)

## Environment setup

Copy `.env.example` to `.env` and fill values:

```bash
cp .env.example .env
```

Required values:

- `GITHUB_APP_ID`
- `GITHUB_WEBHOOK_SECRET`
- `GITHUB_APP_PRIVATE_KEY` or `GITHUB_APP_PRIVATE_KEY_PATH`
- `JAIPILOT_ACTION_REPO` and `JAIPILOT_ACTION_REF`

## Run locally

```bash
npm install
npm run dev
```

Server endpoints:

- webhook: `${WEBHOOK_PATH}` (default `/api/webhook`)
- health: `/healthz`

For local webhook forwarding, use Smee or ngrok and configure the GitHub App webhook URL to your forwarded endpoint.

## Behavior flags

- `JAIPILOT_BOOTSTRAP_ON_INSTALL=true`
- `JAIPILOT_BOOTSTRAP_ON_PULL_REQUEST=true`
- `JAIPILOT_DISPATCH_ON_INSTALL=false`
- `JAIPILOT_DISPATCH_ON_PULL_REQUEST=false`

Recommended default:

- bootstrap enabled
- dispatch disabled (the generated workflow already runs on `pull_request`)

## Important limitation

This app does not set `JAIPILOT_LICENSE_KEY` secrets in repositories.  
You still need to provide that secret in each onboarded repository (or automate it separately via GitHub API + sealed box encryption).
