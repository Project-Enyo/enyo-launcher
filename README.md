# Enyo

Enyo is an Android launcher with Today widgets, app Cards, an Inbox, Quick controls, and a searchable app library. It supports Android 12 and newer.

## Install

Download the APK from [GitHub Releases](https://github.com/Project-Enyo/enyo-launcher/releases). Open Enyo and set it as your home app in Permissions.

Turn on notification access to add supported conversations to Inbox. Messaging apps can also connect directly through [UnifiedInbox](https://github.com/Project-Enyo/UnifiedInbox). Enyo can act as a UnifiedPush distributor using an ntfy server.

## Search

Search covers apps, settings, contacts, calendar events, saved messages, and files. A query such as “Send a message to Alex that I’m on my way” can open an SMS draft with the recipient and message filled in. Review the draft and send it in your messaging app.

Under **Settings → Search**, choose a folder or enable **All files access** in Android Settings. Enyo searches filenames and extracts text from plain text, DOCX, XLSX, PPTX, ODT, ODS, and ODP files. PDF text search requires Android 15 or newer. Scanned PDFs need an existing text layer.

## Local AI

On supported ARM64 devices, **Settings → Search** can download the models for natural language image and document search. The download is about 178 MB. Enyo builds the index while the device is charging and idle. It uses quantized CLIP for images and MiniLM for document passages. The index stays in the encrypted archive on the device.

For local chat, import a compatible quantized LiteRT LM model under **Settings → Local AI**. Start with the [Gemma 3 1B IT model collection](https://huggingface.co/litert-community/Gemma3-1B-IT) and choose its portable INT4 LiteRT LM file. Once imported, select **Local AI** as the default assistant. Prompts and generation stay on the device, and saved chats appear under **AI conversations** in Inbox.

## Build

With JDK 21 and Android SDK 36 installed, run:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
