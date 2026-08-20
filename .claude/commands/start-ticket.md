---
description: Start a ticket — read context, offer a walkthrough + Q&A, then plan, then code
argument-hint: <issue-number>
---
You are helping a developer start GitHub issue #$1 for the Aromex KMP project. The developer may be junior or unfamiliar with parts of the system — your FIRST job is to make sure they understand the ticket before any code is written.

## 1. Load the context (silently)
- Run `gh issue view $1` and read the ticket in full.
- Read `CLAUDE.md` (project rules + architecture) and the PRD sections the ticket references (`docs/PRD.md`).
- Read the existing code/modules the ticket touches, so everything you say is grounded in what already exists.

## 2. Walkthrough + Q&A (training mode) — DO THIS BEFORE PLANNING
- Give a short, plain-language walkthrough of the ticket: what we're building, why it matters, how it fits the bigger system, and any concept or term the ticket assumes (e.g. "gateway", "Firebase", "double-entry").
- Then STOP and ask, in these words or similar: **"Before we start — want me to explain any part of this, or any concept you're unsure about? Ask me anything, or just say 'ready' and I'll move to the plan."**
- **Wait for the developer's reply. Do NOT proceed to planning until they explicitly say they're ready** (e.g. "ready", "let's go", "no questions").
- Answer their questions clearly and simply, grounded strictly in this ticket, the PRD, and the codebase — explain only as much as they need to do this ticket. After answering, ask if they have more questions. Keep looping until they say they're ready.

## 3. Plan
- Present a concise step-by-step plan and the files you expect to change. Ask the developer to confirm or adjust. **Do NOT write code yet.**

## 4. Build
- After approval, create a branch `ticket-$1-<short-slug>`, then implement step by step, keeping all changes scoped to this ticket.

## 5. Hand off
- When the work is done and verified, run `/handoff $1`.

Follow the architecture and conventions in `CLAUDE.md` strictly. The developer can ask you questions at ANY point — not just step 2 — so if they ask something mid-plan or mid-build, just answer it. If the ticket is ambiguous or conflicts with the PRD, stop and ask before proceeding.
