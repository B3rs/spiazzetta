# Spiazzetta

**An unofficial Android app for Piazzetta pellet stoves that run on the Micronova Agua IoT platform.**

Spiazzetta talks to the same cloud bridge your stove already uses (the Navel 2.0 / T009 module, the
backend behind the official *MyPiazzetta* app). It reads the stove state and writes control
registers, and it confirms every command by reading the value back from the stove. Known good on
the Piazzetta P220M; other stoves on the same Agua platform should work too, because nothing in the
app is hard-coded for one model.

The name is a family joke: **Piazzetta** → **Spiazzetta**, from "spiazzare", to catch someone off
guard. The old app gets spiazzata.

Written in Kotlin with Jetpack Compose. Android 8.0 (API 26) or newer. It is not made by, endorsed
by, or supported by Piazzetta or Micronova.

## Why this exists / Perché esiste

**English.** Spiazzetta comes out of plain frustration. The official MyPiazzetta app is terrible at
the things an owner does every day: it takes a dozen taps to change one setting, the screens fight
you on a small phone, the state you see is stale, and the stove sometimes does not do what you
asked with no sign of why. Worse, the app never checks whether a command actually landed - it
posts the write to the cloud and moves on, so a fan speed or a setpoint can silently do nothing and
you only find out by walking over to the stove. This app exists to fix the everyday tasks: one tap
per change, every command sent to the stove, read back, retried, and reported as confirmed or
rejected. It is not a reimplementation of the official app for its own sake; it is what the
everyday use of that stove should have felt like in the first place.

**Italiano.** Spiazzetta nasce dalla frustrazione, pura e semplice. L'app ufficiale MyPiazzetta è
pessima nelle cose che un proprietario fa tutti i giorni: servono dieci tocchi per cambiare una
impostazione, le schermate si incastrano su un telefono piccolo, lo stato che vedi è vecchio, e a
volte la stufa non fa quello che hai chiesto senza dirti perché. Peggio ancora: l'app non verifica
mai se un comando è davvero arrivato - manda la scrittura al cloud e va avanti, quindi una velocità
ventola o una temperatura impostata possono non fare assolutamente nulla e tu lo scopri solo
andando davanti alla stufa. Questa app esiste per sistemare i compiti di ogni giorno: un tocco per
ogni modifica, ogni comando inviato alla stufa, riletto, ritentato e riportato come confermato o
rifiutato. Non è una riscrittura dell'app ufficiale tanto per farla; è come l'uso quotidiano di
quella stufa sarebbe dovuto essere fin dall'inizio.

## Unofficial and use at your own risk

> **This app is unofficial.** It has no affiliation with Piazzetta, Micronova, or
> Omniaweb. Nobody at those companies makes it, checks it, or supports it. It was written by a stove
> owner who read the cloud traffic of the official app.
>
> **It controls a real heating appliance.** A wrong setpoint, power level, or combustion correction
> can waste pellets, foul the burn pot, damage the stove, or make it run unsafely. The stove's own
> safety devices and the manufacturer's instructions always come first. Never bypass them.
>
> **You must accept a disclaimer before you can use the app.** The text is shown once, on first
> launch, before the login screen, and nothing works until you read it and confirm. Your acceptance
> is stored on the device with the disclaimer version and the time; a new version of the text asks
> you to accept it again.
>
> **No warranty of any kind.** You use this app at your own risk. The full legal text is in
> [docs/LEGAL.md](docs/LEGAL.md).

## Install on your phone

You install the APK yourself. There is no Play Store listing.

1. Open the [Releases page](https://github.com/B3rs/spiazzetta/releases/latest) and download the
   newest `.apk` file to your phone.
2. Allow installs from unknown sources for the app you used to download it (browser or file
   manager). On Android 8 and newer this is usually Settings → Apps → Special access → Install
   unknown apps, and you grant it per app.
3. Open the downloaded APK and confirm the install. Android may warn that the file is not from the
   Play Store; that warning is expected for a sideloaded app.
4. Read the disclaimer and accept it.
5. Sign in with your own MyPiazzetta email and password, then pick the stove linked to your
   account.

Requires Android 8.0 (API 26) or newer. Updates install over the previous version, so your saved
login and your dashboard layout survive an upgrade.

## What it does

**Every command is a confirmed write.** The official app posts a register write and moves on
without checking the result, which is why a fan speed change can quietly do nothing. Spiazzetta
treats each command as a transaction: send the write to the Agua bridge, read the stove state back
from the cloud, compare the value with what you asked for, retry up to three times with growing
backoff if it did not take, then tell you plainly which happened. The banner either says the
setting was applied and confirmed by the stove, or that it was not applied after three attempts.
The activity list shows the command while it is queued, then its confirmed or failed state. The
screen never shows a value the stove has not confirmed.

**The dashboard panels.** Each one is a card you can open, showing live values and, where the
stove exposes them, the matching control:

- room temperature and stove status, labelled with the words the cloud itself reports
- target temperature
- power level (flame level and pellet consumption)
- fan speed
- combustion correction, the pellet quality trim for the fuel you are burning
- ducting, left and right (separate fan outlets for other rooms)
- natural mode (natural convection, fan off)
- multifire (the multi-fuel combustion setting)
- weekly programmes stored in the stove

**Panel order is yours.** Open the customise screen and choose which panels the dashboard shows and
in which order. If the stove heats one room, hide the ducts. Hidden panels and their order are saved
on the device.

**A register browser for stoves the app does not know yet.** "See all registers" lists exactly what
the stove reports: the register key, the raw value, and the decoded value. "Export JSON" shares the
whole dump so you can map a stove model the app has not seen. The included
[P220M map](docs/registers/p220m.json) was captured this way.

**Italian and English.** Switchable inside the app. The cloud is asked for register labels in the
chosen language, so the stove's own wording matches.

## Privacy and credentials

Your MyPiazzetta email and password go to one place: the Agua cloud bridge, over HTTPS. That is the
same host the official app already talks to. Nothing else receives them.

They are stored only on your phone, in the app's private storage, so you don't have to type them
again after a restart. There is no server owned by the author anywhere, no relay, no proxy, no
backend in the path.

The app has no analytics, no ads, and no telemetry. It does not phone home. There is no crash
reporting service.

This repository contains no API keys, tokens, or credentials of any kind, and it never will. Access
works only with your own MyPiazzetta account, which you must have already, with the stove paired to
it. Don't commit your credentials to a fork.

## Build from source

You need JDK 17 and the Android SDK with platform 34 installed.

```bash
git clone https://github.com/B3rs/spiazzetta.git
cd spiazzetta
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

To build a version that can only read: append `-PreadOnly=true` to the assemble command. That
disables every write path, so the app cannot send a control command to any stove. Use it if you
only want to watch a stove, or if you are testing against real hardware.

For development without a real stove, `tools/mock-agua-server.mjs` is a mock Agua bridge that
mirrors a P220M register map and honours masked writes the way the controller does:

```bash
node tools/mock-agua-server.mjs          # listens on port 8099
./gradlew :app:assembleDebug -PqaBaseUrl=http://10.0.2.2:8099/
```

`10.0.2.2` is the host machine as seen from the Android emulator. The mock is a development aid
only; it is not a cloud service and it never touches your stove.

Unit tests run with `./gradlew :app:testDebugUnitTest` and use OkHttp's mock web server, so they
need no network.

## How it works

One HTTPS endpoint: `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/`. Requests carry the
action name in the `url` header plus the `CustomerCode` and `IdBrand` headers, and the bridge
answers with an asynchronous job: a read starts a buffer read and a status poll, and a write goes
through `deviceRequestWriting` and then needs its own confirmation.

The app carries no register addresses. Offsets, masks, ranges, formulas, and labels arrive per stove
from `deviceGetRegistersMap`, and Spiazzetta drives everything from that map. Values are decoded
with the map's own formula (`#/2` for a temperature, for example) and written back with the inverse
formula, so a write is the exact value the stove expects. Confirmed writes then read the register
back and compare, retrying up to three times.

The reverse-engineered details, each claim cited to a line of the shipped MyPiazzetta bundle, live
in [docs/protocol/PROTOCOL.md](docs/protocol/PROTOCOL.md). The captured P220M register map, in the
same shape the app consumes, is [docs/registers/p220m.json](docs/registers/p220m.json).

## Disclaimer and license

**License: PolyForm Noncommercial 1.0.0.** Copyright (c) 2026 Marco Bersani. The full text is in
[LICENSE](LICENSE).

**Personal, noncommercial use only.** You are free to build it, install it, use it, study it, change
it, and share it with other stove owners, as long as nobody is making money from it and it is not
used in the course of a business.

**Commercial use is not granted.** Companies - Piazzetta, Micronova, Omniaweb, their distributors,
dealers, installers, and service partners included - may not sell this app, bundle it with a
product, ship it to customers, host it, or use it in support of anything commercial. Manufacturing
the same stove ecosystem does not grant that right: the code and the technique here belong to the
author.

**If a company wants to use this technology, it pays for a license.** That includes embedding it,
rebranding it, using it as a reference implementation, or building a product on top of it. Open an
issue on this repository and ask: commercial licensing is available at a price agreed case by case.
There is no public price list and no free commercial exception.

The MIT license this project started under no longer applies; everything in this repository is
covered by the terms in [LICENSE](LICENSE) from this commit onwards.

Spiazzetta is an unofficial app. It has no affiliation with, and is not endorsed or supported by,
Piazzetta, Micronova, or Omniaweb. It is provided as is, without warranty of any kind, express or
implied, including fitness for a particular purpose. You use it entirely at your own risk, and you
are responsible for any damage to the appliance, your property, or people. The safety devices on
your stove and the manufacturer's instructions always take precedence.

Read [docs/LEGAL.md](docs/LEGAL.md) before you install anything. It is the authoritative version of
the disclaimer, the one the app asks you to accept.
