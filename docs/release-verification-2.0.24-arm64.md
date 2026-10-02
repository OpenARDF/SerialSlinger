# SerialSlinger 2.0.24 ARM64 Retrospective Verification

- Initial verification: 2026-10-01
- Cross-project corroboration: 2026-10-02

This record supplements, but does not rewrite, the completed v2.0.24 release checklist. Windows ARM64 and Linux ARM64 were correctly reported as skipped during the formal release because native hosts were not then part of the release session. The public tag and assets were not moved, replaced, or modified for these retrospective checks.

## Identity

- Public release: [v2.0.24](https://github.com/OpenARDF/SerialSlinger/releases/tag/v2.0.24)
- Immutable release tag commit: `f4f9a2ae4222bf7545c76b9362bc8902ad0f2106`
- Hosted ARM64 smoke implementation tested at: `ba2fe51b113f06dd0ddb021909078699c36ba3cd`
- Runners: native GitHub-hosted `ubuntu-24.04-arm` and `windows-11-arm`
- Windows ARM64 asset: `SerialSlinger.Installer-win-arm64-2.0.24_26BD.exe`, 9,778,632 bytes, SHA-256 `7cb24ab47638d2e82f5e66d012623dcb3f60eb8098766e4795b4553aef5f4fbd`

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Linux ARM64 candidate package install and version probe | Passed | [Run 36952152109](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152109) |
| Windows ARM64 candidate package install and version probe | Passed | [Run 36952152103](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152103) |
| Published v2.0.24 Linux ARM64 installer install and version probe | Passed | [Run 36952163814](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952163814) |
| Published v2.0.24 Windows ARM64 installer install and version probe | Failed before application installation | [Run 36952166643](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952166643) |

The Windows candidate pass proves that SerialSlinger, its local jDeploy package, Java 17, and the native Windows ARM64 hosted runner work together. The exact published `SerialSlinger.Installer-win-arm64-*.exe` instead exits with status 1 during the required `--jdeploy:update` bootstrap, before SerialSlinger is installed or launched. No updater diagnostic log was produced. Directly invoking a published installer without the update step is not a valid workaround: jDeploy installers require that bootstrap to obtain their package metadata before `--jdeploy:command=install` can run.

## Radio-Oracle Corroboration

Radio-Oracle subsequently ran the equivalent candidate and exact-published-installer checks on the same native GitHub-hosted ARM64 runner classes:

| Check | Result | Evidence |
| --- | --- | --- |
| Radio-Oracle Windows ARM64 candidate package install, exports, version, and launch | Passed | [Run 36958667821](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36958667821) |
| Radio-Oracle Linux ARM64 candidate package install, exports, version, and launch | Passed | [Run 36958667822](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36958667822) |
| Published Radio-Oracle v1.0.52 Linux ARM64 bootstrap, install, exports, version, and launch | Passed | [Run 36959015205](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36959015205) |
| Published Radio-Oracle v1.0.52 Windows ARM64 bootstrap | Failed during `--jdeploy:update`, before installation | [Run 36959013821](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36959013821) |

The [Radio-Oracle verification record](https://github.com/OpenARDF/Radio-Oracle/blob/Development1/docs/release-verification-1.0.52-arm64.md) confirms that the release workflows selected the exact published artifacts and did not fall back to candidate evidence. Radio-Oracle's published Windows ARM64 executable is also 9,778,632 bytes with the same SHA-256, `7cb24ab47638d2e82f5e66d012623dcb3f60eb8098766e4795b4553aef5f4fbd`, as SerialSlinger's published Windows ARM64 executable.

The two independent application results are strong evidence of a shared jDeploy 6.1.7 Windows ARM64 published-installer bootstrap defect rather than an application-specific runtime or release-metadata problem. This remains an evidence-based inference: the failing bootstrap does not emit diagnostics that establish its internal root cause.

## Conclusion

Native ARM64 candidate coverage is established for both Linux and Windows, and the published v2.0.24 Linux ARM64 artifact is verified. Radio-Oracle independently reproduces the same published-Windows-only bootstrap failure. The published v2.0.24 Windows ARM64 bootstrap remains an open packaging defect. It must not be represented as a passed release gate, and the original release-time skip remains unchanged.

The defect was reported upstream as [shannah/jdeploy#486](https://github.com/shannah/jdeploy/issues/486); the [repository issue record](upstream-jdeploy-windows-arm64-bootstrap-issue.md) preserves the submitted evidence. Track the maintainer's diagnosis and verify any correction with newly generated installers in later releases rather than by replacing either immutable published artifact.
