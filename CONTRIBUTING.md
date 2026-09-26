# Contributing to Tether

Thanks for helping. Bug reports, fixes and focused improvements are all welcome.

## Before you start

- **Bugs:** open an issue with your Android version, Tether version (Settings → About), the host OS,
  and `claude --version`. Say what you expected and what happened instead. Leave out hostnames,
  usernames, keys and session content.
- **Features:** open an issue to discuss the idea first, so we can agree on the approach before you
  write the code.
- **Security issues:** report them privately via
  [GitHub Security Advisories](https://github.com/iwm911/Tether/security/advisories/new), not in a
  public issue.

## Development

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

- Read [`docs/SPEC.md`](docs/SPEC.md) first. It covers the architecture, the verified Claude Code
  protocol notes and the design language.
- Kotlin + Jetpack Compose + Material 3. Dependency injection is done by hand (`AppContainer`), with
  no Hilt, Room or kapt/ksp. JSON uses kotlinx.serialization.
- Use the shared design system (`ui/theme`, `ui/components/Core.kt`) instead of one-off styles.
- The remote helper (`app/src/main/assets/tether_helper.py`) must stay a single file that runs on
  Python 3.6+ with the standard library only. Bump `HELPER_VERSION` when you change it.
- Parser and reducer changes should come with a test against the real captures in
  `app/src/test/resources/fixtures/`.

## Pull requests

- Keep each PR to one change, and explain what it does and why. Add screenshots for UI changes.
- Make sure `./gradlew :app:testDebugUnitTest` passes.
- Don't commit keystores, `keystore.properties`, `local.properties` or real host details.

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
