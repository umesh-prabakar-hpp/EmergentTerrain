# Security policy

## Supported versions

This is an experimental project. Security fixes are made against the current `main` branch only;
there are no supported release branches yet.

## Reporting a vulnerability

Do **not** open a public issue for a suspected vulnerability.

1. Use GitHub's **Private vulnerability reporting** form in this repository's **Security** tab.
   Maintainers must enable that GitHub setting before making the repository public.
2. If the form is temporarily unavailable, email `umesh.prabakar@gmail.com` with the subject
   `EmergentTerrain security report`. Do not include credentials, tokens, or personally identifying
   data beyond what is necessary to reproduce the problem.

Include the affected component and version/commit, a clear reproduction path or proof of concept,
impact, and any suggested mitigation. Please allow five business days for acknowledgement and
fourteen days for an initial assessment. We will coordinate a fix and disclosure timeline with the
reporter and credit them only with their consent.

## Scope

Examples of in-scope issues include authentication and signed-QR flaws, exposed API keys or service
credentials, game/research API authorization bypasses, unsafe database or network exposure,
dependency vulnerabilities with a practical impact, and flaws in the Codex/legacy AI gateway boundary.

Out of scope are requests for product features, disagreements with research conclusions that do not
create a security impact, attacks requiring credentials already under the attacker's control, and
vulnerabilities in third-party services not operated by this project. Do not report an unverified
model output or ordinary failed computational result as a security issue.

## Maintainer checklist before public launch

- Enable GitHub private vulnerability reporting and ensure at least two maintainers receive security
  notifications.
- Enable secret scanning and push protection where available.
- Review dependency alerts and protect the default branch with pull-request review and required
  checks.
- Keep this policy's contact route current.
