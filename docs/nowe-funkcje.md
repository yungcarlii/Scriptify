# Scriptify — co doszło (dokumentacja)

Wszystko poniżej działa po stronie serwera, na czystym kliencie (Fabric 1.21.11). Gracze nic nie
instalują.

---

## 1. Cue — skrypty scen (`/cue`)

**Co to jest:** cue to jeden „moment" sceny: tytuł, pauza, piorun, kwestia dialogowa, śmierć. Odpalasz
go jedną komendą i za każdym razem dzieje się dokładnie to samo, w tym samym tempie, na każdym
ujęciu.

**Uprawnienie:** `scriptify.admin` (operatorzy mają je automatycznie). Komendy w cue wykonują się
z uprawnieniami osoby, która go odpaliła — nigdy z większymi.

### Najprostszy sposób: nagraj scenę, robiąc ją

```
/cue record zasadzka
```
Teraz po prostu wpisujesz komendy sceny w tempie, w jakim mają się potem odtwarzać, np.:
```
/title @a title "Dzień 7"
/weather thunder
/summon lightning_bolt ~ ~ ~5
/fake death Steve Herobrine
```
Na pasku akcji widzisz czerwone **● Recording zasadzka – step N**, czyli że krok się zapisał. Na
koniec:
```
/cue done
```
Każda komenda jest zapisana razem z prawdziwym czasem, jaki minął od poprzedniej. Czas przed pierwszą
komendą się nie liczy (nie wchodzi do sceny to, że się zastanawiałeś, co wpisać).

### Odtworzenie

```
/cue play zasadzka
```
- Działa z czatu, z command blocka pod przyciskiem i z innego cue (cue może odpalać cue).
- Komendy wykonują się **po cichu** — żadnego „Teleported Steve to…" na czacie, bo czat jest
  w kadrze.
- `/cue stop zasadzka` zatrzymuje jeden cue, a `/cue stop` wszystkie.

### Pisanie ręcznie

```
/cue new intro
/cue add intro title @a title "Odcinek 1"
/cue wait intro 2.5
/cue add intro playsound minecraft:entity.wither.spawn master @a
```
- `/cue add <nazwa> <komenda>` dodaje krok z komendą (ukośnik na początku jest opcjonalny).
- `/cue wait <nazwa> <sekundy>` dodaje pauzę (np. `0.5`, `3`).
- Komenda z błędem (literówka, zła składnia) zostaje odrzucona od razu przy dodawaniu, a nie w połowie
  ujęcia.

### Podgląd i poprawki

| Komenda | Co robi |
| --- | --- |
| `/cue` lub `/cue list` | Lista wszystkich cue; kliknięcie odpala cue |
| `/cue show <nazwa>` | Wszystkie kroki z sekundą, w której startują; kliknięcie kroku wpisuje komendę usuwania |
| `/cue remove <nazwa> <numer>` | Usuwa krok o danym numerze |
| `/cue delete <nazwa>` | Usuwa cały cue |
| `/cue record <istniejąca nazwa>` | Dogrywa kolejne kroki na koniec istniejącego cue |

**Zapis:** jeden plik na cue, w `<świat>/scriptify/events/<nazwa>.json`. Nazwy mogą zawierać
małe litery, cyfry, `-` i `_` (do 32 znaków).

---

## 2. Fałszywe zdarzenia (`/fake`)

Iluzje jako komendy, więc cue i command blocki mogą je odpalać. Wszystko wygląda dokładnie jak
w vanilli (te same klucze tłumaczeń i ekrany), ale nic nie dzieje się naprawdę.

**Uprawnienie:** `scriptify.ondeath`.

Pierwsze słowo to zawsze **kto**: nick gracza, jego pseudonim albo selektor (`@a`, `@s`, `@b`…).
Wszystko po nim to tekst.

| Komenda | Co pokazuje |
| --- | --- |
| `/fake join <gracze>` | „X joined the game" i przywraca gracza do taba |
| `/fake leave <gracze>` | „X left the game" i chowa gracza z taba (dalej jest na serwerze) |
| `/fake death <kto>` | „X died" |
| `/fake death <kto> <zabójca>` | „X was slain by <zabójca>"; zabójca nie musi być online ani istnieć, może mieć spacje |
| `/fake advancement <kto> task\|goal\|challenge <tytuł> \| <opis>` | Linia na czacie jak w vanilli (z dymkiem po najechaniu) i prawdziwy toast w rogu ekranu gracza |
| `/fake kick <kto> [powód]` | Prawdziwy ekran wyrzucenia i zwykłe „left the game" |
| `/fake ban <kto> [powód]` | Ekran „You are banned from this server", ale gracz **nie** jest zbanowany i może od razu wejść z powrotem |
| `/fake chat <kto> <wiadomość>` | `<kto> wiadomość` na czacie. Gracz online mówi swoim wyświetlanym nickiem (z pseudonimem i kolorem drużyny); każde inne słowo to nick kogoś, kogo nie ma na serwerze (np. Herobrine) |
| `/fake whisper <do kogo> <od kogo> <wiadomość>` | „<od kogo> whispers to you: …” szarą kursywą, widoczne tylko dla jednego gracza |
| `/fake lightning [pozycja]` | Prawdziwy piorun z grzmotem, ale bez ognia, obrażeń i naładowanych creeperów. Bez pozycji uderza tam, gdzie patrzysz |
| `/fake explosion [pozycja]` | Wygląd i dźwięk wybuchu i nic więcej: żadnych zniszczonych bloków, obrażeń ani odrzutu. Widoczny z daleka. Bez pozycji wybucha tam, gdzie patrzysz |

**Przykłady:**
```
/fake advancement Steve challenge Zdrada | Wbij nóż w plecy najlepszemu przyjacielowi
/fake advancement Steve task Diamenty!
/fake death @a Herobrine
/fake death Steve The Wither
/fake ban Steve Griefowanie spawnu
/fake chat Herobrine Widzę cię.
/fake whisper Steve Herobrine Odwróć się.
/fake lightning ~ ~ ~10
```

**Przykład sceny jako cue** (piorun, grzmot, Herobrine się odzywa, ktoś „ginie”):
```
/cue new herobrine
/cue add herobrine weather thunder
/cue add herobrine fake lightning ~ ~ ~8
/cue wait herobrine 1.5
/cue add herobrine fake chat Herobrine Nie powinniście tu przychodzić.
/cue wait herobrine 3
/cue add herobrine fake explosion ~ ~ ~8
/cue add herobrine fake death Steve Herobrine
```
Potem na każdym ujęciu wystarczy `/cue play herobrine`. Pozycje `~` liczą się od miejsca osoby,
która odpala cue.
- **Ikona osiągnięcia** to przedmiot, który trzymasz w ręce (trzymasz diament, to jest diament).
  Z konsoli albo bez przedmiotu w ręce ikoną jest blok trawy.
- `task` i `goal` są zielone, `challenge` jest fioletowe, jak w vanilli.
- Fałszywe osiągnięcie znika zaraz po toaście: nie ma go w menu osiągnięć i nic się nie zapisuje.
- `kick` i `ban` działają na jednego gracza; selektor obejmujący kilku bierze pierwszego.

---

## 3. Reset planu między ujęciami (`/stage`)

```
/stage save wioska 32
/stage reset wioska
```
- `save` kopiuje każdy blok w sześcianie wokół ciebie (domyślnie promień 24, maksymalnie 48, czyli
  97 bloków na bok). Kopiowane jest też powietrze, więc reset czyści gruz, a skrzynie i tabliczki
  razem z zawartością.
- `reset` kładzie wszystko z powrotem dokładnie tak, jak było: po wybuchu, walce czy „griefie" plan
  jest gotowy na drugie ujęcie. Nic przy tym nie wypada, skrzynie nie wysypują zawartości, piasek nie
  spada, a TNT się nie zapala.
- Gracze i boty to byty (entities), więc zostają tam, gdzie stoją. Ramki na przedmioty i stojaki na
  zbroję **nie** są przywracane.
- `/stage` lub `/stage list` pokazuje zapisane plany, a `/stage delete <nazwa>` usuwa plan.
- Reset można wrzucić do cue, np. `/cue add reset stage reset wioska`.

**Zapis:** `<świat>/scriptify/stages/<nazwa>.nbt`. **Uprawnienie:** `scriptify.admin`.

Sprawdzone na serwerze: skrzynia z 5 diamentami usunięta i zastąpiona złotym blokiem, a po `reset`
złoty blok zniknął i skrzynia wróciła z diamentami.

---

## 4. Odliczanie (`/countdown`)

```
/countdown 60 Manhunt startuje za
/countdown stop
```
- Pasek bossa u wszystkich graczy online (także u tych, którzy wejdą w trakcie), z czasem w formacie
  `0:42`.
- Pasek płynnie się opróżnia i zmienia kolor z zielonego przez żółty na czerwony.
- Ostatnie 3 sekundy to duże tytuły „3”, „2”, „1” z coraz wyższym dźwiękiem, a na koniec gra dźwięk
  level-upu.
- Bez tytułu pasek pokazuje „Starting in”. Naraz działa jedno odliczanie; nowe zastępuje stare.
- Pasuje do cue: `/cue add start countdown 10 Start za`, potem `/cue wait start 10`, a dalej to, co
  ma się stać na zero.

**Uprawnienie:** `scriptify.player`.

---

## 5. Durability w GUI (`/durability`)

`/durability` otwiera menu (podkomend `max` i `random` już nie ma):
- **Max** naprawia wszystko, co gracz ma w ekwipunku i na sobie.
- **Random** otwiera wybór:
  - **Heavily Worn** (mocno obite): ok. 15% wytrzymałości.
  - **Moderately Worn** (średnio obite): ok. 50%.
  - **Lightly Worn** (ledwo obite): ok. 85%.
  - **Custom**: wpisujesz liczbę 1–100, gdzie 100 to nic nie jest zniszczone, a 1 to wszystko prawie
    na wykończeniu.

Każdy przedmiot dostaje trochę inną wartość (do ±10% od wybranego poziomu), żeby nie wyglądało to
zbyt idealnie. Przy 100 wszystko jest naprawdę całe, przy 1 prawie zepsute, ale nic nigdy się nie
niszczy. Przycisk „Durability” w `/scriptify` otwiera to samo menu.

**Uprawnienie:** `scriptify.inventory`.

---

## 6. Boty: „Fight on sight" (atak po zauważeniu)

**Gdzie:** `/bots` → **Settings** → **Targeting** → **Fight on sight** (ikona tarczy strzelniczej).
Albo komendą: `/bots settings fight-on-sight true`. Domyślnie wyłączone.

**Co robi:** bot, który ma jakieś zajęcie, rzuca je, gdy kogoś zauważy, i atakuje. Zajęcie to:
- ścieżka (path),
- scenariusz (rozmowa, tłum, więzienie, spawn),
- odtwarzanie nagranego ruchu,
- wander,
- chodzenie za graczem (follow).

Po walce wraca do tego, co robił:
- ścieżka rusza dalej od miejsca, w którym bot ją przerwał,
- nagranie czeka na swojej klatce i leci dalej od niej (bot wraca na nią skokiem),
- scenariusz znowu przejmuje bota tam, gdzie akurat stoi.

**Kiedy bot „zauważa":**
- ktoś jest **przed nim** (stożek ok. 140°) w promieniu **24 bloków** i jest go widać (nie przez
  ścianę),
- albo ktoś jest **bliżej niż 5 bloków** z dowolnej strony (słyszy go),
- albo ktoś go uderzył.

**Kogo atakuje:** tylko tych, na których pozwalają inne ustawienia z Targeting. Na przykład
**Target players** musi być włączone, żeby atakował graczy. Drużyny (factions) są respektowane, a bot
nigdy nie atakuje gracza, za którym chodzi. Główny przełącznik **Combat AI** też musi być włączony.

Boty bez zajęcia działają jak dotąd, według **Auto target**.

Sprawdzone na serwerze: bot chodzący za innym botem przy wyłączonej opcji ignorował „intruza" stojącego
przed nim. Po włączeniu opcji (w trakcie, bez restartu) w ciągu kilku sekund go namierzył i zaatakował.

---

## 7. Poprawki

- **`/sudo` działa.** Wcześniej każda komenda `/sudo <gracz> command …` i `/sudo <gracz> say …`
  kończyła się błędem składni (argument gracza „zjadał" resztę linii). Sprawdzone na serwerze: bot
  wykonał komendę i powiedział linię.
- **Nagrywanie cue sprawdzone:** przez `/sudo` na bocie. `/cue record` zapisało dwie komendy
  z prawdziwym odstępem 2,1 s.
- **Crash serwera z botami** (`IndexOutOfBoundsException` w `BotTicker.detourStep`): bot, który
  utknął tuż obok punktu swojej ścieżki, wywalał cały serwer. Teraz wraca na ścieżkę albo jest na
  nią przenoszony.
- **Menu uprawnień i rang** nie pokazywały 15. uprawnienia (**Bots**), więc nie dało się go
  przełączyć z menu. Teraz pokazują wszystkie. W menu uprawnień gracza przycisk roli **Cast** był
  dodatkowo zasłonięty przyciskiem rangi; teraz jest widoczny.
- Stary, niedokończony silnik eventów (pliki `events/*.json`) działa teraz naprawdę: krok `delay`
  faktycznie czeka, a krok `title` pokazuje tytuł zamiast linii na czacie.

---

## Czego nie sprawdziłem w grze

Sprawdzone na serwerze deweloperskim z konsoli i na bocie:
- tworzenie, dodawanie, walidacja, podgląd, lista i usuwanie cue,
- odtwarzanie z dokładnym odstępem 2 s między krokami,
- linie `/fake` na czacie.

Wszystkie testy jednostkowe przechodzą.

Bez prawdziwego klienta nie dało się zobaczyć:
- toastu fałszywego osiągnięcia na ekranie,
- ekranów kick/ban (bot nie ma prawdziwego połączenia),
- wyglądu poprawionych menu.

Warto raz to przeklikać na serwerze.
