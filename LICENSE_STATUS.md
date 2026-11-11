# License status and third-party scope

The project owner has requested public source publication and described the project as open source. The local project has not yet selected a project-wide license. This publication does not choose MIT, Apache-2.0, or another license on the author's behalf. A project-wide license remains pending.

The JNI experiment includes a small libuvc-derived header fixture with four endian macros and selector constants from pinned libuvc commit 448d78b9. Its original attribution and BSD 3-Clause license are retained in `experiments/uvc-worktree/android/uvc-sim/native-experiment/jni-boundary-experiment/source-evidence/licenses/libuvc-LICENSE.txt`. The accompanying `libusb-COPYING.txt` is retained with the source research. These terms apply to their respective third-party material and do not automatically license the rest of this repository.

No downloaded UVCAndroid AAR, native .so, SDK/JDK, upstream source cache, Python installed package directory, or signing private key is redistributed. Python dependencies are declared in `pyproject.toml` and obtained separately under their own licenses. Source research reports retain upstream links and notices.

The five CED8 APKs were inspected: their DEX-defined classes are under local/sidecar/copilot, with no bundled native library or nested JAR/AAR. This is a packaging observation, not a legal guarantee of every source fragment's provenance. All APKs are development/test artifacts, not a production release.
