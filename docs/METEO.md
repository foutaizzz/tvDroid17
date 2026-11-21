# Économiseur et météo

L’économiseur est fourni par `ClockDream`. Il affiche une horloge analogique rouge sur fond noir et récupère la météo d’Open-Meteo en tâche de fond. Les quatre valeurs `weather_city`, `weather_latitude`, `weather_longitude` et `weather_timezone` dans `app/src/main/res/values/config.xml` définissent la ville de façon fixe. Aucune permission GPS ou localisation IP n’est utilisée.

Les données sont rafraîchies selon la durée de validité configurée dans `WeatherData`; en cas d’absence de réseau, le cadran reste affiché sans météo à jour. Les réponses récentes peuvent être réutilisées depuis un cache local, indexé par coordonnées.

Sur le firmware Xiaomi testé, `ScreensaverBacklight` écrit `picture_backlight=1` au début de l’économiseur puis restaure la valeur précédente à son arrêt. Cette propriété est spécifique au firmware, et la permission `WRITE_SECURE_SETTINGS` doit être accordée par ADB. Sur un autre modèle, vérifier la clé et l’échelle du réglage avant de l’activer.

Données météo : [Open-Meteo](https://open-meteo.com/), licence [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
