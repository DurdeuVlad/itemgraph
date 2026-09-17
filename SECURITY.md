# Security Policy

ItemGraph can expose sensitive moderation information, including player
activity, container contents, and coordinates. Treat both the mod and its
stored data as security-sensitive.

## Supported versions

The current supported line is the latest published 0.x release. Pre-1.0
releases may change behavior, storage, and command contracts without a
backward-compatibility guarantee.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability.

Use GitHub's private security advisory form:
https://github.com/DurdeuVlad/itemgraph/security/advisories/new

If private advisories are unavailable, contact the maintainer privately
through the DurdeuVlad GitHub profile:
https://github.com/DurdeuVlad

Include:

- affected release or commit;
- Minecraft, NeoForge, Java, and dependency versions;
- a minimal reproduction;
- security impact and realistic attack conditions;
- a proposed mitigation if you have one.

Do not attach real player data, production databases, private coordinates,
credentials, or unredacted server logs. Use a synthetic reproduction.

## Scope

Reports are relevant when they affect:

- unauthorized access to ItemGraph queries or stored evidence;
- privacy leaks in commands, explanations, exports, or diagnostics;
- corruption or unsafe mutation of ItemGraph's database;
- unsafe interaction with the read-only GriefLogger source;
- credential exposure in build or release automation;
- malicious or unexpected behavior in the published mod.

Report vulnerabilities in NeoForge, Minecraft, GriefLogger, or other upstream
dependencies to their maintainers as well, while explaining the ItemGraph
impact in the private report.

## Response

The maintainer will assess the report, determine affected versions, and
coordinate a fix or mitigation. Response and release timing are best effort;
there is no guaranteed response SLA for this pre-1.0 project.
