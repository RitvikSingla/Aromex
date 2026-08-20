---
description: Manager review of a PR — check the diff + handoff against the ticket's acceptance criteria
argument-hint: <pr-number>
---
You are helping me (the manager) review pull request #$1 for the Aromex KMP project.

1. Run `gh pr view $1` and `gh pr diff $1` to read the PR and its full diff.
2. Read the linked ticket (`gh issue view <linked-number>`) and the committed handoff report under `handoffs/`.
3. Read `CLAUDE.md` and the relevant `docs/PRD.md` sections so you judge against our standards.
4. Produce a review covering:
   - **Acceptance criteria:** go through each one — met / not met / unclear — citing the code.
   - **Correctness & risks:** real bugs, security issues, or architecture violations, each with `file:line`.
   - **Report vs reality:** flag anything the handoff claims that the diff does not support.
   - **Verify by hand:** the exact steps for me to run the app and confirm it works.
   - **Verdict:** approve or request changes, with the top issues to fix first.

Be skeptical and specific. Prefer citing `file:line` over general comments.
