# Open-Source Project Policy

Document status: complete for the current 0.x release model
Last reviewed: 2026-09-17

## Identity and license

ItemGraph is an open-source, server-side Minecraft moderation and forensic
analysis mod for NeoForge 1.21.1. The source is licensed under the MIT License;
see [LICENSE](LICENSE).

The project is pre-1.0. Compatibility, storage, command output, and APIs may
change between minor releases. Published metadata must not imply a stronger
stability guarantee than the repository can prove.

## Governance

The current governance model is single-maintainer with open external
contributions:

- Vlad Durdeu is the current maintainer and release authority.
- Contributions are reviewed through pull requests.
- Technical decisions are recorded in [Decision.md](Decision.md).
- Collaboration boundaries are defined in [Collaboration.md](Collaboration.md).

Maintainer authority includes protecting users, rejecting unsafe changes,
rotating compromised credentials, and delaying a release when evidence or
tests are insufficient.

## Support

- Report reproducible bugs through
  [GitHub Issues](https://github.com/DurdeuVlad/itemgraph/issues).
- Read the technical documentation before opening a broad feature request.
- Ask focused questions with the Minecraft, NeoForge, Java, dependency, and
  ItemGraph versions included.
- There is no guaranteed response SLA for this pre-1.0 project.

## Contributions

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions must preserve:

- evidence-first output;
- deterministic, explainable inference;
- quantity conservation;
- privacy and permission boundaries;
- read-only GriefLogger integration;
- off-thread heavy work and bounded resource use.

## Releases

The official release path is:

1. update gradle.properties and CHANGELOG.md;
2. run the full build and test suite;
3. commit the release change;
4. create a matching vX.Y.Z tag;
5. let the tag-only GitHub Actions workflow publish the jar to GitHub
   Releases, Modrinth, and CurseForge.

Release credentials are maintainer-only. A contributor's pull request cannot
publish an official release.

## Security and privacy

Use [SECURITY.md](SECURITY.md) for vulnerability reporting. Read the
[Code of Conduct](CODE_OF_CONDUCT.md) before participating. Do not publish
real player or server data. ItemGraph operators remain responsible for
permission configuration, database protection, retention, backups, and lawful
handling of moderation data.

## Project map

- [Business.md](Business.md): problem, beneficiaries, rules, and outcomes.
- [Decision.md](Decision.md): durable technical and governance decisions.
- [Milestones.md](Milestones.md): outcome-based project checkpoints.
- [Collaboration.md](Collaboration.md): roles, review, and authority.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): technical boundaries and data
  flow.
- [docs/SECURITY_AND_PERMISSIONS.md](docs/SECURITY_AND_PERMISSIONS.md):
  runtime threat model and permission
  expectations.
- [docs/TEST_PLAN.md](docs/TEST_PLAN.md): correctness and staging evidence.
