# Privacy policy

Effective date: 18 November 2026

This policy describes how Jotter for Android (package `io.github.nir0k.jotter`) handles your data. In short: your notes stay on your phone and on the SFTP server you choose; the developer receives nothing.

## What the developer collects

Nothing. Jotter has no account, no analytics, no advertising, no crash reporting and no Google Play services. It makes no network connections other than to the SFTP server you configure, and the developer has no access to that server or to your data.

## Data stored on your phone

- **Notes and libraries:** your notes, their attachments, collections, tags, rules and the search index are kept in the application's private storage.
- **Settings:** your preferences and the SFTP connection details are kept in the application's private storage.
- **Credentials:** passwords, private keys and passphrases for your SFTP server are encrypted with a key of the Android Keystore. They leave the phone only to sign in to that server.
- **Backups:** the application's data is excluded from Android's cloud backup and device-to-device transfer.

## Synchronisation

If you connect a library to an SFTP server, Jotter copies the library's files to a folder on that server and back. The transfer is encrypted by the SSH protocol. The server is chosen and controlled by you; whoever operates it decides how long the files are kept. Synchronisation is optional: without it, a library exists only on your phone.

## Location

Jotter reads your location only if you turn on Settings → General → Add location to new notes, which is off by default and asks for the location permission when you turn it on. While it is on, a new note asks Android for the current position once, when the note is created. The position, rounded to five decimal places (about a metre), its accuracy and the time are stored in the note's file, and travel with the note like the rest of its content, including to your SFTP server if the library is synchronised. You can remove the location from any note. Jotter never reads your location in the background.

## Camera, files and shared content

Photos are taken with the system camera and files are chosen with the system's pickers, so Jotter has no camera or storage permission. Photos, files and text that you add or share into Jotter become part of your notes.

## Notifications

Notifications report synchronisation problems. They are created on the phone and sent nowhere.

## Exports

Notes exported to PDF, HTML or JPG are created on the phone and go only where you send or save them.

## Deleting your data

Deleting a library in Jotter, clearing the application's storage or uninstalling the application removes its data from your phone. Copies on your SFTP server are removed on that server by you or its operator.

## Children

Jotter is not directed at children and collects no data from anyone.

## Changes to this policy

Changes are published in this file with a new effective date. The history of the file is available in the repository.

## Contact

Questions about this policy can be sent to <jotter@mutted.net> or asked through the issues of the repository: <https://github.com/nir0k/jotter-android/issues>.
