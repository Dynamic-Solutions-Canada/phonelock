<h1 align="center">PhoneLock</h1>

<p align="center">
  A locked-down phone for your child, managed from your own phone.
</p>

<p align="center">
  <a href="https://github.com/Dynamic-Solutions-Canada/phonelock/releases/latest">Download the parent app</a>
</p>

PhoneLock is for parents who want to give a child a phone that does only what they allow. The child's phone shows a simple home screen with the apps you choose and nothing else, and you manage it from the PhoneLock app on your own phone: allow an app for a while, find the phone, ring it, or see its screen.

## Features

- **Only the apps you allow:** The child's home screen shows the apps you pick. Everything else is hidden, and settings that would undo the lock (installing apps, adding accounts, factory reset, safe mode, SMS) are switched off.
- **Answer requests from your phone:** Your child can ask for an app, and you can allow it for a while or turn it off again from anywhere.
- **Daily app time limits:** Set how long an app may be used each day, or on weekdays and weekends, and see how much time it has had.
- **Find, ring and view:** See where the phone is, ring it at full volume even on silent, or view its screen. Your child gets a notice whenever a parent views the screen, and every view is recorded in the activity log.
- **Location history and battery:** The phone logs its location every 15 minutes and keeps it for 7 days, and reports its battery with every message. You choose a low-battery alert, and get "Turning off" and "Turned on" notices, so a dead battery can be told apart from lost signal. The child's home screen says that location and battery are shared.
- **An SOS button:** The child can call the local emergency number from the home screen at any time.
- **Set up from your phone:** No computer and no factory reset: your phone sets up the child's phone over Wi-Fi. A second parent can be added from the first parent's app.
- **Private by design:** Every message between the phones is end-to-end encrypted, so the server that relays them sees only ciphertext. See the [privacy policy](https://dynamic-solutions.ca/privacy/phonelock).

## Get it

Download `PhoneLock-parent-app.apk` from the [latest release](https://github.com/Dynamic-Solutions-Canada/phonelock/releases/latest) and install it on your phone. It updates itself from there once installed. You don't download anything for the child's phone: the parent app installs the lock app during setup, and the lock app keeps itself up to date.

You'll need Android 13 or newer on both phones. During setup, the child's phone must be on the same Wi-Fi as yours and have no accounts signed in; you add the accounts back afterwards.

## Set up a child's phone

In the parent app, tap **Set up a child's device** and follow the steps it shows. On the child's phone, turn on Developer options, then Wireless debugging > **Pair device with pairing code**. The parent app finds it, pairs with the code, installs the lock app from the latest release (checking that it is genuine), pairs it with your phone and sets your parent PIN, turns on the features that need a one-time grant, checks that the phone answers, then switches debugging off. Nothing is erased.

Afterwards, add the child's accounts back in parent mode: hold the clock on the home screen for 4 seconds and enter the PIN. A second parent adds the phone from the first parent's app: device settings > **Share with another parent**. **Fix setup over Wi-Fi** in the device settings re-applies the one-time grants on a phone already set up.

## Your data

PhoneLock collects no telemetry or crash reports, and there is no PhoneLock account. What the child's phone shares stays on the parents' phones: the relay server only passes encrypted messages along and deletes them within 3 days. The [privacy policy](https://dynamic-solutions.ca/privacy/phonelock) lists what is shared.

- **Backup:** neither app is included in Android's backup, so the keys that pair the phones never leave them. On a new phone of your own, add the device again with **Share with another parent** from a phone that still has it.
- **Export:** files you download from the child's phone are saved in your Downloads folder, under PhoneLock. There is nothing else to export.
- **Deletion:** removing a device in the parent app deletes everything that app kept about it, and uninstalling either app deletes that app's data. Downloaded files stay in Downloads until you delete them. Removing PhoneLock from the child's phone currently needs a factory reset from recovery mode, or a computer over USB debugging.

## Languages

PhoneLock is available in English (United States and Canada), Spanish, French (France and Canada), German, Simplified Chinese, Brazilian Portuguese and Japanese. The parent app follows your phone's language unless you pick one in a device's Settings > Language, which is the same setting as Android's per-app language. You set the child's phone's language per device in Settings > Lock app, and a phone set up from your app starts in your app's language. The translations were written with AI assistance and have not been reviewed by native speakers yet. The privacy policy is in English.

Phones paired before the lock app had languages stay in English after updating, until a parent picks one. Entries already in Recent events keep the language they were written in, and phone number layout and the first day of the week follow the phone's region, not the language picked in the app.

## Developers

- [Development guide](docs/development.md): building, the hub, languages, releases and adb control.
- [Protocol](docs/protocol.md): the encrypted messages between the parent app and the child's phone.
- [Hub server](server/README.md): the relay both apps talk through.
- [Agent instructions](AGENTS.md): behaviour rules and conventions.

## License

Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE): free for any **personal and other noncommercial use**, including modifying and redistributing it. **Commercial use is not permitted.** Copyright (c) 2026 Dynamic Solutions.
