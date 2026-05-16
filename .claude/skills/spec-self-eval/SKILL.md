---
name: spec-self-eval
description: Validate a feature spec (`.specs/<feature>/requirements.MD`, `design.MD`, `tasks.MD`) against `.specs/_eval-checklist.MD` plus a clarity/level-of-detail review. Emits a PASS / FAIL / WEAK report and saves it to `.specs/<feature>/eval-report-<YYYY-MM-DD>.md`. Trigger when the user asks to "evaluate / self-eval / audit / score / check / lint the spec" for a feature under `.specs/`, or invokes `/spec-self-eval`.
---

# spec-self-eval

Self-evaluate a feature specification under `.specs/<feature>/` against the repo's checklist plus a clarity / level-of-detail review, and write a dated report next to the spec.

## Inputs

- **Feature** (required): the subdirectory , e.g. `query-api`. If the user does not name one, list the candidate directories under `.specs/` and ask which to evaluate. If exactly one feature directory exists, use it without asking.
- **Checklist file** (optional): defaults to `.specs/_eval-checklist.MD`. Fall back to `.specs/_eval-checklist.md` (lowercase) if the uppercase variant is absent. If neither exists, run only the clarity/level-of-detail review and note the missing checklist in the report.

## Files to read

For feature `<feature>`:

1. `.specs/_eval-checklist.MD` (or `.md`) — one rule per non-empty line. Blank lines and lines starting with `#` are ignored.
2. `.specs/<feature>/requirements.MD`
3. `.specs/<feature>/design.MD`
4. `.specs/<feature>/tasks.MD`

If any of the three spec files is missing, record it as `FAIL` for every checklist item that depends on it and continue — do not abort.

## Evaluation

For **each** checklist line, emit exactly one verdict:

- `PASS` — the spec clearly satisfies the rule. Cite the strongest piece of evidence (section, AC id, task id, or quoted phrase).
- `WEAK` — partially satisfied: most of the spec meets the rule but at least one concrete gap exists. Name the gap.
- `FAIL` — the rule is not satisfied, or required content is missing.

In addition to the checklist, **always** evaluate these two clarity/level-of-detail axes and include them as extra rows in the report table:

- **Clarity** — Are requirements, design, and tasks written so a new engineer can act without asking follow-up questions? Watch for: undefined acronyms, ambiguous pronouns ("it", "they"), vague verbs ("handle", "support"), missing units, mixed tenses, undefined terms.
- **Level of detail** — Is the depth appropriate for the audience: requirements stay at the "what / why", design covers the "how" with enough specificity to implement (data shapes, endpoints, error modes), tasks are sized so each is independently reviewable. Flag both under-specification (hand-wavy) and over-specification (implementation details bleeding into requirements).

## Evidence rules

- Each verdict needs **one line** of evidence that names a concrete locator: a section number, AC id, task id, or short quoted phrase (≤15 words). No vague evidence like "looks fine".
- For `WEAK` / `FAIL`, the evidence must point at the specific gap (the missing AC, the section that hand-waves, the task without DoD, etc.), not just restate the rule.
- Do not invent ids. If you cite an AC or task, it must literally appear in the file.

## Output format

Write the report to `.specs/<feature>/eval-report-<YYYY-MM-DD>.md` using today's date. If a report with the same date already exists, append `-2`, `-3`, … to the filename rather than overwriting.

Template:

```markdown
# Eval report — .specs/<feature>/

**Date**: <YYYY-MM-DD>
**Checklist**: `.specs/_eval-checklist.MD`
**Scope**: `.specs/<feature>/` (requirements.MD, design.MD, tasks.MD)

| # | Item | Verdict | Evidence |
|---|------|---------|----------|
| 1 | <checklist item 1>           | PASS/WEAK/FAIL | <one line, with locator> |
| 2 | <checklist item 2>           | PASS/WEAK/FAIL | <one line, with locator> |
| … | …                            | …              | …                        |
| C | Clarity                      | PASS/WEAK/FAIL | <one line, with locator> |
| D | Level of detail              | PASS/WEAK/FAIL | <one line, with locator> |

## Summary

- **PASS**: <count> · **WEAK**: <count> · **FAIL**: <count>
- Overall: <PASS if no FAIL and ≤1 WEAK · WEAK if no FAIL but ≥2 WEAK · FAIL if any FAIL>

## Notes

- For each WEAK / FAIL row, a 1–3 sentence note: what is missing, where to add it, and the smallest concrete edit that would lift the verdict.
- Optional: ≤3 forward-looking suggestions that are not checklist items but would strengthen the spec.
```

Keep the report under ~120 lines. No emojis. No commentary outside the template.

## After writing the report

Reply to the user with: the report path, the PASS / WEAK / FAIL counts, and the overall verdict — nothing else. Do not paste the report body back into chat.

## Do not

- Do not edit `requirements.MD`, `design.MD`, or `tasks.MD` as part of this skill. The skill is read-only against the spec; the only file it writes is the report.
- Do not create new checklist rules in the report. The checklist file is the source of truth; clarity and level-of-detail are the only two extra axes this skill adds.
- Do not run tests, build the project, or touch source code under `src/`.