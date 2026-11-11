# Published UVCAndroid dependency provenance

This directory records a static declaration inventory for `com.herohan:UVCAndroid:1.0.13`. It does not add a dependency to Sidecar, execute third-party code, resolve an Android Gradle configuration, clear redistribution, or demonstrate hardware support. Downloaded metadata and archive bytes live only in the ignored `android/uvc-sim/build/provenance/transitives/` cache.

The inspected publication has 119 distinct coordinate/version declarations in its conservative Maven compile/runtime union, including optional edges and conflicting versions. There are 124 POM models including parents and imported BOMs, 24 modules with multiple declared versions, 51 present Gradle module files, and 68 module coordinates whose two queried repositories both returned 404. The 119 main JAR/AAR files were inspected as ZIP data. `REPORT.md` provides the table; `inventory.json` preserves exact dependency edges, exclusions, scope, origin, effective POM licenses, variant declarations, archive identities, and coverage status.

## Identity and release binding

The published [POM](https://repo.maven.apache.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.pom) and [Gradle module metadata](https://repo.maven.apache.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.module) declare these direct dependencies:

| Coordinate | Effective POM declaration | Official metadata |
| --- | --- | --- |
| androidx.core:core:1.13.1 | Apache 2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/core/core/1.13.1/core-1.13.1.pom) |
| androidx.legacy:legacy-support-v4:1.0.0 | Apache 2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/legacy/legacy-support-v4/1.0.0/legacy-support-v4-1.0.0.pom) |
| androidx.appcompat:appcompat:1.6.1 | Apache 2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.6.1/appcompat-1.6.1.pom) |
| androidx.annotation:annotation:1.8.0 | Apache 2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.8.0/annotation-1.8.0.pom) |
| cn.hutool:hutool-core:5.8.35 | Mulan PSL 2 inherited from parent; incomplete as a source-license inventory | [Core POM](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.pom), [parent POM](https://repo.maven.apache.org/maven2/cn/hutool/hutool-parent/5.8.35/hutool-parent-5.8.35.pom) |

The release [library build](https://github.com/shiyinghan/UVCAndroid/blob/6018c7f20238832929cf61427427bb7828056260/libuvccamera/build.gradle) at tag commit `6018c7f20238832929cf61427427bb7828056260` declares the same five external coordinates. The later source commit `fcdae5f9a194e2111d57019176b53470a120415b` already removed Hutool; it is not the source version to associate with this publication's declarations. Source archive correspondence and the intervening removal commit are documented in the separate [Hutool provenance report](../hutool/README.md). The fixed release build file also has a local `libs/*.jar` input: this Maven graph alone cannot prove the complete producing build inputs or source-to-native-binary correspondence.

| Published root input | SHA-256 |
| --- | --- |
| POM | `5b1ff27d872d1fa3720fa1e367bab05d6cf422695e7457fb44b5934be005add7` |
| Gradle module | `f22e2960d4eca439c19eebdc82d4ea51450735335fb889cded6511e1b07de20b` |
| AAR | `4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17` |

All other metadata and archive hashes and exact official URLs are in `inventory.json` and `INPUT_LOCK.json`. The latter pins 435 input observations: 299 successful resources with SHA-256 and byte count, and 136 repository-specific 404 observations. A stored 404 means absent at that queried URL when observed, not globally or permanently nonexistent. No signatures or independent publisher attestations were verified.

## Replay and integrity

Python standard library only; run from the worktree root:

```powershell
python -B android\uvc-sim\provenance\transitives\audit_transitives.py --scan-artifacts
python -B android\uvc-sim\provenance\transitives\audit_transitives.py --online --scan-artifacts
python -B -m unittest discover -s android\uvc-sim\provenance\transitives -p test_audit_transitives.py -v
```

The first command is strictly offline. The second may fetch missing pinned resources from Google Maven, Maven Central, or fixed raw GitHub URLs. Neither command invokes Maven, Gradle, a compiler or an upstream script. Metadata requests use at most three workers; archive decompression is sequential. Main archive downloads are bounded at 32 MiB; named notice reads at 2 MiB; nested JAR reads at 24 MiB and one level. Exceeding an inspection bound is recorded as incomplete or fails, rather than silently claiming full coverage.

The tracked input lock is mandatory during normal replay. Both cached receipts and downloaded payloads must agree with its URL, response status, SHA-256 and byte count. Changing both a cache payload and its adjacent receipt still fails against the separate tracked pin. The lock is evidence of the reviewed bytes, not a Gradle version-resolution lockfile and not proof of authenticity. Review its changes like code; an attacker able to alter every tracked evidence file is outside this check's trust boundary.

The existing lock was explicitly bootstrapped once from the previously generated complete static inventory, validating the cached bytes against that inventory. `--initialize-input-lock-from-inventory` records the source inventory hash and refuses to overwrite an existing lock. Routine replay never refreshes pins or treats a new checksum as reviewed. Changed upstream inputs require a separate reviewed update; do not delete the lock to make a failure disappear.

Missing offline cache entries are `not_cached`/unknown, not 404. Transport failures and HTTP errors other than confirmed 404 remain unknown. Missing POMs, parent/BOM inputs, module availability, source snapshots, or requested archives produce issues and `coverage.overall = incomplete`, with exit code 2. Integrity mismatches and malformed archives terminate with a nonzero exit status; an existing report is then the previous successful run, not a new successful result. The 13 synthetic checks cover inheritance/management precedence, range handling, profiles, XML entity rejection, missing cache, 404 versus transport failure, payload tampering, payload-plus-receipt tampering, lock overwrite refusal, and bounded nested notice scanning.

## Interpretation limits and next gate

The POM traversal deliberately retains every literal compile/runtime version and optional dependency. It records but does not apply path-specific exclusions; it does not select the highest version. Singleton ranges such as `[1.0.0]` are recorded and normalized to that exact version. General ranges, dynamic versions, unresolved properties and relevant profiles are issues. Test/provided/system edges are retained in models but are not runtime traversal edges. Parent and BOM license/management inheritance is inspected; this is an explicitly limited static model rather than a reimplementation of every Maven/Gradle rule.

There are five additional metadata-only models: Hutool parent 5.8.35, Guava parent 26.0-android, coroutines BOM 1.6.1 and 1.6.4, and Sonatype oss-parent 9. The Sonatype parent has no license declaration in the inspected POM; this says nothing by itself about the runtime artifacts that name it. Models preserve declaration origins instead of silently assigning a universal component license.

Every present `.module` keeps its complete variants, constraints, dependency declarations, file metadata and `available-at` references. `additional_gradle_jvm_candidates_not_traversed` identifies literal JVM-compatible targets outside the POM runtime union, including coroutines core umbrella coordinates. They are candidates, not resolved artifacts; constraints, BOM alignment, Gradle capabilities, classifiers, redirects and variant selection still need actual resolution. Native/JS platform variants are not recursively traversed. Consequently `complete_for_static_audit_scope` means only the documented POM union and its requested metadata/archive inspection completed without retrieval gaps; actual Gradle resolution and distribution clearance are explicitly `not_performed`.

Archive scans search entry names for LICENSE/NOTICE/COPYING/COPYRIGHT and inspect one nested JAR level, recording archive/name counts and hashes. Zero matches across this snapshot do not prove absence of license text embedded under other names, source headers, class constants or native libraries. Hutool is a concrete counterexample: its POM inherits Mulan PSL 2, but `InstanceComparator`, `CsvParser`, and `CsvTokener` contain Apache headers, while `NeuQuant` retains Anthony Dekker's distinct grant. Follow the [source-level Hutool evidence](../hutool/README.md) and preserve applicable notices; this inventory alone cannot clear any entire component.

The next isolated experiment should use a reviewed fixed source candidate and a minimal, non-publishing Android dependency-resolution harness. Pin the Gradle distribution, Android plugin, repositories and input verification metadata; resolve the intended API/runtime variant for the chosen target SDK; capture selected components, selection reasons, constraints, artifacts and dependency verification hashes. Compare that result with these declarations, then inspect final source/license material and generated manifests/native inputs. The experiment must not execute unreviewed upstream build logic or connect a device. No such experiment ran in this task. Source/native provenance, final licensing, 16 KiB runtime behavior and real-device compatibility remain separate open gates.

## AndroidUSBCamera comparison seeds only

Fixed source: `jiangdongguo/AndroidUSBCamera` commit `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`. The [root build](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/build.gradle) declares version name `3.3.3`; that does not attest any published binary. The exact four inspected Gradle-file hashes and original declaration lines are under `candidate_source_evidence` in JSON.

| Source module | External runtime declarations / project edges |
| --- | --- |
| [libausbc](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libausbc/build.gradle) | appcompat 1.3.1; constraintlayout 2.0.4; material 1.3.0; xlog 1.11.0; project `:libuvc`; project `:libnative` |
| [libuvc](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/build.gradle) | appcompat 1.3.1; xlog 1.11.0; generated build/libs JAR file tree |
| [libnative](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libnative/build.gradle) | core-ktx 1.3.2; appcompat 1.2.0; material 1.3.0 |

Coordinates use `androidx.appcompat:appcompat`, `androidx.constraintlayout:constraintlayout`, `com.google.android.material:material`, `com.elvishew:xlog`, and `androidx.core:core-ktx`. The root build also declares build-only `com.android.tools.build:gradle:4.2.2`, `com.github.dcendents:android-maven-gradle-plugin:2.1`, and `org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.20`; these are not silently included as application runtime components. Test declarations and generated/local JARs require their own scope review.

This wider graph was not traversed or license-cleared. In addition to those seeds, generated artifacts, Kotlin plugin-added dependencies, native source/prebuilts, test/build dependencies and publication mapping remain unresolved. UAC binary provenance and LAME/native terms are addressed separately in the companion reviews. This comparison does not delay completion of the primary publication's static POM inventory.
