# Examples

One folder per TV model, each with a `debloat.txt` listing the packages that are safe to disable
on that firmware, grouped and commented. These are starting points: comment out anything you use.

| Folder | TV | OS | Notes |
|---|---|---|---|
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 | PatchWall, Mi analytics, SmartShare, MiLink |
| `tcl-g10-google-tv/` | TCL 55" 4K Google TV, 2023 (G10, G10_4K_US_NF) | Google TV 12 | TCL TV+, TCL Live, store demo, smartlink installer |
| `xiaomi-mitv-axso0/` | Xiaomi MiTV-AXSO0 (dangal) | Android TV 9 | PatchWall, Mi analytics, MiLink; voice, Chromecast, unused streaming apps off by choice |
| `sanyo-atv-r2/` | SANYO ATV R2 (SCBC, Realtek RTD285O) | Android TV 9 | S-Channels, E-SHOW demo, Vewd, TV Booster, SANYO OTA |
| `oneplus-tv-dubai/` | OnePlus TV (D00, Dubai) | Google TV 14 | HeyTap/OnePlus extras, OnePlus Connect, Smart Home |

To add your own TV, list its packages with `adb shell pm list packages`, pick the bloat,
and put them in a new `debloat/<your-tv>/debloat.txt`. Apply with:

```bash
TV_HOST=<tv ip> tools/disable.sh debloat/<your-tv>/debloat.txt
```

## ⚠️ Ne jamais toucher à `com.mediatek.wwtv.tvcenter` (LiveTV / TvCenter)

C'est la **seule** app du firmware qui gère l'intent passthrough
(`ACTION_VIEW` + type `vnd.android.cursor.item/channel`). Si elle est désactivée
**ou désinstallée**, chaque entrée HDMI affiche « Non disponible sur cette TV » —
dans tvDroid 17 comme dans le launcher stock. Elle reste donc active mais est cachée
de la grille d'apps via `hidden_apps` dans `config.xml`.

Si elle a été désinstallée par erreur : `pm install-existing --user 0 com.mediatek.wwtv.tvcenter`
