const WORKFLOW_TEMPLATE = `name: JAIPilot Generate

on:
  pull_request:
    types:
      - opened
      - synchronize
      - reopened
  workflow_dispatch:
    inputs:
      source:
        description: Trigger source
        required: false
        type: string
        default: manual
      ref:
        description: Branch or tag to run (optional)
        required: false
        type: string
        default: ""

permissions:
  contents: write
  pull-requests: write

concurrency:
  group: jaipilot-generate-\${{ github.repository }}-\${{ github.ref_name }}
  cancel-in-progress: true

jobs:
  generate-tests:
    if: \${{ github.event_name == 'workflow_dispatch' || (github.actor != 'github-actions[bot]' && github.event.pull_request.head.repo.full_name == github.repository) }}
    runs-on: ubuntu-latest
    steps:
      - name: Checkout PR branch
        if: \${{ github.event_name == 'pull_request' }}
        uses: actions/checkout@v4
        with:
          ref: \${{ github.head_ref }}

      - name: Checkout dispatch ref
        if: \${{ github.event_name == 'workflow_dispatch' && inputs.ref != '' }}
        uses: actions/checkout@v4
        with:
          ref: \${{ inputs.ref }}

      - name: Checkout current ref
        if: \${{ github.event_name == 'workflow_dispatch' && inputs.ref == '' }}
        uses: actions/checkout@v4

      - name: Run JAIPilot generate and push changes
        uses: __ACTION_USES__
        with:
          jaipilot-license-key: \${{ secrets.JAIPILOT_LICENSE_KEY }}
`;

export function renderWorkflowTemplate(actionUses) {
  if (!actionUses || !actionUses.includes("/")) {
    throw new Error(
      `Invalid action reference '${actionUses}'. Expected '<owner>/<repo>@<ref>'.`
    );
  }
  return WORKFLOW_TEMPLATE.replace("__ACTION_USES__", actionUses);
}
