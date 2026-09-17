# Contributing to ItemGraph

Thank you for helping improve ItemGraph. Contributions are welcome, but the
project handles sensitive moderation data, so correctness, privacy, and
explainability matter more than feature count.

## Before you start

- Read [Collaboration.md](Collaboration.md) and [OSS.md](OSS.md).
- For a non-trivial change, open or reference an issue first.
- Do not include real player data, private coordinates, server databases,
  logs, credentials, tokens, or production configuration in a commit or issue.
- Do not access or modify production. Reproduce changes on local development or
  explicitly identified staging environments only.

## Development environment

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.248
- Git
- The repository Gradle wrapper

The runtime dependency set includes GriefLogger, Architectury API, and
SuperMartijn642's Config Lib. Use the versions documented by the active
release metadata.

## Contribution flow

1. Fork the repository.
2. Create a focused branch in your fork, such as
   fix/quantity-conservation or docs/contribution-guide.
3. Make the smallest coherent change.
4. Add or update tests and documentation when behavior or contracts change.
5. Run the full build locally.
6. Push the branch and open a pull request against main.
7. Respond to review feedback with follow-up commits.

Do not push directly to main. Maintainers own merges, releases, and the
publishing credentials.

## Verification

From a Unix shell or Git Bash:

~~~text
./gradlew clean build
~~~

From PowerShell:

~~~text
java -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain clean build
~~~

The build must pass before a pull request is ready. A change that affects
inference, quantity flow, persistence, permissions, or privacy should include
a regression test for the failure mode it addresses.

## Design expectations

- Preserve the distinction between observed evidence, inferred movement,
  ambiguous possibilities, and unresolved events.
- Never manufacture quantity or certainty.
- Keep GriefLogger read-only.
- Keep expensive database work off the Minecraft server thread.
- Use bounded queues and bounded query results.
- Prefer documented NeoForge APIs over mixins.
- Keep changes small enough to review and revert.

## Pull requests

A pull request should explain:

- what changed and why;
- the user-visible or operational effect;
- the tests run and their results;
- any migration, compatibility, privacy, or performance impact;
- any unresolved limitation.

The pull request template is a checklist, not a substitute for the
description. Do not hide important assumptions in chat or review comments.

## Release contributions

Contributors propose release-ready changes through pull requests. Only
maintainers update gradle.properties, create matching vX.Y.Z tags, and
trigger the publishing workflow. Never request or submit Modrinth,
CurseForge, or GitHub release tokens in an issue or pull request.
