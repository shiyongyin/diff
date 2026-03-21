# Changelog

All notable changes to this project will be documented in this file.

The format is inspired by Keep a Changelog, and this project follows semantic intent for versioning during its open-source hardening process.

## [Unreleased]

### Breaking Changes

_None in this release._

### Added

- Added root-level open-source governance files: `README.md`, `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `CHANGELOG.md`
- Added GitHub CI workflow and Issue / PR templates
- Added repository-wide `.editorconfig`
- Added `docs/README.md` as the documentation index
- Added integration tests for session and apply/rollback flows
- Added versioning and releasing governance docs
- Added GitHub release note categorization config

### Changed

- Replaced the default `HELP.md` content with a repository entrypoint note
- Clarified current documentation status and reduced the most visible historical drift in docs
- Split health-check and apply documentation away from outdated assumptions about `session not found: 0` and `DRY_RUN` endpoint behavior
- Extended CI to validate the demo packaging path
