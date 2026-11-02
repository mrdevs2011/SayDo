# VoiceFlow

Tap, ushlab turish va slide harakatlarini yozib oladi va ovoz bilan telefonda qayta bajaradi.

## Qanday ishlaydi
- **Interfeys:** `app/src/main/assets/index.html` (WebView ichida).
- **Harakatlarni bajarish:** `GestureService` (Accessibility xizmati, `dispatchGesture`).
- **Ovozni tinglash:** `VoiceService` (mikrofon foreground servisi + `SpeechRecognizer`).
- **Moslashtirish:** `TriggerMatcher` (lotin/kirill, noaniq moslik).

## Birinchi ishga tushirish
1. Ilovani oching, chap yuqoridagi doira tugmani bosing va Sozlamalarda **VoiceFlow** ni yoqing.
   Android 13+ da yoqilmasa: tugmani uzoq bosing (Ilova ma'lumotlari) -> yuqori o'ng **⋮** -> *Cheklangan sozlamalarga ruxsat*, so'ng qaytadan yoqing.
2. O'ng yuqoridagi mikrofon tugmasini bosing, mikrofon ruxsatini bering. Til o'zgartirish: mikrofon tugmasini uzoq bosing (UZ / RU / EN).
3. `+` bilan trigger so'z yozing, ekranda harakatlarni bajaring, yashil galochka bilan saqlang.
4. Trigger so'zni ayting yoki yashil play tugmasini bosing.

## Build (GitHub Actions)
Repoga push qiling, **Actions -> Build APK -> Artifacts -> VoiceFlow-apk**. `v1.0.0` kabi tag bersangiz, APK Releases bo'limiga ham chiqadi.

Imzo kaliti `keystore/voiceflow.jks` repo ichida, shuning uchun yangi versiya eskisining ustiga o'rnatiladi.
Boshqa kalit ishlatish uchun `VF_KEYSTORE`, `VF_STORE_PASS`, `VF_KEY_ALIAS`, `VF_KEY_PASS` muhit o'zgaruvchilarini bering.
