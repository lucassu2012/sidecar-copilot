# Local validation record

Observed 2026-11-10 in `sidecar-uvc-licenses`. These are provenance-tool checks, not new PRD acceptance-test identifiers.

## Successful locked offline replay

```powershell
python -B android\uvc-sim\provenance\transitives\audit_transitives.py --scan-artifacts
```

Exit code **0**, using existing ignored cache only, with the tracked input pins enforced. Result:

| Observation | Result |
| --- | ---: |
| POM compile/runtime coordinate versions | 119 |
| POM models including parents and imported BOMs | 124 |
| Present Gradle module files | 51 |
| Module coordinates confirmed 404 in both repositories | 68 |
| Unknown module availability | 0 |
| Modules with multiple literal versions | 24 |
| Main JAR/AAR archives inspected | 119 |
| ZIPs opened including one nested level | 199 |
| File names examined | 22,070 |
| Notice-name matches | 0 |
| Missing POMs / missing or failed main archives | 0 / 0 |
| Issues / relevant unactivated profiles | 0 / 0 |
| Successful pinned raw resources | 299 |
| Repository-specific pinned 404 observations | 136 |

Generated status: `complete_for_static_audit_scope`; `actual_gradle_resolution` and `distribution_clearance` remain `not_performed`. The five extra JVM target coordinates listed in the report are not recursively traversed. Zero filename matches is a limited archive-name observation, not a full source/native/license clearance.

The intermediate replay with six concurrent ZIP scans failed with `MemoryError: Unable to allocate output buffer` before producing a new report. Archive inspection was changed to sequential decompression and metadata concurrency reduced to three. The successful final replay above uses that implementation. No downloaded code was executed and no new platform graph was fetched to obtain this result.

## Parser and integrity checks

```powershell
python -B -m unittest discover -s android\uvc-sim\provenance\transitives -p test_audit_transitives.py -v
```

Exit code **0**, **13 checks passed**. These are 13 total parser/integrity/ZIP checks, not 13 separate negative scenarios. All inputs are synthetic; temporary copies are confined to the ignored cache subtree.

| Check | Observed result |
| --- | --- |
| `test_bom_over_parent_and_local_over_bom` | Child imported management and direct overrides retain the expected declared versions. |
| `test_cache_tampering_is_rejected` | Payload changed with original cache receipt raises hash mismatch. |
| `test_confirmed_404_is_distinct_from_transport_error` | Both 404 records give confirmed absence; replacing one with transport failure gives unknown. |
| `test_dynamic_ranges_remain_unresolved` | General ranges, dynamic/property versions and snapshots are not silently treated as concrete versions. |
| `test_entities_rejected` | DOCTYPE input is rejected. |
| `test_input_lock_bootstrap_never_overwrites` | Existing lock remains unchanged and initialization fails. |
| `test_license_and_property_inheritance` | Parent license origin remains recorded; child property overrides inherited dependency value. |
| `test_offline_missing_cache_is_unknown_not_404` | Missing cached module receipts are reported unknown, not absent. |
| `test_offline_missing_inputs_report_incomplete_and_fail` | End-to-end offline empty-cache fixture returns exit 2 and writes incomplete coverage with missing POM/module issues. |
| `test_payload_and_receipt_tampering_rejected_by_tracked_pin` | Changing payload and adjacent receipt together is rejected against the separate tracked pin. |
| `test_profiles_recorded_not_activated` | Profile dependencies are retained without silently activating the profile. |
| `test_scope_optional_exclusion_and_singleton` | Optional compile edge is retained, exclusion recorded, test edge not traversed, singleton range normalized explicitly. |
| `test_zip_notice_scan_counts_nested_entries` | Synthetic nested JAR notice and file-name counters are inspected without extracting/executing entries. |

## Output identities after successful replay

| File | SHA-256 |
| --- | --- |
| `INPUT_LOCK.json` | `4b3671b5162142e095445087d077f02e274540f8e374f080d4acde25e0c0f74e` |
| `inventory.json` | `9d1708a62018a89cbe8b3acd4c18d7c374ceb52b2c696089b013e6722c0039ae` |
| `REPORT.md` | `ed772e16667548401236746d2bcb919b09bba8fa60da254bc1965208f158bb21` |

The input lock's bootstrap hash refers to the preceding reviewed inventory used for its explicit first creation, not to the newer inventory that now records the input-lock hash. This is intentional and avoids a circular hash dependency. No lock refresh occurs during replay.
