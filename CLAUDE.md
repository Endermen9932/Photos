# Arbeitsregeln für dieses Repo

- **Alles wird automatisch nach `main` gemergt.** Jedes Release muss immer alle jemals gemachten
  Änderungen enthalten. Änderungen auf den Arbeitsbranch pushen – der Workflow
  `.github/workflows/release.yml` mergt jeden Push automatisch in `main` und baut das Release aus `main`.
  Niemals Releases aus einem Stand bauen, der nicht in `main` ist.
- **APKs immer mit dem Android-Debug-Key signieren** (`signing/debug.keystore`,
  Alias `androiddebugkey`, Passwort `android`). Keinen eigenen Release-Key verwenden.
- Nach jeder Änderung: pushen, CI abwarten, dem Nutzer den Link zum neuen GitHub Release geben.
- UI-Sprache ist Deutsch; Zielgerät Pixel 10 Pro ohne Google-Dienste (keine Play-Services-Abhängigkeiten).
