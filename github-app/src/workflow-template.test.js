import test from "node:test";
import assert from "node:assert/strict";

import { renderWorkflowTemplate } from "./workflow-template.js";

test("renders workflow with action reference and GitHub expressions", () => {
  const output = renderWorkflowTemplate("acme/jaipilot-cli@action-v1");
  assert.match(output, /uses: acme\/jaipilot-cli@action-v1/);
  assert.match(output, /\${{ github\.head_ref }}/);
  assert.match(output, /workflow_dispatch:/);
});

test("rejects invalid action reference", () => {
  assert.throws(() => renderWorkflowTemplate("invalid-reference"));
});
