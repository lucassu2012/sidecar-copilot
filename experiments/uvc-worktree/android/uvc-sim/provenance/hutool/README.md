# UVCAndroid publication and Hutool 5.8.35 provenance

Reviewed 2026-11-10 in `sidecar-uvc-licenses`, baseline `fd9825ee57c1eec9e773ac8853195664adbb5038`. Only this provenance directory and its ignored cache were written. No downloaded code, Gradle, native library or device was executed; no dependency was integrated and no commit was created.

The earlier UVCAndroid Hutool mismatch now has a concrete history explanation and a matching published source archive. This closes the unexplained Java-source difference at the inspected-file level. It does **not** establish reproducible AAR/native output or distribution clearance. Hutool's actual source archive also contains file-level notices beyond its parent POM's Mulan declaration.

## Evidence and replay

- `trace.py`: stdlib-only ZIP, class constant-pool, XML and Git-blob comparison.
- `inputs.lock.json`: reviewed URL, size and SHA-256 for all 25 inputs. The script never rewrites this lock. Both online/cache and offline paths require it.
- `trace.json`: generated result, per-input and per-source hashes, source match inventory, license/source-origin markers, exact class references and commit history.
- `check_integrity.py` / `integrity-checks.json`: negative checks for missing inputs, changed payloads, changed payload plus cache metadata, and preservation of the previous report on failure.
- Downloaded bytes and HTTP metadata: `android/uvc-sim/build/provenance/hutool/`, covered by the existing `/build/` ignore rule. Original UVC AAR/POM/module remain at `task-4/uvc-slice/audit`.

```powershell
python android\uvc-sim\provenance\hutool\trace.py --existing-artifacts C:\Users\USER\Documents\Codex\2026-11-10\task-4\uvc-slice\audit
python -O android\uvc-sim\provenance\hutool\trace.py --offline --existing-artifacts C:\Users\USER\Documents\Codex\2026-11-10\task-4\uvc-slice\audit
python -O android\uvc-sim\provenance\hutool\check_integrity.py --existing-artifacts C:\Users\USER\Documents\Codex\2026-11-10\task-4\uvc-slice\audit
```

Positive online/cached and offline checks passed. Optimized Python (`-O`) also passed, with integrity gates implemented using explicit exceptions rather than assertions. Four negative integrity checks passed without changing the existing evidence report. Offline mode rejects a missing or changed input; it does not contact the network, regenerate a lock, or accept newly computed hashes as reviewed identities. A fresh online replay can fail if a GitHub API representation changes; that requires review rather than automatic lock refresh.

## Closed observations

**UVCAndroid 1.0.13 Java publication lineage.** The inspected GitHub `1.0.13` tag points to `6018c7f20238832929cf61427427bb7828056260`. The release source archive contains 74 ordinary Java source files that match this commit's `libuvccamera/src/main/java/` blobs byte for byte; no mapped file differs. Its remaining Java entry is generated `com/serenegiant/uvccamera/BuildConfig.java`, explicitly marked generated, with release/non-debug values. See the [tag ref](https://api.github.com/repos/shiyinghan/UVCAndroid/git/ref/tags/1.0.13) and [release commit](https://github.com/shiyinghan/UVCAndroid/commit/6018c7f20238832929cf61427427bb7828056260).

The source archive's `FileUtils.java` is exactly the tag's file and includes `cn.hutool.core.io.FileUtil.getMimeType`; the tag build declares `hutool-core:5.8.35`. The existing AAR's `FileUtils.class` has the corresponding symbolic `Methodref` with descriptor `(Ljava/lang/String;)Ljava/lang/String;`. Thus the published source, published dependency declaration and observed binary reference are consistent for this use. This is a static reference match, not proof that this path is reached in Sidecar. See the [release FileUtils](https://github.com/shiyinghan/UVCAndroid/blob/6018c7f20238832929cf61427427bb7828056260/libuvccamera/src/main/java/com/serenegiant/utils/FileUtils.java) and [release build](https://github.com/shiyinghan/UVCAndroid/blob/6018c7f20238832929cf61427427bb7828056260/libuvccamera/build.gradle).

Commit `61e9f8fde9ee0071b661d230a33b58542ddf84bc`, dated 2026-06-24, directly follows that release commit and removes both the Hutool Gradle dependency and the call, replacing it with Android `MimeTypeMap`. The previously reviewed `fcdae5f9a194e2111d57019176b53470a120415b` is the merge of that change, two commits ahead of the release tag. The mismatch should therefore be described as **release versus later source**, rather than an unexplained publication alteration. See the [removal diff](https://github.com/shiyinghan/UVCAndroid/commit/61e9f8fde9ee0071b661d230a33b58542ddf84bc) and [comparison](https://github.com/shiyinghan/UVCAndroid/compare/6018c7f20238832929cf61427427bb7828056260...fcdae5f9a194e2111d57019176b53470a120415b).

**Hutool source/POM correspondence.** The upstream repository redirects from `dromara/hutool` to `chinabugotech/hutool`. The observed `5.8.35` tag points to `ced8bf817ab4448e9b1162af6ce697cef8640827`. All 693 Java files in the Maven source archive exactly match that commit's `hutool-core/src/main/java/` blobs, with zero differences or unmapped source entries. The tag's core and parent POMs are byte-identical to the corresponding Maven POMs. The binary JAR's embedded core POM is also byte-identical. The tag root LICENSE is Mulan PSL 2, SHA-256 `d7136cebc1c0c2a50e02013928fc283b8d69cb805a994e21e6a8cc3983f45131`. See the [tag](https://api.github.com/repos/chinabugotech/hutool/git/ref/tags/5.8.35), [fixed root license](https://github.com/chinabugotech/hutool/blob/ced8bf817ab4448e9b1162af6ce697cef8640827/LICENSE), and [fixed core source tree](https://github.com/chinabugotech/hutool/tree/ced8bf817ab4448e9b1162af6ce697cef8640827/hutool-core/src/main/java).

**Actual Hutool archive contents.** The binary has 906 class entries under `cn/hutool/core/`; the only other files are the manifest, embedded POM, POM properties and JAR index. Neither binary nor source archive has an independent LICENSE/NOTICE/COPYING-named entry, a nested archive, or an identified native binary. A source-text search found no direct `System.load`/`System.loadLibrary` marker; that search is not a general proof about all reflective loading. The manifest states version 5.8.35 and a JDK 8/Maven build, but those declarations are not a build attestation.

The core POM has no direct dependency declarations. Parent JUnit/Lombok dependencies are test-scoped. Class references outside the scanner's core/java/javax/sun namespace allowlist are ten standard XML DOM/SAX types (`org.w3c.dom` / `org.xml.sax`), not ten third-party dependencies. The JAR also references desktop Java, ImageIO/Swing, naming, JavaCompiler and JAXB APIs. Their presence requires target/runtime and reachability review; absence of additional Maven runtime declarations does not establish Android compatibility.

## License facts beyond the parent POM

The exact published sources contain the following notices, and each corresponding class is present in the binary JAR; source/class hashes and line evidence are in `trace.json`:

| File in `cn/hutool/core/` | Actual source notice |
| --- | --- |
| `comparator/InstanceComparator.java` | Apache-2.0 header, original-author copyright; class documentation attributes adapted Spring code. |
| `text/csv/CsvParser.java` | Apache-2.0 header; documentation references FastCSV. |
| `text/csv/CsvTokener.java` | Apache-2.0 header. |
| `img/gif/NeuQuant.java` | Anthony Dekker copyright and a permissive grant requiring retention of the copyright notice; Java-port credit also present. Do not replace this with a guessed SPDX identifier. |
| `lang/Version.java`, `util/JdkUtil.java` | Explicit Mulan PSL 2 headers. |

These are actual source-level findings, not inferred from the POM. See the pinned [InstanceComparator](https://github.com/chinabugotech/hutool/blob/ced8bf817ab4448e9b1162af6ce697cef8640827/hutool-core/src/main/java/cn/hutool/core/comparator/InstanceComparator.java), [CsvParser](https://github.com/chinabugotech/hutool/blob/ced8bf817ab4448e9b1162af6ce697cef8640827/hutool-core/src/main/java/cn/hutool/core/text/csv/CsvParser.java), [CsvTokener](https://github.com/chinabugotech/hutool/blob/ced8bf817ab4448e9b1162af6ce697cef8640827/hutool-core/src/main/java/cn/hutool/core/text/csv/CsvTokener.java), and [NeuQuant](https://github.com/chinabugotech/hutool/blob/ced8bf817ab4448e9b1162af6ce697cef8640827/hutool-core/src/main/java/cn/hutool/core/img/gif/NeuQuant.java).

Additional source comments name borrowed or referenced code from Commons, Spring, Jodd, FastCSV and other upstream projects. The JSON includes a heuristic marker inventory for follow-up; its hit count is not a count of proven third-party components or license obligations. False-positive text such as Html4Escape's copyright-symbol description is excluded from the notice classification. Exact borrowed revisions, complete applicable notices and selected final distribution material are not established by this review. Accordingly, a sole Mulan license label would be an incomplete description of the observed source notices, while a blanket Apache label would also be wrong.

## Artifact identities

| Official artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| [UVCAndroid 1.0.13 sources](https://repo.maven.apache.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13-sources.jar) | 186307 | `f2292812ab0d988a0b0a96c5c511b0702a55aa050fb9a4e5030c06b131a22ce0` |
| [Hutool core 5.8.35 JAR](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.jar) | 1466267 | `69fb13e89d17fd0f254a77fd5c1df4783151a8d90999d0288d0732da6f82365e` |
| [Hutool core 5.8.35 sources](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35-sources.jar) | 1129054 | `1bcf829a8cf3cbb52e551d7332581dc1ecb9192ac99a7039dee7b21f76b81662` |
| [Hutool core POM](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.pom) | 698 | `441fc70fab6afd1b596402b888d6e8a4d014871acea4b484cc083d4335fdbdcf` |
| [Hutool parent POM](https://repo.maven.apache.org/maven2/cn/hutool/hutool-parent/5.8.35/hutool-parent-5.8.35.pom) | 6425 | `66a9b022d6fc105760e41888ce44a9ecceab6f598fc3f5ffe21f461d4e2d8475` |

All five match their repository `.sha1` sidecars as well. That is a same-repository consistency check; signatures and independent publisher attestation were not verified.

## Remaining gates

1. Java source-archive/tag equality and matching symbolic references do not prove the 906 Hutool classes or UVC AAR were reproducibly built from those inputs. Toolchain, generated inputs, compiled-byte comparisons and attestations remain open. UVC's Java source archive does not supply the AAR's native source provenance.
2. Whole-package notices and borrowed-code origins remain incomplete, particularly the additional Hutool notices above. Native licensing/libyuv/UAC questions remain separate open work. No distribution clearance follows from this report.
3. Runtime API/linking compatibility, D8/R8 reachability, final merged permissions and the complete resolved application graph were not tested. No real capture or hardware support is claimed.
4. The post-release commit without Hutool is a different source baseline. If that source is selected for an isolated build, record its commit and local changes separately; do not present the result as the unchanged upstream 1.0.13 publication.
