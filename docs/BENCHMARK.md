# SayDo — Benchmark protokoli (Roadmap, Bosqich 1)

Maqsad: har versiyani **bir xil usulda** o'lchab, "yaxshilandi" yoki "yomonlashdi" deyishni raqamga tayantirish.

## Benchmark ekranini ochish

Asosiy ekranda **SayDo sarlavhasini uzoq bosing** → Debug → **Benchmark**.

Ekranda 3 bo'lim bor:

| Bo'lim | Nima qiladi |
|---|---|
| 1. Ovoz testi | Triggerlarni aralash tartibda ko'rsatadi, siz aytasiz. Makrolar **ishga tushmaydi**, faqat qaysi trigger tanilgani yoziladi. |
| 2. Makro testi | Bitta makroni N marta ishga tushiradi. Har safar oxirida **siz** "to'g'ri bo'ldimi?" deb javob berasiz. |
| 3. Hisobot | Barcha yozilgan o'lchovlarning xulosasi. "Nusxa olish" bilan chatga yoki faylga joylashtirasiz. |

Xom ma'lumot: `/sdcard/Android/data/uz.saydo.app/files/metrics.jsonl`
```bash
adb pull /sdcard/Android/data/uz.saydo.app/files/metrics.jsonl
```

## Nima uchun inson tekshiruvi kerak

Hozirgi ijro kodi (`GestureService.finish`) hatto deyarli barcha qadamlar xato bo'lsa ham "OK" deydi va
noto'g'ri ekranda bosishda davom etadi. Shuning uchun ilovaning o'z hisobi **haqiqat emas**.
Hisobotdagi **"yolg'on 'OK'"** qatori aynan shuni ko'rsatadi: ilova "OK" dedi, siz "yo'q" dedingiz.

## Sinov sharoiti (har safar bir xil)

- Telefon zaryadi > 50%, quvvat tejash rejimi **o'chiq**.
- Wi-Fi yoqilgan (hozirgi STT ba'zi telefonlarda online'ga o'tadi — buni ham bilishimiz kerak).
- Xona: 1) jim, 2) shovqinli (TV/musiqa ~60 dB). Ikkalasini alohida o'tkazing.
- Telefon stolda, og'izdan ~50 sm. Oddiy ovozda, bir marta ayting.
- Makro testida ovozli tinglashni **o'chiring** (aks holda u aralashishi mumkin).
- Test oldidan Hisobot → **Tozalash**, keyin faqat shu sinov ma'lumotlari qoladi.

## 1. Ovoz testi

1. Kamida **10 ta** turli trigger bo'lsin (qisqa: "youtube"; o'rta: "open camera"; uzun: "send message to mom").
   Ikkita o'xshash juftlik qo'shing (masalan "call mom" / "call tom") — yolg'on moslikni ko'rish uchun.
2. Raundlar: **5** (jami 50 sinov).
3. Har bir ko'rsatilgan iborani aytasiz. 7 soniyada tanilmasa "eshitilmadi" deb yoziladi.

O'lchanadigan: to'g'ri %, noto'g'ri trigger %, eshitilmadi %, kechikish (gap tugashidan to moslikka, p50/p90),
qaysi trigger eng yomon.

## 2. Makro testi — 10 ta etalon makro

Bu makrolarni **bir marta** yozib, keyin har versiyada aynan shularni sinaysiz. Trigger nomini aynan shunday qo'ying
(`bench` so'zi bilan boshlansin), ular oddiy makrolardan ajralib tursin.

| # | Trigger | Qadamlar (taxminan) | Nimani tekshiradi |
|---|---|---|---|
| 1 | bench settings wifi | 4–5 | Tizim ilovasi, oddiy tap |
| 2 | bench camera | 3 | Ilova ochish tezligi |
| 3 | bench youtube search | 6–8 | Klaviatura + matn kiritish |
| 4 | bench youtube scroll | 5 | Swipe aniqligi |
| 5 | bench telegram chat | 8–10 | Ro'yxatdan topish, chat ochish |
| 6 | bench chrome page | 10–12 | Sekin yuklanadigan sahifa (kutish muammosi) |
| 7 | bench notification | 3–4 | Panel tortish (swipe yuqoridan) |
| 8 | bench long hold | 4 | Uzoq bosish |
| 9 | bench maps route | 15–20 | O'rta uzunlik, bir nechta ekran |
| 10 | bench long chain | 40–50 | Uzun zanjir — asosiy muammo |

Har birini **10 marta** ishga tushiring. "Yo'q" bo'lsa, qaysi qadamda buzilganini yozing (bilsangiz).

## Natijalar jadvali

Har versiyada shu jadvalni to'ldiring (Hisobot ekranidan olinadi). Bu qator — keyingi bosqichlar uchun asosiy chiziq.

### Qurilmalar

| Kod | Telefon | Android | Izoh |
|---|---|---|---|
| D1 | | | Xiaomi / HyperOS |
| D2 | | | Samsung / OneUI |
| D3 | | | Pixel yoki toza Android |

### Ovoz

| Versiya | Qurilma | Sharoit | To'g'ri % | Noto'g'ri % | Eshitilmadi % | p50 ms | p90 ms |
|---|---|---|---|---|---|---|---|
| 2.9.4 | D1 | jim | | | | | |
| 2.9.4 | D1 | shovqin | | | | | |

### Makro (inson tekshiruvi)

| Versiya | Qurilma | Makro | Haqiqatan to'g'ri | Ilova "OK" | Yolg'on "OK" | Tez-tez buziladigan qadam |
|---|---|---|---|---|---|---|
| 2.9.4 | D1 | #10 long chain | /10 | /10 | | |

## Bosqich mezonlari (ROADMAP bilan bog'liq)

- **Bosqich 2 tayyor:** 10 etalon makroning kamida 9 tasi 20 ishga tushirishda 19+ marta to'g'ri; yolg'on "OK" = 0.
- **Bosqich 3 tayyor:** ovoz to'g'ri ≥ 95% (jim), ≥ 85% (shovqin), noto'g'ri trigger < 1%.
