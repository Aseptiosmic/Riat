# Model Kataloğu Biçimi

Katalog, Lyane'nin mağaza ekranını besleyen JSON dosyasıdır. Gömülü katalog
`app/src/main/assets/catalog/models.json` içindedir; kullanıcı kendi
kataloğunu **Modeller → İçe Aktar** ile ekleyebilir (yalnızca okunur).

## Üst düzey şema

```json
{ "version": 1, "updatedAt": "2026-09-29", "models": [ … ] }
```

## Model girdisi

```json
{
  "id": "whisper-tiny",                    // benzersiz kimlik
  "name": "Whisper Tiny (Çok Dilli)",      // mağazada görünen ad
  "task": "asr",                           // tts | asr | asr_live | vad
  "engine": "whisper",                     // motor (aşağıya bakın)
  "languages": ["tr", "en"],               // dil etiketleri (bilgi amaçlı)
  "sizeBytes": 116204861,                  // indirme boyutu (doğrulamada kullanılır)
  "license": "MIT",                        // SPDX etiketi veya kısa ad
  "author": "OpenAI / k2-fsa",
  "description": "Türkçe dahil 99 dilde çeviriyazı…",
  "homeUrl": "https://…",                  // (isteğe bağlı) proje sayfası
  "url": "https://github.com/…/sherpa-onnx-whisper-tiny.tar.bz2",
  "format": "tar.bz2",                     // tar.bz2 | tar.gz | zip | file
  "quality": 3,                            // 0-5 kalite puanı (mağaza noktaları)
  "speed": 4,                              // 0-5 hız puanı
  "recommended": true,                     // önerilen etiketi
  "speakers": ["af", "af_bella"],          // TTS konuşmacı adları (sid sırası)
  "minRamMb": 1536,                        // önerilen en az RAM
  "options": {                             // motor seçenekleri
    "language": "auto", "task": "transcribe", "quantized": "true"
  },
  "paths": { … yol desenleri … }
}
```

## Motorlar ve kritik yollar

| Engine | Task | Zorunlu yol anahtarları |
| --- | --- | --- |
| `vits` | tts | `model`, `tokens` (+ `dataDir`, `lexicon` Piper'da genelde gerekmez) |
| `kokoro` | tts | `model`, `voices`, `tokens`, `dataDir` |
| `kitten` | tts | `model`, `voices`, `tokens`, `dataDir` |
| `matcha` | tts | `acousticModel`, `vocoder`, `tokens`, `dataDir` |
| `supertonic` | tts | `durationPredictor`, `textEncoder`, `vectorEstimator`, `vocoder`, `ttsJson`, `unicodeIndexer` |
| `whisper` | asr | `encoder`, `decoder`, `tokens` |
| `moonshine` | asr | `encoder`, `mergedDecoder` (v2) veya `uncachedDecoder`+`cachedDecoder` (v1), `tokens` |
| `sensevoice` | asr | `model`, `tokens` |
| `transducer` | asr_live | `encoder`, `decoder`, `joiner`, `tokens` |
| `silero` | vad | `model` |

## Yol desenleri

Her anahtar bir **aday listesidir**; `PathResolver` sırayla dener:

- Birebir ad: `"tokens.txt"`
- Glob: `"*.onnx"`, `"model-steps*.onnx"`
- Arşiv kök dizini farkı otomatik tolere edilir (arşiv içinde tek üst klasör
  olabilir).

**int8 tercihi:** liste `model.int8.onnx` gibi bir kuantalanmış adla
başlıyorsa ve dosya varsa o seçilir (aynı arşivde fp32 + int8 birlikte
gelebiliyor: örn. Whisper).

## options anahtarları

| Anahtar | Motorlar | Anlam |
| --- | --- | --- |
| `language` | whisper (`auto`/`tr`/`en`/…), sensevoice (`auto`/`zh`/…) | tanıma dili |
| `task` | whisper | `transcribe` \| `translate` |
| `quantized` | tümü | int8 dosyaların tercih edilmesi (bilgi amaçlı; gerçek seçim yol desenleriyle yapılır) |
| `useItn` | sensevoice | inverse text normalization |

## Kullanıcı kataloğu kuralları

- `id` çakışırsa **kullanıcı kataloğu kazanır** (aynı modelin farklı
  sürümünü sunmak için).
- URL'lerin `https` olması zorunludur.
- `sizeBytes` indirme doğrulamasında kullanılır; sunucu Content-Length
  uyuşmazsa uyarı üretilir.

## Örnek: en küçük Türkçe kurulum

```json
[
  { "id": "tr-piper-fahrettin", "task": "tts", "engine": "vits",
    "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-tr_TR-fahrettin-medium-int8.tar.bz2",
    "format": "tar.bz2", "sizeBytes": 21118817,
    "paths": { "model": ["tr_TR-fahrettin-medium.int8.onnx"], "tokens": ["tokens.txt"], "dataDir": ["espeak-ng-data"] } }
]
```

## lyane.json (arşiv içi tanım)

Kendi model arşivinizi hazırlarken arşivin köküne `lyane.json` koyun; Lyane
arşivi alınca tanımı sizden okur:

```json
{ "spec": { …yukarıdaki model girdisi ile aynı… } }
```

`paths` yine aday listesi ister; `url`/`format` gerekmez (dosya zaten eldedir).
