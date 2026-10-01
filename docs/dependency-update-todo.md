# Dependency update follow-up

## jDeploy installer-tool warnings

jDeploy 6.1.7 still installs its own `shelljs` 0.8 dependency, which brings the deprecated
`glob` 7 and `inflight` packages into the development-only installer toolchain. SerialSlinger uses
the maintained direct `shelljs` 0.10 launcher dependency, overrides nested `brace-expansion` with
patched 1.1.21, bundles the runtime launcher dependencies, and requires `npm audit` to report zero
known vulnerabilities.

This is a narrow waiver for npm's `glob` and `inflight` deprecation notices while they remain
transitive dependencies of the pinned jDeploy CLI. Do not force an incompatible dependency
override that makes the npm tree invalid. Recheck this waiver whenever jDeploy changes or during
each release dependency review, and remove it when upstream no longer requires those packages.
