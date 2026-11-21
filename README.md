# YTM Legacy (Android)

Lekki klient YouTube Music dla Androida **2.3 i nowszych**, w stylu Holo z ery Androida 4.
Działa bez konta, bez reklam i bez śledzenia. Metadane pochodzą z InnerTube (WEB_REMIX),
a dźwięk prosto z serwerów YouTube (googlevideo). Nie ma tu Invidious ani żadnego pośrednika.

## Funkcje

- Zakładki **Główna / Odkrywaj / Biblioteka / Ustawienia**, lupa w prawym górnym rogu
  (podpowiedzi, historia wyszukiwań, filtry: utwory, teledyski, albumy, wykonawcy, playlisty).
- Strony albumów, playlist (ze stronicowaniem), wykonawców, list przebojów, nastrojów i gatunków.
- **Teraz odtwarzane**: okładka, tytuł, wykonawca, pasek postępu, losowo, pętla (wszystko lub
  jeden utwór), suwak głośności, kolejka (prawy górny róg), tekst piosenki, wyłącznik czasowy,
  ulubione i pobieranie. Ekran zamyka się na trzy sposoby: trójkątem w lewym górnym rogu,
  przeciągnięciem palcem w dół albo przyciskiem Wstecz.
- **Nieskończone radio**. Kontynuacja radia YouTube zaczyna się powtarzać, więc aplikacja
  zapamiętuje wszystkie zagrane utwory. Gdy nowych jest za mało, losuje świeży utwór-ziarno
  spośród ostatnio zagranych.
- **Odtwarzanie w tle** z powiadomieniem.
  - Android 2.3: zwykłe powiadomienie.
  - Android 3.0–4.0: powiadomienie z przyciskami.
  - Android 4.0–4.4: przyciski i okładka na ekranie blokady (RemoteControlClient).
  - Android 5+: MediaSession i MediaStyle, czyli systemowe kontrolki multimediów.
  - Działają też przyciski słuchawek i Bluetooth.
- **Jakość**: Niska (AAC 48 kb/s), Normalna (AAC 128 kb/s), Wysoka (Opus 160 kb/s, tylko
  Android 8+). Można ustawić osobną jakość dla sieci komórkowej i dla pobierania.
- **Cache** (można go wyłączyć): odtwarzane i kolejne utwory z kolejki zapisują się na dysku
  (LRU z limitem rozmiaru), więc startują od razu.
- **Pobieranie do offline** pojedynczych utworów, albumów i playlist, razem z okładką.
- **Import i eksport playlist CSV**: Spotify (Exportify), Google Takeout (YouTube), listy
  linków i ID. Utwory bez ID są dopasowywane po tytule, wykonawcy i długości.
- **Crossfade** z regulacją długości (1–12 s), **korektor** (presety i pasma), wzmocnienie
  basu, wirtualizer, normalizacja głośności (dane głośności z YouTube).
- Motyw **ciemny i jasny**. Motyw systemowy jest dostępny od Androida 10.
- Funkcje, których dana wersja Androida nie obsługuje, są wyszarzone z dopiskiem, od której
  wersji działają.

## Jak gra dźwięk

1. `player` (klient VISIONOS z visitorData) zwraca URL audio z googlevideo. Jeśli to się nie
   uda, aplikacja używa klienta ANDROID (itag 18).
2. googlevideo dławi zwykłe GET-y. Dlatego aplikacja pobiera plik kawałkami (nagłówek Range)
   do cache, a MediaPlayer czyta go przez lokalny serwer na `127.0.0.1`.
3. Na Androidzie 4.4+ MediaPlayer odtwarza DASH MP4 bezpośrednio.
4. Android 2.3–4.3 nie czyta MP4 we fragmentach, więc plik jest przepakowywany do
   klasycznego MP4. Próbki audio zostają bez zmian, zmienia się tylko kontener.
5. Jeśli jakieś urządzenie odrzuci DASH MP4, aplikacja sama to wykrywa i przechodzi na
   przepakowanie.

## TLS na starych Androidach

- Android 4.0–4.4: TLS 1.2 przez Conscrypt.
- Android 2.3: Conscrypt kończy się tam natywnym abortem, więc zostaje TLS systemu.
- Android do 7.1: kilka aktualnych certyfikatów głównych jest dołączonych do aplikacji
  (`res/raw/extra_roots.pem`). Weryfikacja certyfikatów nigdy nie jest wyłączana.

## Budowanie

```
JAVA_HOME=<JDK 17> ./gradlew assembleRelease
```

- APK: `app/build/outputs/apk/release/app-release.apk`.
- Release jest podpisany lokalnym kluczem debug. Przed publikacją podmień go na własny klucz.
- Testy (parsery na nagranych odpowiedziach InnerTube, CSV, kolejka): `./gradlew testDebugUnitTest`.

## Sprawdzone na

- Emulatory: Android 2.3.7, 4.1, 4.4, 13.
- Telefon: Samsung Galaxy S4 (Android 4.4.2).

Jeśli YouTube zmieni wersję klienta, w ustawieniach można zmienić „Wersję klienta WEB_REMIX”.
