# Collaboration

Document status: complete for the current single-maintainer, open-source model
Last reviewed: 2026-09-17

ItemGraph is currently maintained by Vlad Durdeu and accepts contributions from
external developers through GitHub pull requests. This is an open contribution
model, not a promise that every proposal will be accepted or that every
contributor has release authority.

## Roles

- Maintainer: owns project direction, merge decisions, release versions,
  publishing credentials, security response, and final conflict resolution.
- Contributor: proposes code, tests, documentation, issue reports, or
  integrations and responds to review.
- Operator: runs the mod on a server and controls its sensitive data,
  permissions, and retention.
- Moderator: uses privileged ItemGraph queries according to the operator's
  rules.

Roles may expand as the project grows, but authority should be explicit rather
than inferred from commit volume.

## Communication

- Use GitHub Issues for reproducible bugs and scoped feature proposals.
- Use pull requests for reviewable changes.
- Use the private security route in [SECURITY.md](SECURITY.md) for
  vulnerabilities.
- Do not post real moderation evidence, player identities, private coordinates,
  databases, credentials, or tokens.

## Review and ownership

Every pull request should have a clear scope, tests, documentation impact, and
known limitations. Maintainers may request design changes when a proposal
would weaken evidence provenance, quantity conservation, privacy, server-thread
safety, or read-only source boundaries.

The maintainer decides whether a change is ready to merge. A rejected proposal
should receive a technical reason when practical. Disagreements are resolved
by returning to the documented problem, evidence, invariants, and decisions in
Business.md and Decision.md.

## Contribution boundaries

External contributors may:

- fork the repository;
- create branches in their fork;
- open issues and pull requests;
- improve code, tests, documentation, and supported integrations.

External contributors may not:

- push directly to the official main branch;
- access release tokens or repository secrets;
- publish to the official Modrinth, CurseForge, or GitHub release channels;
- access production or private staging data;
- modify the GriefLogger database.

## Releases

Maintainers update the version, changelog, and release metadata, then push a
matching vX.Y.Z tag. The release workflow builds and publishes only from
that tag, extracts the matching `## [X.Y.Z]` section from `CHANGELOG.md` into
`release-notes.md`, and sends those same notes to GitHub Releases, CurseForge,
and Modrinth. Pull requests never receive release credentials.
