# Brand masters

The PhoneLock mark's hand-authored masters live here (ASSET-1, ASSET-2), and `npm run icons` (devkit-icons) writes every icon both apps and the Play listing need from them; `npm run check:icons` fails when a committed icon no longer matches its master. `icons.json` says where the outputs go. Nothing else draws icons.

Waiting for the maintainer's approval of the drafted design:

- `icon.svg`: the full-colour mark, text converted to outlines, the art inside the central 66 of a 108 dp canvas.
- `icon-mono.svg`: a single-colour silhouette, used for Android's themed launcher icon and the notification icon.
- `icon-maskable.svg` (optional): full-bleed art, if the Play icon should use it (`playMaster`).
- The background colour (`background` in `icons.json`; the current value is a placeholder).

Both apps use the same masters: `icons.json` lists one Android entry per app (only the parent app's writes the Play icon). Until the masters exist, both apps still use Android system icons and `check:icons` is not part of `validate`. When the masters land, in the same change: run `npm run icons`, point both manifests at `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, switch every `setSmallIcon` to `R.drawable.ic_stat`, and add `check:icons` to `validate`.
