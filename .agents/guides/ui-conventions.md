# UI conventions

Read when: adding or changing a screen, dialog, list or wording in the parent app.
Governs: `android/companion/src/main/java/home/phonelock/parent/**`, `android/companion/src/main/res/values*/strings*.xml`

The shared Android rules (Material 3 with dynamic colour, the About block, one dialog and one row shape) are UI-11 and BRAND-8 in the [app standards](https://github.com/Dynamic-Solutions-Canada/app-standards/blob/main/standards/ui.md). This guide records how the parent app meets them, and its own conventions on top. Match the sibling screen that already does the same thing before writing anything new.

## Building blocks

- **Theme:** every activity's `setContent` starts with `AppTheme { }` (`AppTheme.kt`). Nothing else sets a colour scheme or wraps a `Surface` around a whole screen.
- **Pages:** a sub-page is a `Page(title, onBack)` (`DeviceScreens.kt`): fixed top bar with a back arrow, a scrolling body, and a progress line under the title whose space is always kept, so waiting never moves the content. Pages are routed by the `page` string in `MainScreen` (`MainActivity.kt`); a page opened from another page goes back to that page, not to the main screen (Settings to Diagnostics, Fix setup and About; About to Third-party notices).
- **Sections:** a page's groups are `Section(title)` cards (`MainActivity.kt`). Don't wrap a card around a section or put a section inside another.
- **Rows:** every list row is `ListRow` (`ListRow.kt`), with a 40 dp `SymbolBadge` (an emoji) or a letter badge on the left, a title, one detail line, and a ⋮ menu for row actions. Settings rows are `ListRow`s with a symbol, a title and a line saying what they do. Raise `titleLines` for labels that must be read whole in long languages.
- **Dialogs:** every dialog is `AppDialog` (`AppDialog.kt`), with confirm on the right and Cancel to its left. A destructive confirm is `DangerButton`. In Settings, the one destructive setting (Remove this device) is a red text button, not a row.
- **Step-by-step flows:** adding a device and setup use `WizardStep` (`Wizard.kt`): one thing per step, "Step N of M", the primary button, then Back.
- **Empty and waiting states:** an empty list shows `EmptyNote`; waiting shows `ProgressTrack`, never the word "Loading".
- **Setup prompts and warnings** on the main screen share `HintCard` (`SetupHints.kt`), as does the note that an emergency alarm is paused (`EmergencyUi.kt`).
- **Lengths of time:** every "for how long" question uses `LengthDialog` (`LengthDialog.kt`): the usual lengths, "Until a time…" and "Custom length…", up to 24 hours. A notification offers one quick length plus "Choose time…", which opens the app at the same picker.
- **Confirmations:** a change that can be undone shows `Undo.offer` with an Undo button; a request whose result comes later shows `Undo.notify`, a plain message saying it was sent.

## Wording

- Screens read wording with `stringResource()`, so they redraw when the language changes; code without a Compose context (notifications, services, snackbars) uses `Strings`.
- Each area keeps its wording in its own `strings_<area>.xml`, and every one of them exists in all seven catalogs with the same keys and placeholders (`npm run check:l10n`). Reuse the common buttons in `strings.xml` (Cancel, Save, Remove) rather than adding another copy.
- The About screen's labels are winui-app-kit's `kit.about.*` wording and translations (BRAND-8); copy new About labels from there, not freshly translated.
- Names, app labels and anything a person typed are never translated. Wording the child reads uses the informal "you".
- Parent-facing text says what a setting does, not how it works. No em dashes in any language.

## Placement

- A device's Settings page, from the ⋮ on its header card, is the only settings surface. About is its last section, in every flavour.
- Flavour differences are decided by `AppUpdate.SUPPORTED` and `BackgroundConnection.SUPPORTED`; a section that a flavour doesn't support is left out, not shown disabled.
