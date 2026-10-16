# Security Policy

English · [한국어](SECURITY.ko.md)

## Supported versions

Security fixes go into the latest release. Please update before reporting.

| Version | Supported |
|---|:---:|
| 2026.2.x (latest) | ✅ |
| older | ❌ |

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Report privately through [GitHub Security Advisories](https://github.com/taeyun1215/mydb-ide/security/advisories/new). Include:

- the affected version and operating system
- what an attacker can do and what they need (for example a malicious project file, a compromised database server, a crafted CSV file)
- steps or a proof of concept
- any suggested fix

You can expect an acknowledgement within 7 days and a status update within 30 days. When a fix is released, the advisory is published with credit to the reporter unless you prefer to stay anonymous.

## Scope

In scope:

- leaking passwords or key passphrases (for example into project files, logs or exports)
- code execution from opening a project, a SQL file, a CSV file or a DDL data source
- SQL sent to a server other than the one the user chose, or statements run without the user's action
- weaknesses in SSH tunnel and SSL handling (host key and certificate verification)

Out of scope:

- issues that require an attacker who already controls the user's machine or account
- vulnerabilities in a database server itself
- the unsigned or unnotarized state of the builds (known, see the README)

## How MyDB IDE handles secrets

- Passwords and passphrases are stored in the operating system's credential store (Keychain on macOS) and never in `.idea/` project files.
- SSH host keys are verified on every connection after the first one (trust on first use), and a changed key is refused.
- The app sends no telemetry, error reports or update requests.
