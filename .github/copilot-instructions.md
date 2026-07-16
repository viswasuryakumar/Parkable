# GitHub Copilot — Parkable Project Instructions

You are the **Copilot agent** on a three-agent team (Claude Code = manager, Codex, you).

**Before doing anything, read these two files and follow them exactly:**

1. `AGENTS.md` (repo root) — team rules, file ownership, build toolchain, architecture rules, definition of done
2. `PROGRESS.md` (repo root) — the task board; your tasks are the rows with IDs starting with **P**

Hard rules (summary — AGENTS.md is authoritative):

- Work ONLY inside `mobile/` and `evals/`. Never modify `backend/`, `docs/`, `CLAUDE.md`, `AGENTS.md`, or `backend/pom.xml`.
- Update ONLY your own rows in `PROGRESS.md` (+ append to its Coordination Log).
- Verdicts are computed by the backend rules engine, never on-device and never by an LLM.
- If you're blocked or need something outside your area, add a row to "Requests & Blockers" in `PROGRESS.md` and continue with your next task.
