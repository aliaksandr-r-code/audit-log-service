# Delta: query-api multi-actor requirements

Date: 2026-05-16

Scope: `.specs/query-api/requirements.MD`, `.specs/query-api/design.MD`, and `.specs/query-api/tasks.MD`.

Assumption: original tasks T1-T9 are already implemented and must not be changed. The current requirements/design add multi-actor query behavior after that baseline, so implementation work must happen through follow-up tasks T10-T15.

## Open deltas

| ID | Gap | Requirement / design source | Current baseline mismatch | Closing task |
|----|-----|-----------------------------|---------------------------|--------------|
| D1 | `actor` must accept a comma-separated list. | requirements.MD AC-1.1a, CR-1; design.MD section 3.1 | Implemented baseline treats `actor` as one optional exact-match string. | T11, T12, T13, T14 |
| D2 | Actor values must be trimmed, de-duplicated, and matched case-insensitively. | requirements.MD AC-1.1a, AC-1.1b, CR-1; design.MD section 5.1 | Implemented baseline does not normalize actor lists or compare `lower(actor)` against normalized values. | T11, T12, T14 |
| D3 | Empty actor tokens and more than 10 distinct actors must return HTTP 400. | requirements.MD CR-3.1; design.MD section 3.3 | Implemented baseline validation covers time window, offset, and page size only. | T11, T13, T14 |
| D4 | Multi-actor filtering must remain deterministic under pagination. | requirements.MD AC-3.6; design.MD section 4 and section 5.1 | Implemented baseline pagination tests do not cover OR-combined actor filters or non-page-aligned raw offsets with actor lists. | T12, T14 |
| D5 | Case-insensitive multi-actor query path needs an explicit index plan. | requirements.MD CR-4.3; design.MD section 5.2 | Implemented baseline has V3 `(occurred_at, id)` and `(aggregate_id, occurred_at, id)` indexes only. | T10, T14 |
| D6 | Performance verification must cover the new multi-actor path. | requirements.MD CR-4.1, CR-4.2, CR-4.3; design.MD section 7 | Implemented baseline performance check does not prove the `(lower(actor), occurred_at, id)` path. | T10, T14 |
| D7 | README must document actor-list semantics and actor validation errors. | requirements.MD CR-1, CR-3, CR-6; design.MD section 3.1 and section 3.3 | Implemented baseline README task documents the query endpoint but not comma-separated actor behavior. | T15 |

## Historical baseline notes

- T1-T9 remain as implemented baseline tasks. Their older single-actor wording is not edited because changing them is forbidden.
- The follow-up task section in tasks.MD is the active implementation plan for these deltas.
- The public endpoint still caps `pageSize` at 200. The 500-row CR-4 check is an internal stress benchmark below controller validation, not a public API request contract.

## Expected completion signal

The delta is closed when T10-T15 are implemented and the new integration tests covering actor-list behavior pass under the project integration-test task.
