# Parkable — Multi-Agent Team Instructions

**Every AI agent working in this repo (Claude Code, Codex, GitHub Copilot) must follow this file.**
Codex reads this automatically; Copilot gets it via `.github/copilot-instructions.md`; Claude Code via `CLAUDE.md`.

## The Project (30 seconds)

Parkable answers **"Can I park here right now?"** — LLM extracts parking rules from sign photos into JSON (perception), a deterministic Java rules engine produces the verdict (decision). The LLM NEVER decides the verdict. Read `CLAUDE.md` for the full picture, `docs/schema.md` for the canonical JSON schema, `docs/plans/phase1-rules-engine.md` for Phase 1 component specs.

## Team Roles

| Agent | Role |
|-------|------|
| **Claude Code** | Manager + backend integration. Owns build health, reviews other agents' work, resolves conflicts. Ask it "what's the project status?" |
| **Codex** | Backend modules (repository, datasource, SQL) per assigned tasks |
| **Copilot** | Mobile app (`mobile/`) and evals (`evals/`) per assigned tasks |

## Coordination Protocol (MANDATORY)

1. **`PROGRESS.md` is the single task board.** Before starting anything, read it. Claim a task by setting its status to `IN_PROGRESS` with a timestamp; set `DONE` only when the Definition of Done below is met. Append one line to the Coordination Log when you start and when you finish.
2. **Edit only your own rows** in PROGRESS.md and only files inside your ownership area (table below). Never edit another agent's task rows, code, or tests — even to "fix" something. If another agent's code blocks you, file it under **Requests & Blockers** in PROGRESS.md and move on to your next task.
3. **Do only tasks assigned to you.** Ideas for new work go in Requests & Blockers, not into code.
4. **Never block on another agent.** Every assigned task is designed to be independently completable. Interfaces between areas are specified in `docs/plans/phase1-rules-engine.md` — code to the spec, not to another agent's work-in-progress.

## File Ownership

| Area | Owner | Notes |
|------|-------|-------|
| `backend/pom.xml` | **Claude Code ONLY** | Need a dependency? Add a row to Requests & Blockers — do not edit |
| `backend/src/main/java/com/parkable/{model,engine,calendar,builder,factory,validation,extraction}` + their tests | Claude Code | Frozen for others; read freely |
| `backend/src/main/java/com/parkable/cli` + tests | Claude Code | Stage C |
| `backend/src/main/java/com/parkable/lambda` + tests | Claude Code | Phase 2 — handlers + ports |
| `backend/src/main/java/com/parkable/repository` + tests | Codex | Task X1 |
| `backend/src/main/java/com/parkable/repository/postgres` + tests | Codex | Phase 2 Task X4 |
| `backend/src/main/java/com/parkable/datasource` + tests | Codex | Task X2 |
| `backend/sql/` | Codex | Tasks X3, X5 |
| `infra/` (SAM template, deploy docs) | Copilot | Phase 2 Task P3 — greenfield |
| `mobile/` | Claude Code (since Phase 3, 2026-07-21, user decision) | Was Copilot (P1/P4 scaffold); Phase 3 build-out C16-C19 |
| `evals/` | Copilot | Task P2 |
| `docs/`, `CLAUDE.md`, `AGENTS.md`, schema JSON resource | **Claude Code ONLY** | Schema changes are breaking — request via PROGRESS.md |
| `PROGRESS.md` | All | Own rows + log appends only |

## Build & Toolchain (Windows, no admin rights)

- **JDK 25**: `C:\Users\018316532\tools\jdk-25.0.2` (project upgraded from 21 on 2026-07-16)
- **Maven 3.9.16**: `C:\Users\018316532\tools\apache-maven-3.9.16`
- The user-level `JAVA_HOME` may still point to JDK 21. In every new terminal session run:
  ```powershell
  $env:JAVA_HOME = 'C:\Users\018316532\tools\jdk-25.0.2'
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  ```
- Build/test: `mvn test -f backend\pom.xml` (from repo root). The suite must be green (currently 113 tests) before you mark any backend task DONE.

## Architecture Rules (violations fail code review)

1. **Lambda handlers / CLI mains: zero business logic** — core logic runs outside entry points unchanged.
2. **`SignSource` + `VisionExtractor` are interfaces** — adding a source/provider must not touch the engine.
3. **The rules engine takes the evaluation instant as a parameter** — `Instant.now()` is allowed ONLY at the CLI entry boundary. Grep before you commit.
4. **Every stored rule carries source + parser_version tags** — reproducibility is a hard requirement.

## Code Quality Bar

- Java 21+ idioms: records, sealed interfaces, immutability, `java.time` only (no Joda).
- Composition over inheritance; SOLID; design patterns used deliberately (Strategy, Factory, Builder, Decorator, Repository — see `docs/plans/phase1-rules-engine.md` §3).
- **Comment the WHY, not the WHAT** — only where logic is non-obvious (temporal edge cases, performance constraints).
- Test-first for logic-bearing code: JUnit 5 + AssertJ (+ Mockito where needed). Match the style of existing tests in `backend/src/test`.
- Mobile: TypeScript, Expo; keep API calls in `mobile/services/api.ts`; no business logic in components (verdicts come from the backend, never computed on-device).

## Version Control (local git — added 2026-07-16; review gate added 2026-07-17)

- The repo is **local-only by the user's explicit choice**: never add a remote, never push, never suggest publishing.
- **Codex and Copilot do NOT commit.** Finish your task, mark it DONE in PROGRESS.md, and leave your files in the working tree. Claude Code reviews every deliverable (correctness, tests, architecture rules) and commits it after the review — with your agent name in the commit message. This is the user-mandated quality gate.
- Claude Code: stage specific paths per review — never `git add -A` blindly (in-progress work from another agent may be in the tree).
- Never rewrite history (`rebase`, `commit --amend` on shared commits, `reset --hard`).

## Definition of Done (per task)

- [ ] Code + tests written, `mvn test -f backend\pom.xml` fully green (backend) / `npx tsc --noEmit` clean (mobile)
- [ ] Only files in YOUR ownership area were created/modified
- [ ] No architecture rule violated
- [ ] PROGRESS.md: your task row set to `DONE` with timestamp + one-line summary, log line appended
