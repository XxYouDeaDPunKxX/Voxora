# Contributing to Voxora

**Contributions are welcome.** Voxora is an experimental Wear OS project. Bug reports, clearer documentation, accessibility improvements, interface refinements, and focused code fixes can all help.

## Issues and ideas

Check the [existing issues](https://github.com/XxYouDeaDPunKxX/Voxora/issues) and the [setup guide](docs/INSTALL_AND_SETUP.md) before opening a new issue. For a bug, please include:

- The watch model, Wear OS version, and Voxora version you tested.
- What you expected, what happened instead, and steps to reproduce it.
- Relevant logs or screenshots **with all account and personal data removed**.

For a larger feature or behavior change, [open an issue](https://github.com/XxYouDeaDPunKxX/Voxora/issues/new) first so the approach can be discussed.

## Pull requests

1. Fork the repository, create a branch from `main`, and keep the change focused.
2. Describe the problem, the change, and how you verified it. Link a related issue when applicable.
3. Include documentation or updated UI examples when user-facing behavior changes.
4. For Android code changes, run the applicable checks if your environment supports them. On Windows:

   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug lintDebug
   ```

   On macOS or Linux:

   ```sh
   sh ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

   State any checks you could not run. Documentation-only changes do not need an Android build.

See [Build from source](README.md#build-from-source) for the required JDK and Android SDK versions.

## Privacy and project limits

**Never include ChatGPT session cookies, access tokens, passwords, private conversations, or other credentials** in an issue, pull request, log, screenshot, or commit. Redact sensitive content before sharing diagnostics.

Voxora has been tested primarily on Samsung Galaxy Watch7; the on-watch interface is currently in Italian. Its ChatGPT web-session integration may change, and the project is independent of OpenAI. When reporting support for another device or a new integration behavior, say exactly what you tested.

Contributions are subject to the repository's [MIT license](LICENSE) and the existing [third-party notices](THIRD_PARTY_NOTICES.md).
