# SerialSlinger 2.0.24 ARM64 Retrospective Verification

Date: 2026-10-01

This record supplements, but does not rewrite, the completed v2.0.24 release checklist. Windows ARM64 and Linux ARM64 were correctly reported as skipped during the formal release because native hosts were not then part of the release session. The public tag and assets were not moved, replaced, or modified for these retrospective checks.

## Identity

- Public release: [v2.0.24](https://github.com/OpenARDF/SerialSlinger/releases/tag/v2.0.24)
- Immutable release tag commit: `f4f9a2ae4222bf7545c76b9362bc8902ad0f2106`
- Hosted ARM64 smoke implementation tested at: `ba2fe51b113f06dd0ddb021909078699c36ba3cd`
- Runners: native GitHub-hosted `ubuntu-24.04-arm` and `windows-11-arm`

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Linux ARM64 candidate package install and version probe | Passed | [Run 36952152109](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152109) |
| Windows ARM64 candidate package install and version probe | Passed | [Run 36952152103](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152103) |
| Published v2.0.24 Linux ARM64 installer install and version probe | Passed | [Run 36952163814](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952163814) |
| Published v2.0.24 Windows ARM64 installer install and version probe | Failed before application installation | [Run 36952166643](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952166643) |

The Windows candidate pass proves that SerialSlinger, its local jDeploy package, Java 17, and the native Windows ARM64 hosted runner work together. The exact published `SerialSlinger.Installer-win-arm64-*.exe` instead exits with status 1 during the required `--jdeploy:update` bootstrap, before SerialSlinger is installed or launched. No updater diagnostic log was produced. Directly invoking a published installer without the update step is not a valid workaround: jDeploy installers require that bootstrap to obtain their package metadata before `--jdeploy:command=install` can run.

## Conclusion

Native ARM64 candidate coverage is now established for both Linux and Windows, and the published v2.0.24 Linux ARM64 artifact is verified. The published v2.0.24 Windows ARM64 bootstrap remains an open packaging defect. It must not be represented as a passed release gate, and the original release-time skip remains unchanged.

The next practical action is to reproduce or report the Windows ARM64 bootstrap failure against jDeploy with run 36952166643 as evidence, then verify a newly generated installer in a later release rather than replacing the immutable v2.0.24 artifact.
