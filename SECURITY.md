# Security policy

## Supported versions

Only the latest release receives security fixes. Please check that a problem is still present in it before reporting.

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. Please do not open a public issue, discussion or pull request for a vulnerability.

A useful report includes the application version, the device and Android version, the steps or files that reproduce the problem and what an attacker gains. A reproducing library, note file or SFTP server setup helps most; remove any personal notes and credentials from it first.

Reports are answered as soon as possible. Once a fix is released, the advisory is published with credit to the reporter unless they prefer otherwise.

## Scope

Jotter for Android handles untrusted input in several places, and problems there are in scope:

- Library files: note HTML, `library.json`, `collections.json`, `rules.json` and attachments, which may come from another device or another person through synchronisation.
- Synchronisation: data sent by an SFTP server, including file names, listings and file contents, and host key checks.
- Credentials: passwords, private keys and passphrases, which are encrypted with a key of the Android Keystore and never leave the device except to sign in.
- Content shared into Jotter by other applications, pasted content, and the images and files inserted into notes.
- Links and attachments opened from notes, and exported HTML, PDF and JPG files.

Out of scope are problems that need an attacker who already controls the unlocked device or has root access, and vulnerabilities in Android itself or in other applications, which go to their own projects.
