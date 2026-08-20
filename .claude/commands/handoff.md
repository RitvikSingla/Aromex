---
description: Generate the standardized handoff report for a finished ticket, from the real git diff
argument-hint: <issue-number>
---
Produce a handoff report for GitHub issue #$1, based on the ACTUAL changes — not your memory.

1. Find the base branch (usually `master`). Run `git diff <base>...HEAD --stat` and review the full diff.
2. Write the report to `handoffs/ticket-$1.md` with these sections:
   - **Ticket:** #$1 — <title>
   - **Summary:** what was built, in 3–6 sentences.
   - **Files changed:** grouped by area (shared logic / android / ios / desktop / server / config), each with a one-line "why".
   - **How to test:** exact steps a reviewer runs to verify locally.
   - **Acceptance criteria:** list each criterion from the ticket and mark met / not met.
   - **Deviations / decisions:** anything done differently from the ticket, and why.
   - **Open questions / follow-ups:** anything left for review or a future ticket.
3. Every statement must be supported by the diff. Do not claim work that isn't in the changes.
4. Commit the report, then remind me to open a PR (the PR template links to this file).
