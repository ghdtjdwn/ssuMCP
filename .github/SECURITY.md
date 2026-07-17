# Security Policy

## Supported versions

Only the latest commit on `main` and the currently deployed revision receive security fixes. This is
a portfolio project, not a versioned commercial service, so older commits and forks are not supported.

## Report a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private **Report a
vulnerability** form from the repository's Security tab. If that form is unavailable, email
[seongjuice999@gmail.com](mailto:seongjuice999@gmail.com).

Include the affected repository and commit, expected and actual behavior, impact, and the smallest
sanitized reproduction you can provide. Never include a real university password, API key, session
cookie, JWT, MCP session identifier, downloaded academic file, or personal student data.

No bug-bounty payment or response-time SLA is offered. Reports will be triaged against the current
code and deployment, and disclosure should be coordinated until a fix or mitigation is available.

## Scope

In scope:

- authentication, session ownership, authorization, and approval-gated actions;
- secret exposure, unsafe logging, injection, SSRF, and cross-user data access;
- deployment manifests and public endpoints owned by this project.

Third-party university systems, identity providers, LLM providers, and their availability are not
controlled by this project. Please still report integration behavior that creates a vulnerability in
this code rather than testing a third party directly.
