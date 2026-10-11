---
paths:
  - "backend/src/**"
  - "frontend/src/**"
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "frontend/{package.json,angular.json,proxy.conf.json,tsconfig*.json}"
  - ".claude/launch.json"
---

# Docs sync — docs are part of every code change

A change to code, config or build is **not done** until the docs that describe it are true again. Update them in
the same change, before reporting completion; never batch doc fixes "for later". Edit only the affected lines; link
instead of copying between docs.

| code change | update |
|---|---|
| file added / removed / renamed / moved | its line in `docs/map/backend.md` or `docs/map/frontend.md` (link, role, key symbols); `INDEX.md` task router if it is an entry point for a kind of change |
| public type, key method, SPI or bean added/renamed | symbol list on that file's map line; ARCHITECTURE §2 (backend) / §4 (frontend) if the package role changed |
| WS message, record field, HTTP route, query/patch semantics | ARCHITECTURE §1/§3 (see also `wire-contract.md`) |
| user-visible behaviour (feature, filter, sort, shortcut, setting) | SPEC row (status ✅/◐/○ + evidence); README if users run/configure/press it |
| config key, command, port, toolchain, build step | README table; root `CLAUDE.md` commands; `application.yml` comment |
| new convention, invariant or gotcha | `backend/CLAUDE.md` or `frontend/CLAUDE.md` (one line, why included) |
| PLAN item started / finished, or new gap found | `PLAN.md` (tick/move it, update **State** date and test counts if checks ran) |
| costly-to-undo design choice | `PLAN.md` Decisions row |
| product direction change | `INTENT.md` (only then) |

Before finishing: `git status` → for each changed source file, confirm its map line still matches; check new doc links
resolve. Pure refactors with unchanged names and behaviour need no doc change — say so in the report.
