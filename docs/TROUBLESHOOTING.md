# Troubleshooting Voxora

For Voxora 0.4.1, start with **Diagnostica** on the watch. It shows the current activity, network, audio route, and last reported error. Before sharing a screenshot, check it for chat titles or message text.

## The app opens, but ChatGPT cannot be reached

Check the watch's internet connection. It may use Wi-Fi or a paired phone, but the network label in Voxora does not confirm that ChatGPT is reachable. Try loading the conversation list. If only Voice fails, its live connection may be unavailable even when the list works.

If Diagnostics reports a session or authentication error (including 401/403), the account session on the watch may have expired or been rejected. Use your own signed-in desktop session and repeat the [one-time setup](INSTALL_AND_SETUP.md#4-transfer-your-own-session). Do not share session cookies in issues, logs, or chats.

## A conversation cannot be reopened

Tap **Aggiorna** in **Conversazioni**. For older chats, load more pages and select the conversation again. A 404 or unavailable-message error may mean the conversation is no longer accessible. Verify it in ChatGPT from a browser. Search on the watch filters only the conversations already fetched.

## Voice stays on opening or a call drops

Open **Diagnostica** and check the network route. If the call fails, end it before retrying. For a 429/rate-limit error, wait for the displayed countdown. Voice may work differently over Wi-Fi and a paired phone's internet connection.

If no audio comes out, open **Uscita** and select the intended speaker or connected device, then check **Volume**. A paired Bluetooth device may appear only while connected. The output actually in use is displayed separately from your saved preference.

## Dictation cannot record, send, or read a response

Grant Voxora microphone permission in the watch's Android app settings if it was denied. Check the local **10 min / 24 MiB** recording limit. When the draft appears, read it before tapping **Invia**.

If Voxora says the send result is uncertain, check the ChatGPT conversation first. **Do not send the same draft again until you know whether it arrived.** If the message was sent and only spoken playback failed, use **Riprova lettura** to retry the audio for the existing reply.

Audio position seeking and speed changes wait for the file to finish downloading. During a partial download, use **Pausa** if available.

## Installation or update fails

For a missing ADB device, reopen the watch's wireless debugging page and reconnect using its current address; the port can change. Keep the watch and computer on the same network during setup. The pairing port and the regular debugging port can be different.

The prebuilt 0.4.1 APK has a **debug signature**. Android will reject an update signed with a different certificate. Uninstalling an existing installation can erase saved account data and preferences, so preserve anything you need before making that choice. If the provisioning helper reports a `run-as` failure, confirm the supplied debug APK is installed under `local.watchvoice`.

For platform commands, checksum verification, and the account setup helper, use the [installation guide](INSTALL_AND_SETUP.md).

## Known limits

The menus are in Italian. Voice and language changes take effect on the next call or recording. Voice captions may be incomplete. The app was tested on a Galaxy Watch7, but installation and account setup have not been independently tested on another user's watch. Changes to ChatGPT's web interfaces may affect Voxora.
