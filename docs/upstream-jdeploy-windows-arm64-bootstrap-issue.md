# Upstream jDeploy Windows ARM64 Bootstrap Issue

## Submission

- Authority: [shannah/jdeploy](https://github.com/shannah/jdeploy), the repository declared by the `jdeploy` npm package and linked by the official jDeploy documentation.
- Status: Submitted by OpenARDF on 2026-10-02 as [shannah/jdeploy#486](https://github.com/shannah/jdeploy/issues/486).
- Channel: [new GitHub issue](https://github.com/shannah/jdeploy/issues/new). The repository has no issue template, and no existing issue found on 2026-10-02 matches this two-application `--jdeploy:update` failure.
- Submitted title: `Windows ARM64 published installer exits 1 during --jdeploy:update (jDeploy 6.1.7)`
- Alternative support channel: [jDeploy Discussions](https://github.com/shannah/jdeploy/discussions). Use an issue for this reproducible defect; use Discussions only if the maintainer prefers initial troubleshooting there.

The content from **Summary** through **Questions** matches the submitted issue body.

## Summary

Published Windows ARM64 installers generated with jDeploy 6.1.7 exit with status 1 during `--jdeploy:update` on a native GitHub-hosted Windows ARM64 runner. This reproduces with two independent applications, Radio-Oracle and SerialSlinger.

Both applications' locally prepared candidates install and launch successfully on the same native Windows ARM64 runner class. Their exact published Linux ARM64 installers also complete the bootstrap update, installation, version verification, and installed-launcher checks. The failure is limited to the exact published Windows ARM64 bootstrap path in the current evidence.

## Environment

- jDeploy package version used to publish both releases: 6.1.7
- Runner: GitHub-hosted `windows-11-arm`
- Native architecture assertion: Node 24 reports `process.arch === "arm64"`
- Candidate-build Java on Windows ARM64: Microsoft Java 17, reporting `aarch64`
- Publication target: public GitHub Releases
- Invocation shell: PowerShell 7

The published-installer jobs do not provision a build JDK because they exercise the standalone public installer. The equivalent Linux ARM64 published-installer job succeeds without candidate preparation.

## Reproduction

1. Download either exact published installer:
   - [Radio-Oracle 1.0.52 Windows ARM64 installer](https://github.com/OpenARDF/Radio-Oracle/releases/download/v1.0.52/Radio-Oracle.Installer-win-arm64-1.0.52_26CY.exe)
   - [SerialSlinger 2.0.24 Windows ARM64 installer](https://github.com/OpenARDF/SerialSlinger/releases/download/v2.0.24/SerialSlinger.Installer-win-arm64-2.0.24_26BD.exe)
2. On native Windows ARM64, invoke the bootstrap update and wait for its exit code:

```powershell
$process = Start-Process `
    -FilePath .\Radio-Oracle.Installer-win-arm64-1.0.52_26CY.exe `
    -ArgumentList '--jdeploy:update' `
    -Wait `
    -PassThru
$process.ExitCode
```

3. Observe exit status 1. The workflow therefore does not proceed to `--jdeploy:command=install` or application launch.

SerialSlinger produces the same result when its installer is substituted in the command.

## Expected Behavior

`--jdeploy:update` should complete successfully, allowing `--jdeploy:command=install` to install the application and the source-qualified installed launcher to run.

## Actual Behavior

The Windows ARM64 executable exits with status 1 during `--jdeploy:update`, before installation. No useful updater diagnostic log is produced. The workflow reports:

```text
Installer command --jdeploy:update exited 1.
```

The two Windows ARM64 executables are byte-identical:

- Size: 9,778,632 bytes
- SHA-256: `7cb24ab47638d2e82f5e66d012623dcb3f60eb8098766e4795b4553aef5f4fbd`

## Evidence and Controls

Radio-Oracle:

- [Windows ARM64 candidate passes](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36958667821)
- [Linux ARM64 candidate passes](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36958667822)
- [Published Linux ARM64 v1.0.52 installer passes](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36959015205)
- [Published Windows ARM64 v1.0.52 installer fails during `--jdeploy:update`](https://github.com/OpenARDF/Radio-Oracle/actions/runs/36959013821)
- [Retrospective verification record](https://github.com/OpenARDF/Radio-Oracle/blob/Development1/docs/release-verification-1.0.52-arm64.md)

SerialSlinger:

- [Windows ARM64 candidate passes](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152103)
- [Linux ARM64 candidate passes](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952152109)
- [Published Linux ARM64 v2.0.24 installer passes](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952163814)
- [Published Windows ARM64 v2.0.24 installer fails during `--jdeploy:update`](https://github.com/OpenARDF/SerialSlinger/actions/runs/36952166643)
- [Retrospective verification record](https://github.com/OpenARDF/SerialSlinger/blob/Development_Android/docs/release-verification-2.0.24-arm64.md)

The exact-release workflows select one matching published installer and do not fall back to candidate evidence.

## Impact

The published Windows ARM64 installers cannot currently pass unattended installation verification. Candidate success shows that both Java applications can run on native Windows ARM64, but it does not provide a workaround for users of the published installer.

The existing release tags and assets remain immutable. We intend to verify any correction using newly generated installers in later application releases.

## Questions

1. Is `--jdeploy:update` expected to work for a newly downloaded Windows ARM64 installer in jDeploy 6.1.7?
2. Is there a diagnostic flag or log location that can expose the internal cause of this exit status?
3. Is this a known Windows ARM64 bootstrap issue, and is there a newer installer/bootstrap build that we should test before changing the pinned jDeploy package version?
