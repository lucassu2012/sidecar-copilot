# Synthetic validation — 2026-11-10

Command: `./android/uvc-sim/run-checks.ps1` from the isolated worktree. Toolchain: local JDK 17.0.19+10, Java `--release 8`, Android API 35 `android.jar` for descriptor-adapter compilation only.

Observed successful output after the final source changes:

```text
PASS CoordinatorChecks: 204 assertions; controlled synthetic lifecycle and race scenarios
PASS FramePipelineChecks: 10 scenarios, 77 assertions
Owned pixel peak bytes=3686400; blocked-worker stop admission observation ns=39900 (JVM fixture only)
PASS IntegrationChecks: 3 end-to-end synthetic scenarios, 43 assertions
PASS Android discovery API 35 compilation only; no device used.
```

324 assertions total. CoordinatorChecks groups 13 scenario methods; FramePipelineChecks groups 10; IntegrationChecks exercises 3 flows. These counts include setup/cleanup assertions, not independent PRD acceptance cases. Existing PRD identifiers are unchanged.

The 39,900 ns observation is the largest of two in-process `FramePipeline.cancel` observations while its worker is held at a test latch. It measures neither native driver dispatch nor physical camera stop; the sample size is two and has no P95/SLA interpretation. It must not be used to claim production stopping performance. The 3,686,400-byte peak counts pipeline-reserved pixel arrays, not process RSS or the entire application heap.

All inputs were generated arrays. No Android device, emulator, APK, microphone, speaker, screen, USB endpoint or external model was opened. No real content was uploaded. The Android discovery adapter was compiled but not executed.

The initial combined runner incorrectly used a package-qualified name for the default-package CoordinatorChecks and failed to launch it. The runner was corrected to invoke each actual class name and now compiles into a fresh directory; the successful output above is from the corrected final run. There was no failing behavioral assertion in that launcher failure.

The primary application suite was not rerun: the slice is outside its source/build paths, and no primary application file was changed. There is no claim of native hardware compatibility, encrypted image persistence, integration acceptance or G1/G2/G3 completion.
