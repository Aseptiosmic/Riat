# Lyane 🎙️

**Lyane**, Android için %100 yerel çalışan bir ses motorudur: konuşma sentezi (TTS) ve konuşma tanıma (STT) modellerini cihazınızın işlemcisinde çalıştırır. Bulut yok, sunucu yok, telemetri yok.

> Bu depo (`Riat`), Lyane uygulamasının kaynak kodunu içerir.

---

## Neden Lyane?

| İhtiyaç | Lyane'nin cevabı |
| --- | --- |
| Model listesi | Uygulama içi **mağaza**: 17 özenle seçilmiş açık kaynak model; dil, boyut, kalite ve hız filtreleriyle |
| İndir ve kullan | Tek dokunuşla indir → otomatik doğrula → hemen kullan. Duraklat/devam destekli |
| Hız | Tek çalışma zamanı (**sherpa-onnx + ONNX Runtime**), int8 kuantalanmış modeller otomatik tercih edilir, cümle cümle **akışlı üretim** (ilk cümle bitmeden çalmaya başlar), cihaz profiline göre iplik ayarı |
| Kalite | **Kokoro-82M** (açık kaynakların en doğal TTS'i), **Whisper**, **Moonshine**, **SenseVoice**, **Piper** |
| Detaylı araçlar | Dosya çeviriyazı (SRT dahil), canlı dinleme, kıyaslama (RTF), sıcak kelimeler, ses kütüphanesi |
| Eklenti açıklığı | JS **ve** DEX eklentileri, bildirimsel araç arayüzleri, TTS/STT kancaları — [docs/PLUGINS.md](docs/PLUGINS.md) |
| Yerellik | Her işlem cihazda; internet yalnızca **model indirmede** kullanılır |
| Cihazlar arası aktarım | **Yalnızca** Lyane Drop: aynı Wi-Fi'daki iki cihaz, uygulama içinden, PIN'li eşleşmeyle. Başka hiçbir yol yok |

## Araştırma özeti: neden bu modeller?

Lyane'nin model seçimi, güncel karşılaştırmalara ve Android'de kanıtlanmış çalışma zamanına dayanır:

- **sherpa-onnx (k2-fsa)** — Apache-2.0. TTS ve STT'yi tek bir ONNX Runtime tabanlı pakette, Android (JNI) üzerinde çalıştıran olgun proje. Kokoro, Piper/VITS, Matcha, Kitten, Supertonic (TTS) ve Whisper, Moonshine, SenseVoice, Zipformer (STT) motorlarını destekler. Lyane bu tek çalışma zamanını kullanır: tek native kütüphane, tek optimizasyon hattı.
- **Kokoro-82M** (Apache-2.0) — 82M parametreyle açık kaynaklar arasında en doğal İngilizce TTS; CPU'da gerçek zamanlı çalışır. int8 sürümü (~103 MB) telefonda akıcıdır.
- **Piper** (MIT) — 20+ dilde hafif VITS sesleri. Türkçe için `tr_TR` (Fahrettin/DFKI/Fettah) sesleri yalnızca **21 MB** (int8).
- **Whisper** (MIT) — 99 dil (Türkçe dahil) çeviriyazının en sağlam açık kaynak seçeneği; tiny/base/small boyutları telefonda çalışır.
- **Moonshine** (MIT) — kenar cihazlar için tasarlanmış yeni nesil STT; tiny sürümü (30 MB) Whisper tiny'den küçük ve daha doğru.
- **SenseVoice** (Apache-2.0) — Çince/İngilizce/Japonca/Korece/Kantonca için çok hızlı tanıma.
- **Zipformer 20M akış** (Apache-2.0) — gerçek zamanlı, uç nokta algılamalı canlı tanıma.
- **Silero VAD** (MIT) — konuşma aktivitesi algılama (0.6 MB).

Katalogda sunulan her modelin **lisansı, kaynağı ve boyutu** mağaza kartında görünür; indirme adresleri resmî açık kaynak dağıtım noktalarıdır (GitHub Releases).

## Ekranlar

- **Ana Sayfa** — durum, hızlı erişim, depolama
- **Konuş** — metinden sese: model/ses seçimi (Kokoro'nun 100+ konuşmacısı), hız, akışlı oynatma, WAV kaydı
- **Dinle** — canlı çeviriyazı: akış modellerinde kelime kelime kısmi sonuç, çevrimdışı modellerde VAD'lı bölümleme; Whisper dil seçici
- **Modeller** — Mağaza / Yüklü / İçe Aktar sekmeleri; ilerlemeli, duraklatılabilir indirmeler
- **Araçlar** — dosya çeviriyazı (VAD + SRT), kıyaslama, sıcak kelimeler, kütüphane, Drop, eklentiler + **eklenti araçları**
- **Ayarlar** — tema, motor iplikleri, yalnız-Wi-Fi indirme, tanılama günlüğü

## Mimari (özet)

```
┌─────────────────────────── Lyane app (Kotlin + Compose) ───────────────────────────┐
│ UI (Compose)  ←→  ViewModels  ←→  Controllers (Speak/Listen)  ←→  EngineManager    │
│                                    │                            (sherpa-onnx JNI) │
│ Model katmanı: ModelRegistry (katalog) · ModelStore (kurulu) · DownloadManager     │
│ Eklentiler: PluginManager → ScriptPluginHost (Rhino) / DexPlugin (DexClassLoader)  │
│ Lyane Drop: DropServer (NanoHTTPD) + NSD keşfi + DropClient — yalnız yerel ağ     │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Ayrıntı: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Derleme

Gereksinimler: Android Studio (Ladybug+) veya JDK 17 + Android SDK 35.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

**sherpa-onnx AAR** Gradle tarafından ilk derlemede k2-fsa GitHub Release'inden
otomatik indirilir (`settings.gradle.kts` içindeki ivy deposu). Çevrimdışı
derleme için AAR'ı elle indirip `app/libs/` içine koyun ve
`app/build.gradle.kts` içindeki `files(...)` satırını etkinleştirin:

```bash
# elle:
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar -O app/libs/sherpa-onnx-1.13.8.aar
```

Sürüm bilgisi: `gradle/libs.versions.toml` → `sherpaOnnx`.

## Model kataloğunu güncellemek

Katalog `app/src/main/assets/catalog/models.json` dosyasındadır. Kendi
modellerinizi eklemenin iki yolu:

1. **Uygulama içinde**: Modeller → İçe Aktar → Katalog JSON seç (biçim: [docs/MODELS.md](docs/MODELS.md))
2. **Kaynak kodda**: JSON'u düzenleyin. URL/boyut doğrulaması için:
   ```bash
   scripts/check-models.sh   # katalogdaki tüm URL'leri ve boyutları doğrular
   ```

## Eklenti geliştirme

`.lyplugin` = `plugin.json` + `main.js` (ya da `plugin.dex`) içeren ZIP.

```js
// main.js — Türkçe Sayı Okuyucu (gömülü örnek)
function onTtsPreprocess(text, modelId) {
    return cevirBinlikli(text);      // "1.250 TL" → "bin iki yüz elli TL"
}
function onToolAction(toolId, actionId, state) {
    if (toolId === "sayi-cevirici" && actionId === "convert")
        return { output: cevirBinlikli(state.input) };
}
```

Kancalar, izinler, DEX eklentileri ve araç arayüzü şeması:
**[docs/PLUGINS.md](docs/PLUGINS.md)** · hazır örnek: `sample-plugin/`

## Lyane Drop (cihazdan cihaza)

Aynı Wi-Fi ağına bağlı iki cihaz arasında, yalnızca uygulama içinden:

- Modeller (içinde `lyane.json` tanımıyla — karşı tarafta ek kurulumsuz çalışır)
- Eklentiler, ses kayıtları, transkriptler, model katalogları

Sunucu PIN gösterir → istemci PIN girer → liste → seç → aktar. Protokol:
[docs/DROP.md](docs/DROP.md). Bulut depolama **yoktur**; veri hiçbir
sunucudan geçmez.

## Gizlilik

- Mikrofon sesi ve metinler **cihazdan çıkmaz**; tüm çıkarım yerel.
- İnternet izni yalnızca model/eklenti indirmeleri için kullanılır (açık kaynak dağıtım noktalarına doğrudan bağlantı).
- Analitik, telemetri, reklam, bulut yedekleme **yok**.
- Cihazlar arası aktarım yalnız Lyane Drop ile; aktarım aynı Wi-Fi alt ağıyla sınırlıdır ve PIN ile eşleşme gerektirir.
- Ayrıntı: [docs/PRIVACY.md](docs/PRIVACY.md)

## Testler

```bash
./gradlew testDebugUnitTest
```

Katalog doğruluğu, yol çözümleyici, WAV gidiş-dönüş, metin bölücü ve gömülü
örnek eklentinin Rhino üzerinde gerçek çalıştırılması dahildir.

## Lisans

Uygulama kodu: **Apache License 2.0** (bkz. [LICENSE](LICENSE)). Bağımlılık ve
model lisansları: Ayarlar → Hakkında.

## Yol haritası fikirleri

- [ ] Anadil seçimine göre mağaza sıralaması
- [ ] Konuşmacı benzerliği (speaker embedding) tabanlı ses eşleme
- [ ] Bluetooth mikrofon desteği
- [ ] Wear OS companion
