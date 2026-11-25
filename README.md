# Jarvis Sveglia

Sveglia Android che si programma da sola leggendo Google Calendar.

- Suona all'inizio di ogni evento il cui titolo contiene **"Sveglia"** (parola modificabile nell'app), per esempio `[Jarvis Routine] Sveglia`.
- Tutti gli altri eventi con un orario diventano **promemoria**: notifica con suono all'inizio dell'evento (anticipo configurabile), con orario e descrizione; toccandola si apre l'evento.
- Legge il calendario del telefono, che Android sincronizza con Google: nessun login, nessuna chiave API.
- Gestisce gli eventi ricorrenti uno per uno.
- Si aggiorna da sola quando un evento cambia (osservatore del calendario), ogni 30 minuti, dopo ogni sveglia, al riavvio e al cambio d'ora.
- Usa `AlarmManager.setAlarmClock`: scatta anche con il telefono in Doze e mostra l'icona della sveglia nella barra di stato.
- Schermata a tutto schermo sul blocco schermo, volume crescente, vibrazione, posticipo configurabile, posticipo automatico dopo 10 minuti.

## Compilazione

L'APK viene compilato da GitHub Actions a ogni push su `main` (artifact `JarvisSveglia-apk`).
In locale: `gradle :app:assembleRelease` con Android SDK 34 e JDK 17.

La chiave in `keystore/` serve solo a firmare sempre allo stesso modo, così ogni nuova versione si installa sopra la precedente.
