# Enyo

Enyo is an Android launcher with Today widgets, app Cards, an Inbox, Quick controls, and a searchable app library. It supports Android 12 and newer.

## Install

Download the APK from [GitHub Releases](https://github.com/Project-Enyo/enyo-launcher/releases). Open Enyo and set it as your home app in Permissions.

Turn on notification access to add supported conversations to Inbox. Messaging apps can also connect directly through [UnifiedInbox](https://github.com/Project-Enyo/UnifiedInbox). Enyo can act as a UnifiedPush distributor using an ntfy server.

## Inbox

When chats link to the same Android contact, Enyo combines their history and unread counts in one thread. Single platform chats show an app badge on the avatar. For chats that Enyo cannot link automatically, open the thread and use its contact or merge action. Replies in a merged thread go through the conversation selected in the composer. Chat messages and Local AI replies render Markdown.

## Search

Search covers apps, settings, contacts, calendar events, saved messages, and files. A query such as “Message Alex to say that I’m on my way” can open an SMS draft with the recipient and message filled in. Review the draft and send it in your messaging app. Drafts can also use chat apps with an available UnifiedInbox or notification reply. Search understands flashlight, alarm and timer requests. With Local AI selected, use **Interpret request** for unfamiliar wording. Draft previews offer spelling and grammar corrections.

Under **Settings → Search**, choose a folder or enable **All files access** in Android Settings. Enyo searches filenames and extracts text from plain text, DOCX, XLSX, PPTX, ODT, ODS, and ODP files. PDF text search requires Android 15 or newer. Scanned PDFs need an existing text layer.

## Local AI

On ARM64 devices with at least 5 GiB of RAM reported by Android, **Settings → Search** can download the models for natural language image and document search. The download is about 178 MB. Enyo builds the index while the device is charging and idle. It uses quantized CLIP for images and MiniLM for document passages. The index stays in the encrypted archive on the device.

For local chat, open **Settings → Local AI → Download recommended model**. Enyo downloads and verifies [Qwen3 0.6B INT4](https://huggingface.co/litert-community/Qwen3-0.6B-int4), then selects Local AI as the default assistant. The model takes about 347 MB. You can also import a compatible LiteRT LM file. Model downloads show progress in Android notifications and in Enyo’s overlay when enabled.

The recommended Qwen model supports German. Local AI uses the app language for replies, and direct clock questions use Android’s formatted local time and time zone. Prompts and generation stay on the device, and saved chats appear under **AI conversations** in Inbox. Local AI can refer to relevant excerpts of earlier chats and show action previews inside a conversation. Use the sparkle button on a message or notification to summarize it. You can also ask Local AI for a summary of recent Inbox activity. When requested, it can use battery, Wi-Fi, Bluetooth, local time, upcoming calendar events, and the latest available weather widget reading.

## Build

With JDK 21 and Android SDK 36 installed, build the debug APK.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
