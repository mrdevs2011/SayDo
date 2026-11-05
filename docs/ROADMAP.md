# SayDo — Ishonchlilik Roadmap

**Maqsad:** foydalanuvchi aytgan buyruq **eshitilsin** va yozilgan harakatlar **xuddi o'zi qilgandek** bajarilsin.
Qoida: *ishonchlilik funksiyadan muhimroq.* Yangi funksiya yo'q, avval mavjudi 99% ishlasin.

## 0. Hozirgi holat (halol baho)

| Qatlam | Holat | Asosiy muammo |
|---|---|---|
| Ovoz | Beqaror | `SpeechRecognizer` doimiy tinglash uchun mo'ljallanmagan: sessiyalar orasida tanaffus (cooldown 1.8s), OEM o'ldirishi, tizim tovushi, faqat `en-US`. README "100% offline" deydi, kod esa online'ga o'tadi. |
| Ijro | Beqaror | `GestureService.step()` open-loop: ekranni tekshirmaydi, xatodan keyin davom etadi, muvaffaqiyat mezoni noto'g'ri (`failures < list.size()`), anchor `g.x/g.y` ni doimiy o'zgartiradi. |
| Kod | Shishgan | `kernel/` (Vulkan, chaos, mesh, "omega") asosiy yo'lga hech qanday foyda bermaydi, real qurilmada tekshirilmagan. |
| Test | Yolg'on ishonch | Unit-testlar mock bilan o'tadi, lekin real telefonda ovoz va gesture sinalmagan. |

## Tamoyillar

1. **O'lchamasdan tuzatma.** Har bosqich boshida metrika, oxirida o'sha metrika bo'yicha natija.
2. **Closed-loop.** Har harakatdan keyin natijani tekshir. Ko'r-ko'rona davom etma.
3. **Xavfsiz xato.** Shubha bo'lsa to'xta va ayt. Noto'g'ri ekranda bosishdan ko'ra to'xtash yaxshi.
4. **Bitta o'zgarish, bitta o'lchov.** Bir vaqtda ovoz va ijroni o'zgartirma.
5. **Real qurilma.** Kamida 3 ta (Xiaomi/HyperOS, Samsung/OneUI, Pixel yoki toza Android) sinovsiz bosqich yopilmaydi.

## Bosqich 1 — O'lchov va poydevor (1 hafta)

*Nima uchun birinchi:* o'lchamagan narsani yaxshilab bo'lmaydi.

- [x] **Metrikalar** (`metrics/Metrics.java` → `metrics.jsonl`): eshitilgan/tanilgan trigger, STT kechikishi, STT xatolari, makro ishga tushishi, xato qadam va sababi, inson tekshiruvi.
- [x] **Benchmark ekrani** (`BenchmarkActivity`, Debug → Benchmark): ovoz testi (makro ishga tushmaydi), makro testi (har ijrodan keyin inson "to'g'rimi?" deydi), hisobot.
- [x] **Protokol va 10 etalon makro ro'yxati:** [BENCHMARK.md](BENCHMARK.md).
- [x] **README ni to'g'rilash:** isbotlanmagan iboralar olib tashlandi, ma'lum cheklovlar yozildi.
- [ ] **Boshlang'ich o'lchov (qurilmada):** 10 etalon makroni yozish, 3 qurilmada ovoz va makro testini o'tkazish, jadvalni to'ldirish.
- ~~`kernel/` ni build'dan chiqarish~~ → **Bosqich 5 ga ko'chirildi.** Sabab: `GestureService`, `VoiceService`, `FloatService`, `Gesture` kernel klasslariga bog'langan (`NodeAnchor`, `SmartGestureDispatcher`, `ParametricMatcher` va b.). Ularni hozir uzish o'lchovdan oldin xulq-atvorni o'zgartiradi. `SayDoKernel.start()` hech qayerda chaqirilmaydi, ya'ni daemon/mesh ishlamaydi.

**Tayyor mezoni:** boshlang'ich raqamlar jadvali (BENCHMARK.md) 2.9.4 uchun to'ldirilgan.

## Qo'shimcha (2.10.0): Ovoz darvozasi

Foydalanuvchi so'rovi bilan Bosqich 3 dan oldin qo'shildi. Baseline o'lchovini buzmaslik uchun **standart holatda o'chiq**.

- [x] `audio/VoiceGate`: o'z `AudioRecord` (16 kHz), har 20 ms dBFS, 1.2 s pre-roll, 60 ms ketma-ket baland ovozda ochiladi.
- [x] Android 13+: pre-roll + jonli ovoz `EXTRA_AUDIO_SOURCE` orqali recognizer'ga beriladi (triggerning boshi saqlanadi).
- [x] Recognizer oqimni qabul qilmasa, avtomatik "restart" rejimga o'tadi va buni eslab qoladi (sozlamadan qayta sinash mumkin).
- [x] Android 12 va past: restart rejim (mikrofon bo'shatiladi, recognizer ishga tushadi, triggerning boshi yo'qolishi mumkin).
- [x] Kalibrlash ekrani (asosiy ekran → **Ovoz ⚙**): jonli o'lchagich, 2 bosqichli kalibrlash, chegara slayderi, "ochildi" chirog'i.
- [x] Metrikalar: `gate_open` (rejim, dB), `gate_fallback`; hisobotda "darvoza ochildi → trigger topildi %".
- [ ] Qurilmada o'lchash: Benchmark ovoz testini darvoza **o'chiq** va **yoqiq** holatda alohida o'tkazib, taqqoslash.
- Bosqich 3 da (Vosk) darvoza o'zgarishsiz qayta ishlatiladi: pre-roll to'g'ridan-to'g'ri Vosk'ga beriladi.

## Bosqich 2 — Closed-loop ijro (2–3 hafta) ← eng yuqori ustuvorlik

Foydalanuvchi shikoyati: 40–50 qadamlik makroda bitta xato hammasini buzadi.

**2.1 Ma'lumot modeli.** Har `Gesture` ga "kutilgan holat" qo'shiladi:
- `expectPkg` (paket nomi), `expectActivity` (mumkin bo'lsa)
- `expectTexts[]` (ekrandagi 2–4 ta barqaror matn/ID)
- `timeoutMs` (default 4000)
- Eski makrolar bilan moslik: maydon bo'lmasa eski usul.

**2.2 Yozish.** Har harakatdan keyin 300–500 ms kutib, accessibility daraxtidan holat suratini oladi va saqlaydi.

**2.3 Ijro (`step()` ni qayta yozish).**
1. Bosishdan oldin: kutilgan ekran paydo bo'lishini **kut** (polling 100 ms, `timeoutMs` gacha). Qat'iy tanaffus emas.
2. Maqsadni **node bo'yicha** top (matn/ID/`contentDescription`) → `ACTION_CLICK`. Koordinata faqat zaxira.
3. Bajargach natijani tekshir (ekran o'zgardimi).
4. Bo'lmasa **2 marta qayta urin**, so'ng **to'xta** va `{qadam, sabab, ekran surati}` qaytar.
5. Ijro tugaganda "OK" faqat oxirgi ekran kutilganga mos kelsa.

**2.4 Xatolar tuzatish:**
- [ ] `finish(...)` muvaffaqiyat mezoni: `failures == 0` va oxirgi ekran mos.
- [ ] Anchor topgan koordinatani **saqlama** (faqat shu ijroda ishlat).
- [ ] `onCancelled` da jim davom etishni olib tashla.
- [ ] Vaqt siljishini yig'ma emas, mutlaq vaqtga tenglash.

**2.5 Foydalanuvchiga ko'rsatish.** "9/50 qadamda to'xtadi: kutilgan ekran chiqmadi" xabari, sabab bilan.

**2.6 Cheklov.** `FLAG_SECURE` (bank, PIN) ekranlarida daraxt ko'rinmaydi. Bunday qadamlar belgilanadi va ochiq "kafolatlanmagan" deyiladi.

**Tayyor mezoni:** 10 etalon makroning kamida 9 tasi 20 ketma-ket ishga tushirishda 19+ marta oxirigacha ishlaydi. Xato bo'lsa, hech qachon noto'g'ri ekranda davom etmaydi.

## Bosqich 3 — Ovoz dvigateli (2–3 hafta)

Hozirgi `SpeechRecognizer` o'rniga **grammar-cheklangan offline tanish**.

- [ ] **Prototip** (1 hafta, alohida branch): Vosk (kichik model, `grammar` bilan faqat foydalanuvchi triggerlari + `[unk]`). `AudioRecord` bilan mikrofon oqimi, foreground service (`microphone` turi).
- [ ] Taqqoslash: Bosqich 1 benchmarkida eski vs yangi. Yangi dvigatel **kamida eshitish ulushida +20 punkt** bersa o'tamiz. Bo'lmasa muqobil (sherpa-onnx KWS yoki Porcupine) sinaladi.
- [ ] Til: trigger tili foydalanuvchi tanlovi (en/uz/ru), tegishli model yuklanadi.
- [ ] Triggerlar o'zgarganda grammar qayta quriladi (modelni qayta yuklamasdan).
- [ ] **Yolg'on ijobiy himoyasi:** ishonch chegarasi (confidence), 2 qisqa trigger o'xshash bo'lsa ogohlantirish, ijrodan oldin ixtiyoriy tasdiqlash.
- [ ] Eski `SpeechRecognizer` ni zaxira sifatida **vaqtincha** qoldirib, keyin olib tashlash.
- [ ] Batareya o'lchovi: 1 soat ekran o'chiq, % sarfi. Maqsad ≤ 3%/soat.

**Cloud STT:** asosiy yo'lga qo'shilmaydi (narx, maxfiylik, kechikish). Faqat kelajakda ixtiyoriy "erkin buyruq" rejimi uchun ko'riladi.

**Tayyor mezoni:** 3 qurilmada trigger eshitish ≥ 95% (jim xonada), ≥ 85% (shovqinda), yolg'on ishga tushish < 1 / soat. Mikrofon tovushi/beep yo'q.

## Bosqich 4 — Fon ishlash va OEM (1–2 hafta)

- [ ] Xiaomi/Samsung/Huawei fon o'ldirishini real qurilmada sinash: 8 soat kutish testi, service tirikmi.
- [ ] `onTaskRemoved`, qayta ishga tushish (boot receiver), batareya optimizatsiyasi yo'riqnomasi (`OemPermissionHelper`) haqiqiy qurilmada tekshiriladi.
- [ ] Mikrofon boshqa ilova (qo'ng'iroq, yozuv) bilan to'qnashganda to'g'ri to'xtash/davom etish.
- [ ] Android 14+ foreground service cheklovlari bo'yicha tekshiruv.

**Tayyor mezoni:** 8 soatlik kutishdan keyin trigger ishlaydi (3 qurilmada).

## Bosqich 5 — Tozalash va sifat (1 hafta)

- [ ] `experimental/` (ex-`kernel/`) ni, ishlatilmayotgan qismlarini o'chirish: kamroq kod = kamroq xato.
- [ ] Mock testlarni **instrumented testlarga** almashtirish (real Accessibility bilan, kamida emulyatorda).
- [ ] Crash log + foydalanuvchi "xato haqida xabar" tugmasi (debug ekranidan yig'ish).
- [ ] Hujjatlarni haqiqatga moslash (`ARCHITECTURE.md`, `RELIABILITY.md`, "Omega sertifikat" ni olib tashlash).

## Bosqich 6 — Beta (2 hafta)

- [ ] 5–10 real foydalanuvchi, turli telefonlar.
- [ ] Anonim metrika (faqat ishlash ulushi, ovoz/ekran mazmuni emas). Foydalanuvchi roziligi bilan.
- [ ] Eng ko'p to'xtagan qadamlar bo'yicha tuzatishlar.
- [ ] Relizdan oldin: Bosqich 2 va 3 mezonlari yana qayta tekshiriladi.

## Xavflar

| Xavf | Ehtimol | Choralar |
|---|---|---|
| Vosk yetarlicha aniq emas | O'rta | Prototip + benchmark; muqobil KWS |
| Ba'zi ilovalar accessibility daraxtini yashiradi | Yuqori | Koordinata zaxirasi + ochiq cheklov ro'yxati |
| OEM fonda baribir o'ldiradi | O'rta | Foydalanuvchi yo'riqnomasi, ilovani "himoyalangan" ro'yxatga qo'shish |
| Closed-loop ijro sekinroq | Past | Kutish faqat kerak bo'lganda; ekran tayyor bo'lsa darhol |
| Vaqt hajmi baholanganidan ko'p | O'rta | Har bosqich mezonga erishmaguncha keyingisi boshlanmaydi |

## Tartib va jami vaqt

`1 → 2 → 3 → 4 → 5 → 6` ≈ **9–12 hafta.**
Bosqich 2 va 3 mustaqil, lekin **bir vaqtda o'zgartirilmaydi**: avval 2 (foydalanuvchi eng ko'p shikoyat qilgan joy), keyin 3.

## Reliz qoidasi

Versiya chiqarilmaydi, agar:
1. 10 etalon makro ishlamasa (Bosqich 2 mezoni),
2. Benchmark oldingi versiyadan yomonlashgan bo'lsa,
3. Yangi funksiya qo'shilgan bo'lsa-yu, uning o'lchovi bo'lmasa.
