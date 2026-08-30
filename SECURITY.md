# Security policy

## Supported versions

The project is pre-alpha and has no supported production release yet. Security fixes target `main` until the first supported release is declared.

## Report a vulnerability

Do not open a public issue for a suspected vulnerability. Email **argvid@126.com** with:

- affected component and revision;
- reproduction steps or proof of concept;
- likely impact;
- suggested mitigation, if known;
- a safe way to contact you.

Do not include real user media, production credentials, or unnecessary personal data. We will acknowledge receipt when possible and coordinate disclosure after a fix or mitigation is available.

## Scope priorities

- authentication and authorization boundaries;
- contract confusion that can cause unsafe execution;
- lost-contact, emergency-stop, and capability enforcement;
- dependency or workflow supply-chain compromise;
- credential or restricted-data exposure.

The repository does not provide a safety certification. Responsible reports about unsafe behavior are handled with the same priority as software security issues.
