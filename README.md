# tvDroid 17

Launcher pour téléviseurs Android TV, construit à partir de l’expérience validée sur une Xiaomi Mi TV sous Android TV 10. Cette V1 reprend l’interface et les fonctions conservées dans le projet : dock et grille, diaporama de films/séries, panneaux de contrôle et de sources, service des touches SET/INPUT, réglages personnalisés et économiseur d’écran avec baisse réelle du rétroéclairage sur le firmware Xiaomi testé.

> **Compatibilité :** le projet vise Android 8 (API 26) et supérieur, mais les panneaux système, le rétroéclairage et les touches de télécommande dépendent du fabricant. SET/INPUT et `picture_backlight` ont été intégrés pour le firmware Xiaomi testé ; leur prise en charge n’est pas garantie sur toutes les TV.

## Ce que fait l’application

- Affiche les applications Android TV dans une grille, avec un dock ordonné et déplaçable. Les listes de favoris et d’applications masquées se règlent dans `app/src/main/res/values/config.xml`.
- Charge un slider de films et de séries depuis Cinemeta. Le premier visuel, son logo ou son titre de remplacement, le dock et les commandes ne sont révélés ensemble qu’une fois le premier slide prêt.
- Affiche les informations et l’action **Regarder** seulement si le contenu possède un identifiant IMDb compatible et que le paquet `com.playtorrio.tv` est installé. La visibilité de ce paquet est déclarée explicitement dans le manifeste ; l’action reste inerte sinon.
- Ouvre un centre de contrôle, les entrées TV disponibles et des pages de réglages visuellement intégrées. Les actions système non accessibles à une application ordinaire sont déléguées aux panneaux Android.
- Reçoit SET/INPUT via un service d’accessibilité, lorsque le firmware et les réglages de la TV le permettent. Le script d’installation active ce service et sélectionne le launcher comme écran d’accueil.
- Laisse Android et le firmware gérer les touches de volume. La répétition lors d’un appui long a été constatée sur la TV de référence ; tvDroid 17 ne remplace pas ce routage système.
- Fournit un économiseur d’écran noir avec horloge rouge et météo Open-Meteo. La ville est définie manuellement ; aucune géolocalisation n’est utilisée. Sur la Xiaomi de référence, l’économiseur règle `picture_backlight` à 1 puis restaure la valeur précédente à sa fermeture. Cette intégration fabricant demande `WRITE_SECURE_SETTINGS`, accordée par ADB lors de l’installation.
- Peut afficher les bandes-annonces YouTube et les résumés français si un jeton de lecture TMDB est fourni. Le jeton est stocké hors du dépôt et copié sur l’appareil au déploiement ; il n’est pas inclus dans l’APK.

## Installer une version publiée

Les APK publiés seront attachés aux versions GitHub dans **Releases**. Sur la TV, autorisez le débogage réseau dans les options développeur et récupérez son adresse IP. Depuis un ordinateur du même réseau, installez Android Platform Tools, puis :

```sh
adb connect ADRESSE_IP_DE_LA_TV:5555
adb install -r tvDroid17-v1.0.0.apk
adb shell cmd package set-home-activity --user 0 com.tvdroid17/.MainActivity
```

`adb install -r` installe l’application, mais n’accorde pas à lui seul l’accès nécessaire au rétroéclairage ni le service SET/INPUT. Pour activer les intégrations de la TV Xiaomi de référence, construisez et déployez avec le script de la section suivante. Les commandes de retour au launcher d’origine sont plus bas.

## Construire et déployer (Linux/macOS ou Windows)

Prérequis : [JDK 17+](https://adoptium.net/), [Android Studio / Android SDK](https://developer.android.com/studio), Android Platform Tools, Python 3 et ADB autorisé par la TV. Clonez le dépôt GitHub, puis ouvrez un terminal à sa racine :

```sh
git clone https://github.com/foutaizzz/tvDroid17.git
cd tvDroid17
```

```sh
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
TV_HOST=ADRESSE_IP_DE_LA_TV ./build-and-install.sh
```

Sous Windows PowerShell :

```powershell
python -m venv tools\env
tools\env\Scripts\pip.exe install -r tools\requirements.txt
.\build-and-install.ps1 -TvHost ADRESSE_IP_DE_LA_TV
```

Au premier déploiement, acceptez l’autorisation de débogage sur l’écran de la TV et cochez « Toujours autoriser ». Le script compile l’APK, l’installe, accorde les droits nécessaires, active SET/INPUT et choisit tvDroid 17 comme accueil. Il désactive également le launcher Google indiqué par `STOCK_LAUNCHER` (par défaut `com.google.android.tvlauncher`) ; cette étape est propre à la configuration Xiaomi de référence et réversible. N’utilisez pas ce script sans vérifier le nom du launcher d’origine sur un autre modèle.

La clé ADB propre à votre ordinateur est conservée dans `~/.config/tvdroid17/adbkey/`, hors du dépôt. Le répertoire peut être déplacé avec la variable `TV_ADB_KEY_DIR`.

Le jeton TMDB est facultatif. Placez-le sur votre ordinateur dans `~/.config/tvdroid17/tmdb-token` (une ligne), ou définissez `TMDB_TOKEN_FILE`. Sans jeton, le launcher fonctionne mais les fonctions TMDB associées restent indisponibles.

## Personnaliser pour sa TV

Toutes les valeurs du profil de compilation sont regroupées dans `app/src/main/res/values/config.xml` :

- `dock_apps` : paquets du dock, dans l’ordre souhaité. Les paquets absents sont ignorés.
- `hidden_apps` : paquets à masquer de la grille. Ce sont des identifiants Android, pas les noms visibles des applications. Ne reprenez pas ceux de Xiaomi sur une autre marque sans les avoir vérifiés.
- `columns` : nombre de colonnes de la grille.
- `live_tv_package` : paquet de l’application TV du fabricant si une entrée particulière doit être ciblée.
- `weather_city`, `weather_latitude`, `weather_longitude`, `weather_timezone` : emplacement fixe de l’économiseur. Remplacez les quatre valeurs ensemble. Les coordonnées peuvent être trouvées dans une carte en ligne ; aucune permission de localisation n’est demandée.

Après une modification de `config.xml`, reconstruisez et redéployez l’APK. Le profil par défaut du dépôt conserve le dock utilisé pour la V1 de référence ; adaptez les paquets à vos propres applications. La grille détecte automatiquement les apps exposées par `LEANBACK_LAUNCHER` ou `LAUNCHER`.

PlayTorrio est détecté au lancement et au retour du launcher. Pour une autre application de lecture, son paquet et son activité devront être intégrés explicitement dans `PlayTorrioDetails` ; il ne suffit pas de modifier le nom visible de l’application.

## Revenir au launcher d’origine

Sur le modèle Google TV de référence :

```sh
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher
adb shell pm enable-user --user 0 com.google.android.tvlauncher
```

Si le paquet du launcher d’origine diffère, remplacez-le par celui relevé sur la TV. La désinstallation de tvDroid 17 ne réactive pas nécessairement un launcher précédemment désactivé.

## Aide par une IA

Les règles destinées aux assistants de programmation sont dans [`AGENTS.md`](AGENTS.md). Elles décrivent l’architecture, les réglages personnalisables, les contraintes de la télécommande et les limites propres au firmware. Commencez par demander à l’assistant de lire ce fichier et le README, puis donnez-lui le modèle de la TV, sa version Android et le changement précis souhaité. N’envoyez jamais de jeton TMDB, de clé ADB ou de données de compte dans un prompt ou dans Git.

## Dépôt et licences

Application Android : `app/`. Scripts ADB : `tools/`. Listes de désactivation optionnelles et propres à certains modèles : `debloat/`. Ces listes n’interviennent pas dans l’installation du launcher et ne sont pas appliquées par le script principal.

Le code est publié sous MIT (voir `LICENSE`). La police Outfit incluse conserve sa licence SIL OFL dans `FONT-LICENSE-OFL.txt`. Les métadonnées de catalogue viennent de Cinemeta ; les informations météo proviennent d’Open-Meteo. Les marques et applications tierces restent la propriété de leurs éditeurs.
