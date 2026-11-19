# Brand masters

The PhoneLock mark's hand-authored masters live here (ASSET-1, ASSET-2): a phone with a padlock on its screen, in the family palette (navy `#0B0F19`, blues `#6EA8FE` and `#416397`). Both apps use the same mark. `npm run icons` (devkit-icons) writes every launcher, notification and Play icon from them, and `npm run check:icons`, part of `validate`, fails when a committed icon no longer matches its master. `icons.json` says where the outputs go: one Android entry per app, and only the parent app's writes the Play icon. Nothing else draws icons, and generated files are never edited by hand.

- `icon.svg`: the full-colour mark.
- `icon-mono.svg`: the single-colour silhouette, used for Android's themed launcher icon and the notification icon. Holes are drawn with reversed winding, so the screen, keyhole and home bar stay open.
- `icon-maskable.svg`: the same art on the full 108 dp canvas, used for the Play icon (`playMaster`), so it keeps a margin.

When changing them:

- Use flat `<path d fill>` elements only (no `<rect>`, `<circle>` or strokes) and no text; devkit rejects anything else.
- devkit maps a master's whole viewBox into the central 66 dp of the 108 dp adaptive canvas, and Android's safe zone is a circle of that size. `icon.svg` and `icon-mono.svg` therefore use `viewBox="12 12 84 84"` around art drawn on the 108 grid, which keeps every corner inside the circle so round launchers don't clip it.
- Check the result at launcher, round, themed and 24 px notification sizes before committing.
