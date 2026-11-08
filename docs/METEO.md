# Economiseur rouge et meteo — 19 octobre 2026

L'economiseur ClockDream utilise maintenant un cadran analogique rouge sur fond noir, avec 12, 3, 6 et 9, trois aiguilles et les graduations de la reference fournie. A droite : Paris, temperature arrondie en degres Celsius, icone et conditions en francais, puis maximum et minimum du jour. Le widget de batterie de l'iPhone est omis sur la TV.

Les elements sont dessines en vecteurs, sans image ni video. L'affichage est redessine une fois par seconde ; un petit decalage progressif evite une position totalement fixe. La meteo est chargee hors du fil d'affichage, uniquement pendant l'economiseur. Les requetes et le worker sont arretes lorsqu'on en sort. Les delais de veille et la selection com.gathin.tvhome/.ClockDream sont conserves.

La ville est fixe : Paris, latitude 48.8566, longitude 2.3522. Aucune geolocalisation IP ou GPS n'est demandee. Le fournisseur est Open-Meteo, sans compte ou cle API. La requete HTTPS utilise current=temperature_2m,weather_code,is_day et daily=temperature_2m_max,temperature_2m_min, avec le fuseau Europe/Paris et un jour de prevision.

Actualisation toutes les vingt minutes. Un petit JSON conserve la derniere reponse valide : au maximum deux heures, uniquement pour le jour courant a Paris. Apres echec, une nouvelle tentative a lieu cinq minutes plus tard. Les donnees anciennes encore utilisables portent la mention Dernieres donnees disponibles ; au-dela de la limite, le cadran affiche Meteo indisponible et aucune temperature. Aucun nombre de demonstration n'est affiche en production.

L'attribution Open-Meteo.com reste discrete au bas de la meteo. Documentation : https://open-meteo.com/en/docs . Les codes WMO determinent les conditions et les symboles (soleil, lune, nuages, pluie, neige, brouillard et orages).

Validation : WeatherDreamInstrumentation-result.txt teste les valeurs, les libelles francais, le rejet des temperatures manquantes et des donnees perimees, le rendu noir/rouge 1080p, un appel HTTPS reel depuis la TV et le cache. Le demarrage et la sortie du vrai DreamService sont verifies sur la TV. Capture : screensaver-meteo.png. APK precedent : launcher-avant-meteo.apk.
