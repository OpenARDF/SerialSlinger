#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

for tool in actionlint shellcheck shfmt; do
	if ! command -v "$tool" >/dev/null 2>&1; then
		echo "Missing required static-analysis tool: $tool" >&2
		exit 1
	fi
done

shell_files=()
portable_shell_files=()
while IFS= read -r file; do
	shell_files+=("$file")
	# ShellCheck does not parse zsh; shfmt still enforces the repository's shell layout.
	if ! head -n 1 "$file" | grep -q 'zsh'; then
		portable_shell_files+=("$file")
	fi
done < <(git ls-files --cached --others --exclude-standard '*.sh')

actionlint
if ((${#portable_shell_files[@]} > 0)); then
	shellcheck "${portable_shell_files[@]}"
fi
if ((${#shell_files[@]} > 0)); then
	shfmt -d "${shell_files[@]}"
fi
git diff --check

echo "Workflow, shell, formatting, and Git whitespace checks passed."
