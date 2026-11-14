# Using Voxora on your watch

Voxora 0.4.1 has Italian menus. This guide explains the watch controls in English and keeps their Italian labels so you can find them.

First, [install Voxora and set up your account session](INSTALL_AND_SETUP.md). The watch needs internet access. The app has been tested on a Galaxy Watch7; other models have not been verified.

## Start with a conversation

Open Voxora. The main screen shows the selected conversation title, the current mode, and a short status line. Tap **Nuova** to prepare a new conversation, or tap the conversation icon at the top to open **Conversazioni** (Conversations).

In the conversation list you can:

- Tap a conversation to select it. Selection alone does not turn on the microphone.
- Tap **Aggiorna** (Refresh) to fetch the latest list.
- Scroll and use **Carica altre chat** (Load more chats) to reach older conversations.
- Use **Ricerca** (Search) to filter chats already loaded on the watch, or show only favorites.
- Open a chat's details to mark it as a favorite on this watch and request recent message previews.

The list shows conversations available to your signed-in ChatGPT session. If a chat is missing, refresh and check it in ChatGPT. Search filters chats already loaded on the watch.

## Voice: have a live conversation

Choose **Voice** from the top-right mode control if needed. Make sure the right conversation title appears on the main screen, then tap **Avvia** (Start). The app requests microphone permission when needed and shows its connection status while opening the call.

When the status changes to **In ascolto** (Listening), speak normally. During the call:

- **Disattiva** mutes the watch microphone. **Riattiva** turns it back on. Muting the microphone keeps the call open.
- **Termina** ends the call. When the connection is still opening, the main control may show **Annulla** (Cancel).
- **Volume** adjusts output volume. **Uscita** in settings chooses the watch speaker or an available audio device.
- **Scambi Voice** opens the text that Voxora has received from the live exchange, when available. It is not a guaranteed complete transcript.

The status shows when the session is listening, speaking, or processing. It cannot show every internal step. If ChatGPT returns a usage limit, Voxora displays a retry countdown.

**Voice settings take effect on the next call.** Changing the requested voice or language while a call is active does not replace the ongoing voice session.

## Dictation: record, review, send

Use the top-right control to switch to **Dettatura** (Dictation). On the main screen, tap **Registra** (Record) and speak. Tap **Termina** when finished. Voxora transcribes the recording and presents a **Bozza** (Draft) before sending it.

Review the transcribed text, then tap **Invia** (Send). **Registra di nuovo** replaces the unsent draft with a fresh recording; **Scarta** discards the draft. The local recording limit is **10 minutes or 24 MiB**. Voxora reports a limit failure rather than silently cutting and sending part of a message.

After sending, wait for confirmation and the response. If Voxora reports that the send result is **uncertain**, check the conversation in ChatGPT before trying again. Sending the same request again could duplicate it. The app has a dedicated **Chiudi dopo controllo** action for this case.

## Read Aloud: listen to a response

After a Dictation reply arrives, Voxora can read the answer aloud. The audio screen displays playback progress and text previews for **Tu** (You) and **ChatGPT**. Tap either preview to read the available full text.

Use **Pausa** / **Riprendi** to pause or resume. The speed control cycles through **0.75×, 1×, 1.25×, and 1.5×**. Seeking and speed controls become available after the audio download finishes. **Ferma lettura** returns playback to the beginning; **Ricomincia lettura** replays the same downloaded response. If audio playback fails after the response text arrives, use **Riprova lettura** to retry its audio without resending the message.

During an active operation, switching modes or chats may require confirmation or remain disabled until the operation finishes. Follow the message on the screen.

## Preferences and watch shortcuts

Open **Opzioni** or **Impostazioni** (Settings). Available controls include requested voice and voice language (Italian, English, Spanish, French, German), volume, **Uscita** (speaker or available connected device), **Vibrazioni** (vibration), **Testo grande** (large text), **Didascalie** (captions), and **Schermo acceso in chiamata** (keep screen on during a call).

**Ripristina preferenze confermate** restores the most recently confirmed working settings, when such a saved set exists. The voice language setting affects voice interactions; **it does not translate Voxora's Italian interface**.

The Wear OS tile can reopen the last chat, prepare a new one, or open Voxora. There is also a watch-face complication. How you add them depends on your watch and launcher. These shortcuts do not start recording.

## When something does not work

Open **Diagnostica** (Diagnostics) from the menu or status view. It reports the activity state, observed network type, audio route, and available error details. The displayed network type alone does not prove that the service is reachable. For practical recovery steps, see [Troubleshooting](TROUBLESHOOTING.md).

Voxora uses ChatGPT web interfaces. Changes to those interfaces or session rules may affect the app.
