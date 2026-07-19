# Capability v14 authorization-validator audit

Date: 2026-07-16 (Asia/Seoul)

## Exact audited bytes

| File | SHA-256 |
| --- | --- |
| `scripts/validate_capability_policy_authorization.py` | `ad3f77b89a9c0444cf266dea4b9ce3de3118fa1023029276e1060ebde0a8b0bb` |
| `scripts/tests/test_validate_capability_policy_authorization.py` | `8a4314a26a4afadafd3fe27546af24ee0deca6192fa804de9ec5c98e645015c5` |

## Independent result

- Verdict: `APPROVE`
- Findings: P0=0, P1=0, P2=0
- Independent Windows unittest: 43/43 passed in 64.326 seconds
- Independent WSL unittest: 43/43 passed in 53.192 seconds
- Independent `py_compile`: passed
- Additional adversarial checks: a forged `H` using both graft and replace was
  rejected; duplicate-key and lone-surrogate review JSON was rejected; non-string
  candidate/head inputs returned controlled fail-closed results.

The audit covers the formal object-only candidate/authorization validator. Live
symbolic `HEAD`, index, worktree, status, and rollback-tag ref checks are deliberately
outside that verdict and remain separate operational stop conditions.

No staging, commit, authorization record, implementation authorization, Android
implementation, physical-device result, human-QA result, G2, G6, or G8 is asserted by
this audit.
