# Contributing

Thank you for your interest in Jotter for Android. Bug reports, ideas and pull requests are welcome.

## Reporting problems and ideas

Open an [issue](https://github.com/nir0k/jotter-android/issues/new/choose) with the bug report or feature request form. For a bug, include the application version, the device and Android version, whether the library is synchronised, the steps that reproduce it and what you expected. Vulnerabilities are reported privately as described in [SECURITY.md](SECURITY.md).

## Pull requests

For anything larger than a small fix, open an issue first so that the change can be discussed before you spend time on it. The library format and synchronisation are shared with [Jotter](https://github.com/nir0k/Jotter) for the desktop and are specified there; a change to either is proposed in that project first.

Before opening a pull request:

- Keep it to one topic; unrelated refactoring, renaming or formatting goes in a pull request of its own.
- Build and check as described in [docs/development.md](docs/development.md): the full set of checks passes, and changed screens are recorded with Roborazzi and reviewed.
- Add or update tests for changed behaviour and a regression test for a fixed bug where possible. Changes to synchronisation are also checked against an SFTP test stand.
- Write code, comments, documentation and interface text in English, and give every function a KDoc comment. New or changed interface strings go to the English and the Russian string resources.
- Do not add a dependency without a clear need; every dependency is open source and published outside Google's proprietary SDKs.
- Update the documentation that the change affects, [docs/spec.md](docs/spec.md) included.

By submitting a pull request you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
