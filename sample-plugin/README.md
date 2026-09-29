# Örnek Lyane eklentisi: Türkçe Sayı Okuyucu

Seslendirmeden önce metindeki sayıları Türkçe kelimelere çevirir:

- "1.250 TL" → "bin iki yüz elli TL"
- "Saat 14:30" → "saat on dört otuz"
- "0.5" → "sıfır virgül beş"

## Kurulum

1. `./build.sh` → `turkce-sayi-okuyucu.lyplugin` üretilir
2. Telefonda: Lyane → Araçlar → Eklentiler → `.lyplugin kur`

Alternatif olarak uygulamanın içindeki "Örnek eklentiyi kur" düğmesi
aynı eklentiyi gömülü varlıktan kurar.

## Neler gösterir?

- `tts.preprocess` kancası (`onTtsPreprocess`)
- `tts.text` izni
- Bildirimsel araç arayüzü (`plugin.json` → `tools[].ui`)
- `onToolAction` ile alan güncelleme döndürme

API belgesi: `docs/PLUGINS.md`
