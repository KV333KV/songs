# Songsmith for Android and Android Auto

Say what you want to hear, and Claude composes it, a built-in synth band records it, and it plays. This works in the car through Android Auto, with your voice, without touching the screen.

> "Hey Google, play melancholic acid trance on Songsmith"
> "Hey Google, play more energy on Songsmith"

## What it does

- **Hands-free in the car.** Android Auto lists Songsmith as a media app. Ask for a song by voice. Songsmith says "Composing…", plays a soft chord bed while Claude writes, then announces the title and plays the song. If you start with "make it…", "add…" or "change…", it reworks the last song instead.
- **One-tap ideas on the car screen.** Under *Start a song* there are quick ideas (Surprise me, Acid techno, Melancholic trance, Warehouse rave, Hard techno, Psytrance). *Your songs* has everything you've made.
- **Built for high-energy electronic music.** Techno, acid, trance and rave, melancholic and instrumental by default, with a 909 kit, 303-style acid bass, rave stabs and supersaw leads. Optional male, female or choir vocals if you ask for them.
- **Phone app.** It has a Create screen (type or dictate, pick singers and a style), a Library (play songs, read lyrics, change a song by voice, delete) and Settings. Driving mode is one giant talk button.
- Songs use the same format as the Pocket Songsmith web app.

Two honest limits:

- The singers are synthesized. They sing the real melody, shaped by the vowels of the lyrics, but you won't hear the actual words.
- The instruments are simple synths. For a polished track, export from the web app as MIDI and use better sounds in a DAW.

## Build the APK (no Android Studio needed)

1. Create a **private** repository on GitHub and upload this folder.
2. Open the **Actions** tab. The *Build APK* workflow runs automatically, and you can also start it with *Run workflow*.
3. When it finishes, open the run and download **songsmith-apk**. It contains `app-debug.apk`.

## Build it with Android Studio instead

1. Open this folder in Android Studio (Ladybug or newer).
2. If it asks about the Gradle wrapper, let it create one, or run `gradle wrapper --gradle-version 8.11.1`.
3. Choose **Run** with your phone plugged in, or use *Build → Build APK(s)*.

## Install and set up

1. Copy the APK to your phone and open it. Allow installs from your browser or file manager when Android asks.
2. Open Songsmith, then go to **Settings**. Paste an Anthropic API key from console.anthropic.com and tap **Test connection**.
   - Each song is one request to the Claude API, billed to that API account. This is separate from a claude.ai subscription.
3. Pick your default singers on the **Create** screen.

## Turn it on in Android Auto

Android Auto only shows apps from the Play Store unless you allow unknown sources:

1. On your phone go to **Settings → Connected devices → Android Auto**. The path may differ slightly on your phone.
2. Scroll to **Version** and tap it about ten times until developer settings unlock.
3. Open the **⋮** menu, then **Developer settings**, and turn on **Unknown sources**.
4. Back in Android Auto, open **Customise launcher** and make sure **Songsmith** is ticked.
5. Connect to the car. Songsmith appears with your other media apps.

### Voice commands that work

| Say | What happens |
| --- | --- |
| "Hey Google, play **hard acid techno at 145** on Songsmith" | Composes and plays a new track |
| "…play **a short melancholic trance loop** on Songsmith" | "Short" makes a one-minute loop |
| "…play **darker and harder** on Songsmith" | Reworks the last track |
| "…play **Golden Hour** on Songsmith" | Plays a saved song with that title |
| "…play Songsmith" | Plays your latest song |

The exact wake phrase depends on your phone. On phones that use Gemini in Android Auto, the same "play … on Songsmith" phrasing applies.

## How it's built

- `PlaybackService.kt` is a Media3 `MediaLibraryService`. It provides the Android Auto browse tree and voice search, and handles compose and refine requests and text-to-speech announcements.
- `ClaudeClient.kt` calls the Messages API with streaming.
- `Prompts.kt` holds the composing instructions shared with the web app.
- `Song.kt` contains the song model and a validator for Claude's JSON.
- `Synth.kt` is an offline renderer: synths, drums, formant-filter singers and reverb, output as WAV.
- `MainActivity.kt` is the phone UI, written in Jetpack Compose.

The model is set in Settings and defaults to `claude-sonnet-5-5`.
