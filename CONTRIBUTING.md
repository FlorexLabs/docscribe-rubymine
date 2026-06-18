# Contributing to DocScribe for RubyMine

We welcome contributions! Here's how to get started.

## Branching

This project follows [FlorexLabs shared workflows](https://github.com/FlorexLabs/shared-workflows):

```
feature/*  -- squash & merge -->  v*.*.*  -- merge commit -->  master
system/*   ------------------------ merge commit -->  master
```

- **feature/*** — new features. Squash & merge into release branch.
- **v*.*** — release branches. Merge commit into `master`.
- **system/*** — infra/config. Merge commit directly into `master`.

## Development Setup

1. Fork and clone the repository.
2. Install JDK 21 (Temurin recommended).
3. Install RubyMine 2026.1+ (or IntelliJ IDEA with Ruby plugin).
4. Install the `docscribe` gem: `gem install docscribe --version '>= 1.5.0'`
5. Open the project in IntelliJ/RubyMine and let Gradle sync.
6. Run `./gradlew buildPlugin` to verify the build.
7. Run `./gradlew runIde` to launch a development IDE with the plugin.

## Code Style

- Kotlin — ktlint enforced via `./gradlew spotlessCheck`
- Static analysis — detekt: `./gradlew detekt`
- Run both before committing: `./gradlew spotlessApply detekt`

## Pull Request Process

1. Create a feature branch from the latest release branch (e.g. `v*.*.*`).
2. Implement your changes with tests.
3. Ensure all checks pass:
   - `./gradlew spotlessCheck detekt test verifyPlugin buildPlugin`
4. Open a PR targeting the release branch.
5. After review, squash & merge into the release branch.

## Reporting Issues

- **Bugs**: open a GitHub issue with steps to reproduce, environment info, and logs.
- **Feature requests**: describe the problem and proposed solution.

## Security

See [SECURITY.md](./SECURITY.md) for reporting vulnerabilities.
