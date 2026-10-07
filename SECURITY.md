# Security Policy

## Reporting Security Issues

Do not publish sensitive vulnerabilities, credentials, or private keys in public issues. Report security-sensitive issues privately to the project maintainer.

## Never Commit Secrets

Never commit `.env`, `.env.*`, Supabase service-role keys, API keys, passwords, private keys, keystores, signing credentials, tokens, or private certificates.

## Android Signing

Release signing credentials must remain outside the repository.

## Backend Credentials

Production credentials belong in the deployment environment. They should never be hard-coded into Android source, WebView JavaScript, server source, or documentation.

## If a Secret Is Accidentally Committed

Treat it as compromised. Revoke or rotate it, remove it from the working tree, investigate repository history, replace the credential, and document the incident privately.
