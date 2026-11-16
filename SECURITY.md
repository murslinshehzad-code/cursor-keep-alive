# Security policy

## Supported versions

Only the latest release and the `main` branch receive fixes.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Use GitHub's private reporting instead: go to the repository's **Security** tab and choose
**Report a vulnerability**. Include what you found, how to reproduce it and what the impact could be.

You can expect an acknowledgement within a few days. Please give the maintainer a reasonable time to fix the
problem before you disclose it publicly.

## What the app handles

- Google sign-in cookies, kept in the app's private web view storage on the phone.
- The saved Chrome Remote Desktop session link, kept in the app's private preferences.

The app has no server of its own and sends nothing anywhere except to Google's own pages.
