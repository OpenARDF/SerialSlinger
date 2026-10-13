export function sectionBody(lines, heading, failures = []) {
  const matches = lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line === `## ${heading}`);

  if (matches.length !== 1) {
    failures.push(`${heading}: expected exactly one '## ${heading}' heading, found ${matches.length}.`);
    return "";
  }

  const start = matches[0].index + 1;
  const nextHeadingOffset = lines.slice(start).findIndex((line) => line.startsWith("## "));
  const end = nextHeadingOffset === -1 ? lines.length : start + nextHeadingOffset;
  return lines.slice(start, end).join("\n").trim();
}

export function meaningfulBullets(body) {
  return body
    .split("\n")
    .filter((line) => /^-\s+\S/u.test(line))
    .map((line) => line.replace(/^-\s+/u, "").replace(/[`*_]/gu, "").trim())
    .filter((line) => line.length >= 20);
}
