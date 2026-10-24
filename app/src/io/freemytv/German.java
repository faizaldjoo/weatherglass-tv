package io.freemytv;

/** German copy for text drawn directly by the custom UI. Source keys are the existing English copy. */
final class German {
    private German() {}

    static String text(String english) {
        switch (english) {
            case "In cinemas": return "Im Kino";
            case "Coming soon": return "Demnächst";
            case "No trailer for this film yet.": return "Für diesen Film gibt es noch keinen Trailer.";
            case "Loading films…": return "Filme werden geladen…";
            case ". OK opens the trailer": return ". OK öffnet den Trailer";
            case "With Motion on, the film's artwork drifts slowly.": return "Mit Bewegung an bewegt sich das Filmbild langsam.";
            case "Movie": return "Film";
            case "Live TV": return "Live-TV";
            case "All Apps": return "Alle Apps";
            case "Settings": return "Einstellungen";
            case "Home Settings": return "Start-Einstellungen";
            case "TV Settings": return "TV-Einstellungen";
            case "HDMI Inputs": return "HDMI-Eingänge";
            case "Inputs": return "Eingänge";
            case "Shortcuts": return "Verknüpfungen";
            case "Weather": return "Wetter";
            case "No weather data right now.": return "Gerade keine Wetterdaten.";
            case "Sunrise ": return "Sonnenaufgang ";
            case "Sunset ": return "Sonnenuntergang ";
            case "Crypto": return "Krypto";
            case "24h change": return "Änderung in 24 h";
            case "For You": return "Für dich";
            case "Couldn't open ": return "Konnte nicht öffnen: ";
            case " of ": return " von ";
            case "← → to move · OK to drop · Back to cancel": return "← → verschieben · OK ablegen · Zurück abbrechen";
            case "Moving ": return "Verschiebe ";
            case ", position ": return ", Position ";
            case ", tab ": return ", Tab ";
            case " of 3": return " von 3";
            case "Dollar": return "Dollar";
            case "Series": return "Serie";
            case "prices from ": return "Kurse von ";
            case "Starting…": return "Wird gestartet…";
            case "Original": return "Original";
            case "Home Screen, ": return "Startbildschirm, ";
            case "Screensaver, preview": return "Bildschirmschoner, Vorschau";
            case "no ads, with the new screensaver": return "ohne Werbung, mit dem neuen Bildschirmschoner";
            case "the TV's factory home screen": return "der Startbildschirm ab Werk";
            case ". In use": return ". Aktiv";
            case " of 2": return " von 2";
            case "Your TV's home screen and screensaver": return "Startbildschirm und Bildschirmschoner des Fernsehers";
            case "Home Screen": return "Startbildschirm";
            case "Screensaver": return "Bildschirmschoner";
            case "Preview": return "Ansehen";
            case "On the TV: tick “Always allow” and press OK.": return "Am Fernseher: „Immer zulassen“ ankreuzen und OK drücken.";
            case "RECOMMENDATIONS": return "EMPFEHLUNGEN";
            case "No TMDB key: see the README (config.properties).": return "Kein TMDB-Schlüssel: siehe README (config.properties).";
            case "Error: ": return "Fehler: ";
            case "Fetching titles…": return "Titel werden geladen…";
            case "Links to the titles by Watchmode.": return "Links zu den Titeln von Watchmode.";
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return "Nutzt die TMDB-API, ohne Billigung oder Zertifizierung durch TMDB.";
            case "COMPARISON": return "VERGLEICH";
            case "Home button": return "Home-Taste";
            case "Free memory": return "Freier Speicher";
            case "Home screen memory": return "Speicher des Startbildschirms";
            case "NOTICES": return "HINWEISE";
            case "None yet.": return "Noch keine.";
            case "No ads, with the new screensaver": return "Ohne Werbung, mit dem neuen Bildschirmschoner";
            case "The TV's factory home screen": return "Der Startbildschirm ab Werk";
            case "Switching takes a few seconds; the screen blinks while it measures.": return "Der Wechsel dauert ein paar Sekunden; beim Messen flackert der Bildschirm.";
            case "now": return "jetzt";
            case "Measuring the original home screen…": return "Der ursprüngliche Startbildschirm wird gemessen…";
            case "Switching the home screen and screensaver…": return "Startbildschirm und Bildschirmschoner werden gewechselt…";
            case "Android didn't accept the new home screen": return "Android hat den neuen Startbildschirm nicht angenommen";
            case "Turning off apps (": return "Apps werden ausgeschaltet (";
            case "Measuring Weatherglass TV…": return "Weatherglass TV wird gemessen…";
            case "Couldn't turn on Weatherglass TV: ": return "Weatherglass TV ließ sich nicht einschalten: ";
            case "Turning the apps back on…": return "Die Apps werden wieder eingeschaltet…";
            case "Turning apps back on (": return "Apps werden wieder eingeschaltet (";
            case "Restoring the original home screen…": return "Der ursprüngliche Startbildschirm wird wiederhergestellt…";
            case "Original mode: the factory home screen, screensaver and apps are back.": return "Originalmodus: Startbildschirm, Bildschirmschoner und Apps ab Werk sind zurück.";
            case "the original home screen": return "den ursprünglichen Startbildschirm";
            case "Original mode, but I couldn't turn back on: ": return "Originalmodus, aber das ließ sich nicht wieder einschalten: ";
            case "Couldn't go back to the original: ": return "Zurück zum Original ging nicht: ";
            case "Weatherglass TV's home screen crashed 3 times in a row; I switched back to the original one.": return "Der Startbildschirm von Weatherglass TV ist 3-mal hintereinander abgestürzt; der ursprüngliche ist wieder aktiv.";
            case "the home screen": return "den Startbildschirm";
            case "the screensaver": return "den Bildschirmschoner";
            case "Couldn't check Weatherglass TV: ": return "Weatherglass TV ließ sich nicht prüfen: ";
            case "The TV update turned back on ": return "Das TV-Update hat wieder eingeschaltet: ";
            case "Turned off what was missing: ": return "Ausgeschaltet, was noch fehlte: ";
            case "The TV was updated; Weatherglass TV is still on.": return "Der Fernseher wurde aktualisiert; Weatherglass TV ist weiter aktiv.";
            case "Connecting…": return "Verbindung wird hergestellt…";
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return "Am Fernseher „USB-Debugging zulassen?“ bestätigen („Immer zulassen“ ankreuzen).";
            case "Weatherglass TV's home screen ran into a problem.\nGoing back to the TV's original home screen…": return "Der Startbildschirm von Weatherglass TV hatte ein Problem.\nZurück zum ursprünglichen Startbildschirm…";
            case "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                    + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                    + "show there: Claude Code can remove them from your computer), then try once more.":
                return "die Ersteinrichtung des Fernsehers hat das WLAN gespeichert, und sie auszuschalten würde diese Netzwerke löschen. "
                        + "Unter Einstellungen → Netzwerk und Internet das Netzwerk entfernen und neu verbinden (Netzwerke außer Reichweite "
                        + "erscheinen dort nicht: Claude Code entfernt sie vom Computer aus), dann noch einmal versuchen.";
            // Home Settings, Shelf and Remote Buttons
            case "Language": return "Sprache";
            case "Temperature": return "Temperatur";
            case "Theme": return "Design";
            case "Automatic": return "Automatisch";
            case "Light": return "Hell";
            case "Dark": return "Dunkel";
            case "Navigation sounds": return "Navigationstöne";
            case "Weather widget": return "Wetter-Widget";
            // What TalkBack reads on the drawer's weather card (#47)
            case ". High ": return ". Höchstwert ";
            case ", low ": return ", Tiefstwert ";
            case " Next hours: ": return " Nächste Stunden: ";
            case " Sunrise ": return " Sonnenaufgang ";
            case " Sunset ": return " Sonnenuntergang ";
            case "City": return "Stadt";
            case "Searching…": return "Suche…";
            case "Type the city's name": return "Namen der Stadt eingeben";
            case "Search": return "Suchen";
            case "Cancel": return "Abbrechen";
            case "No city with that name.": return "Keine Stadt mit diesem Namen.";
            case "Couldn't search now: ": return "Suche gerade nicht möglich: ";
            case "Quotes widget": return "Kurs-Widget";
            case "On": return "An";
            case "Off": return "Aus";
            case "Shelf": return "Leiste";
            case "on the shelf, place ": return "in der Leiste, Platz ";
            case "not on the shelf": return "nicht in der Leiste";
            case "Remote Buttons": return "Fernbedienungstasten";
            case "Netflix button": return "Netflix-Taste";
            case "YouTube button": return "YouTube-Taste";
            case "Prime Video button": return "Prime-Video-Taste";
            case "TCL Channel button": return "TCL-Channel-Taste";
            case "Tabii button": return "Tabii-Taste";
            case "Nothing": return "Nichts";
            case "BRAVIA CORE button": return "BRAVIA-CORE-Taste";
            case "Disney+ button": return "Disney+-Taste";
            case "Crunchyroll button": return "Crunchyroll-Taste";
            case "Sony default": return "Sony-Standard";
            case "Sony default: the button opens what it always did.": return "Sony-Standard: Die Taste öffnet, was sie immer geöffnet hat.";
            case "Not installed": return "Nicht installiert";
            case ", selected": return ", ausgewählt";
            case "OK or the arrows change the value.": return "OK oder die Pfeiltasten ändern den Wert.";
            case "OK picks, Back cancels.": return "OK wählt aus, Zurück bricht ab.";
            case "Disney and Globoplay: the TV itself controls those buttons.": return "Disney und Globoplay: Diese Tasten steuert der Fernseher selbst.";
            case "YouTube, Prime Video and Google Play: the TV itself controls those buttons.": return "YouTube, Prime Video und Google Play: Diese Tasten steuert der Fernseher selbst.";
            case "Netflix, YouTube and Prime Video: the TV itself controls those buttons.": return "Netflix, YouTube und Prime Video: Diese Tasten steuert der Fernseher selbst.";
            case "The other buttons: the TV itself controls them.": return "Die anderen Tasten steuert der Fernseher selbst.";
            case "TCL remote buttons: on this TV they work as always.": return "TCL-Fernbedienung: Die Tasten funktionieren hier wie immer.";
            default:
                if (english.startsWith("Weatherglass TV is on: new home screen and screensaver, ")) {
                    return "Weatherglass TV ist an: neuer Startbildschirm und Bildschirmschoner, "
                            + english.substring("Weatherglass TV is on: new home screen and screensaver, ".length()).replace(" apps turned off.", " Apps ausgeschaltet.");
                }
                if (english.startsWith("On · ")) {
                    return "An · " + english.substring(5).replace(" titles from the TV's apps, 3 new ones every hour.",
                            " Titel aus den Apps des Fernsehers, 3 neue pro Stunde.");
                }
                if (english.startsWith("OK adds or removes (up to ")) {
                    return "OK fügt hinzu oder entfernt (bis zu " + english.substring("OK adds or removes (up to ".length())
                            .replace("). ← → change the order.", "). ← → ändern die Reihenfolge.");
                }
                if (english.endsWith(" min ago")) return "vor " + english.substring(0, english.length() - 8) + " Min.";
                if (english.endsWith(" h ago")) return "vor " + english.substring(0, english.length() - 6) + " Std.";
                if (english.endsWith(" days ago")) return "vor " + english.substring(0, english.length() - 9) + " Tagen";
                return english;
        }
    }
}
