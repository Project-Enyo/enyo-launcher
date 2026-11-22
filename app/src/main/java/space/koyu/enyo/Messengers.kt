package space.koyu.enyo

/**
 * Supported messaging providers. Package ids were checked against Google Play listings or an
 * installed copy; Enyo still only uses providers that PackageManager reports as installed and
 * launchable. Contact account types are the sync accounts a provider adds to the address book,
 * used to corroborate a name based contact suggestion.
 *
 * Verified on the attached phone: Anura, Molly (account type im.molly.app), Discord, platform SMS.
 * WhatsApp account types come from published developer notes. Telegram, Signal, and Viber account
 * types follow their package ids and are unverified. VK packages are absent from Google Play and
 * unverified.
 */
internal enum class Messenger(val packages: Set<String>, val accountTypes: Set<String> = emptySet()) {
    Anura(setOf("space.koyu.apollo.mini", "space.koyu.apollo")),
    Telegram(setOf("org.telegram.messenger"), setOf("org.telegram.messenger")),
    WhatsApp(setOf("com.whatsapp", "com.whatsapp.w4b"), setOf("com.whatsapp", "com.whatsapp.w4b")),
    Molly(setOf("im.molly.app"), setOf("im.molly.app")),
    Signal(setOf("org.thoughtcrime.securesms"), setOf("org.thoughtcrime.securesms")),
    Discord(setOf("com.discord")),
    Beeper(setOf("com.beeper.android")),
    Line(setOf("jp.naver.line.android")),
    Viber(setOf("com.viber.voip"), setOf("com.viber.voip")),
    KakaoTalk(setOf("com.kakao.talk")),
    Vk(setOf("com.vkontakte.android", "com.vk.im")),
    FacebookMessenger(setOf("com.facebook.orca")),
    WeChat(setOf("com.tencent.mm")),
    Sms(setOf("com.android.messaging", "org.fossify.messages"));

    companion object {
        fun of(packageName: String): Messenger? = entries.firstOrNull { packageName in it.packages }
    }
}

internal val messagingPackages: Set<String> = Messenger.entries.flatMapTo(mutableSetOf()) { it.packages }
