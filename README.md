# Enyo

Enyo is an Android launcher with Today widgets, app Cards, an Inbox, Quick controls, and a searchable app library. It supports Android 12 and newer.

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Project-Enyo/enyo-launcher)

Install Enyo with [Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically, or download the APK from [GitHub Releases](https://github.com/Project-Enyo/enyo-launcher/releases). Open Enyo and set it as your home app in Permissions.

Turn on notification access to add supported conversations to Inbox. Messaging apps can also connect directly through [UnifiedInbox](https://github.com/Project-Enyo/UnifiedInbox). Apps that support it share everything their chats hold: formatting, photos, videos, GIFs, stickers, voice messages, files, link previews, music, places and live locations, reactions and quotes. Photos, videos and GIFs open in Enyo, voice messages play inline, and other files open in a matching app. Long-press a message to react, reply to it or copy it, and send files or your location from the chat. They also share profile pictures. Inbox hides group chats unless **Show groups** is on in the UnifiedInbox settings; shown groups carry a group icon. Enyo can act as a UnifiedPush distributor using an ntfy server.

## Inbox

When chats link to the same Android contact, Enyo combines their history and unread counts in one thread. Single platform chats show an app badge on the avatar. For chats that Enyo cannot link automatically, open the thread and use its contact or merge action. Links you make are saved to the contact as chat accounts, so they come back on their own after a reinstall or on another synced device. The thread's options can also save older links, unlink the contact, or unmerge accounts again. Replies in a merged thread go through the conversation selected in the composer. Chat messages and Local AI replies render Markdown.

## Search

Search covers apps, settings, contacts, calendar events, saved messages, and files. A query such as “Message Alex to say that I’m on my way” can open an SMS draft with the recipient and message filled in. Review the draft and send it in your messaging app. Drafts can also use chat apps with an available UnifiedInbox or notification reply. Search understands flashlight, alarm and timer requests. With Local AI selected, use **Interpret request** for unfamiliar wording. Draft previews offer spelling and grammar corrections.

Under **Settings → Search**, choose a folder or enable **All files access** in Android Settings. Enyo searches filenames and extracts text from plain text, DOCX, XLSX, PPTX, ODT, ODS, and ODP files. PDF text search requires Android 15 or newer. Scanned PDFs need an existing text layer.

## Local AI

On ARM64 devices with at least 5 GiB of RAM reported by Android, **Settings → Search** can download the models for natural language image and document search. The download is about 178 MB. Enyo builds the index while the device is charging and idle. It uses quantized CLIP for images and MiniLM for document passages. The index stays in the encrypted archive on the device.

For local chat, turn on **Settings → System → AI features**, then open **Settings → Local AI** and pick a model. Enyo downloads and verifies it, then selects Local AI as the default assistant:

- [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (2.6 GB) is recommended and answers well in German and other languages.
- [Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) (3.7 GB) gives the best answers but is slower and needs 8 GB of RAM.
- [Qwen3.5 2B](https://huggingface.co/litert-community/Qwen3.5-2B) (2.1 GB) is an alternative to Gemma 4 E2B.
- [Qwen3.5 0.8B](https://huggingface.co/litert-community/Qwen3.5-0.8B) (965 MB) is small and quick for simple questions.
- [Qwen3 0.6B INT4](https://huggingface.co/litert-community/Qwen3-0.6B-int4) (347 MB) is the smallest and fastest, but often wrong.

You can also import a compatible LiteRT LM file. Model downloads show progress in Android notifications and in Enyo’s overlay when enabled.

Local AI uses the language of the message for ordinary chat. Direct clock questions use Android’s formatted local time and time zone. Prompts and generation stay on the device, and saved chats appear under **AI conversations** in Inbox. Local AI can refer to relevant excerpts of earlier chats when asked and show action previews inside a conversation. With Gemma 4 or Qwen3.5, it also keeps more of the current chat in mind, brings in clearly related earlier chats on its own, knows the local date and time, and reads recent messages with people you name while the phone is unlocked. Answers that use phone data show cards for the time, weather, battery, upcoming events, connections and the chats Local AI read; a chat card opens that chat. Local AI also keeps a memory of lasting things you tell it about yourself, or anything you ask it to remember, and notes under a reply when it saved something. Review, delete or clear memories, or turn off automatic memory, in **Settings → Memory**. AI features are off by default: turn on **Settings → System → AI features** to use them. Turning it off again keeps models, chats and memories. Use the sparkle button on a message or notification to summarize it. You can also ask Local AI for a summary of recent Inbox activity. When requested, it can use battery, Wi-Fi, Bluetooth, local time, upcoming calendar events, and the latest available weather widget reading.

## Build

With JDK 21 and Android SDK 36 installed, build the debug APK.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
