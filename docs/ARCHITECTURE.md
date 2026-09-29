# Lyane Mimarisi

## Genel bakış

Lyane, tek bir native çalışma zamanı (sherpa-onnx) üzerinde tüm konuşma
işlemini yürüten, katmanlı ve tamamen yerel bir Android uygulamasıdır.

```
┌─────────────────────────────────── UI ────────────────────────────────────┐
│  Compose ekranları + AppNav + ViewModels (ui/, ui/screens/)               │
└───────────────┬────────────────────────────────────────────┬──────────────┘
                │ StateFlow                                  │
┌───────────────▼───────────────┐             ┌──────────────▼──────────────┐
│ SpeakController (speak/)      │             │ ListenController (listen/)  │
│ · metin bölme (TextSplitter)  │             │ · akış döngüsü (live)       │
│ · eklenti ön işlem            │             │ · VAD döngüsü (offline)     │
│ · cümle cümre akışlı üretim   │             │ · otomatik kayıt            │
│ · PCM oynatma + WAV kaydı     │             └──────────────┬──────────────┘
└───────────────┬───────────────┘                            │
                │                                            │
┌───────────────▼────────────────────────────────────────────▼──────────────┐
│ EngineManager (engine/)                                                   │
│  TtsSession · AsrSession.Live/Offline · VadSession                        │
│  konfigürasyon üretimi + oturum önbelleği + serbest bırakma               │
│  → sherpa-onnx JNI (OfflineTts, Online/OfflineRecognizer, Vad)            │
├───────────────────────────────────────────────────────────────────────────┤
│ AudioIO (PcmPlayer, MicSource, WavIo) · AudioDecoder (MediaCodec)         │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────── Model katmanı (model/) ──────────────────────────┐
│ ModelRegistry   gömülü + kullanıcı katalogları (assets/catalog, files/)   │
│ ModelDownloadManager  kuyruk, HTTP range-resume, arşiv açma, doğrulama    │
│ ModelStore      kurulu modeller + lyane.json + tarama                     │
│ PathResolver    aday desenleri + glob + int8 tercihi                      │
│ ModelAutoDetect tanınmamış arşivlerden spec türetme                       │
│ HttpDownloader · Archive (tar.bz2/tar.gz/zip) · ModelDownloadService      │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────── Diğer alt sistemler ─────────────────────────────┐
│ PluginManager → ScriptPluginHost (Rhino) · DexPlugin (DexClassLoader)     │
│ LibraryRepository  dosya tabanlı ses/transkript indeksi (files/library)   │
│ DropManager → DropServer (NanoHTTPD) · DropDiscovery (NSD) · DropClient   │
│ SettingsRepository  DataStore tercihleri                                   │
│ LyLog  bellek içi halka tampon (tanılama ekranı)                          │
└───────────────────────────────────────────────────────────────────────────┘
```

## Temel tasarım kararları

### 1. Tek çalışma zamanı: sherpa-onnx

TTS (Kokoro, Piper/VITS, Matcha, Kitten, Supertonic) ve STT (Whisper,
Moonshine, SenseVoice, Zipformer) için tek JNI kütüphanesi. Bu, APK boyutunu
tek ABI başına ~12 MB'ta tutar, kod yolunu ve optimizasyonu tekleştirir.

### 2. Akışlı üretim (algılanabilir hız)

`SpeakController` metni cümlelere böler (`TextSplitter`), ilk cümleyi hemen
üretmeye başlar ve `generateWithCallback` ile örnek geldikçe `AudioTrack`'e
yazar; üretim sürerken sonraki cümleler kuyruğa girer. Duraklatma:
`AudioTrack.pause() + flush()`; devam: kalan örnekler yeniden üretilir.

### 3. Hız optimizasyonları

- **int8 kuantalanmış dosyalar otomatik tercih edilir** (`PathResolver`):
  aynı arşivde hem fp32 hem int8 varsa int8 seçilir (Whisper, Kokoro, Zipformer).
- **İplik ayarı cihaz profiline göre**: `Device.ttsThreads()/asrThreads()`
  çekirdek sayısı ve RAM'e göre öneri üretir; kullanıcı Ayarlar'dan
  geçersiz kılabilir.
- **Oturum önbelleği**: aynı model yeniden yüklenmez; model değişince eski
  oturum serbest bırakılır.
- **Çevrimdışı STT'de VAD**: sessizlik üzerinde işlem harcanmaz, dosya
  transkripti bölümlerle (SRT) üretilir.

### 4. Model tanıma üç düzeyde

1. Arşivde `lyane.json` varsa → doğrudan spec.
2. Katalogdaki modelin yol desenleri eşleşirse → catalog spec.
3. `ModelAutoDetect`: dosya adı kalıplarından motor + tür çıkarımı
   (whisper/tiny-encoder.onnx → whisper; `*-tokens.txt` …).

Böylece kullanıcı **herhangi** sherpa-onnx uyumlu arşivi (URL ya da dosya)
uygulamaya taşıyabilir; elle yapılandırma gerekmez.

### 5. İzinli eklenti sanal alanı

- JS eklentileri Rhino'da, `ClassShutter` + beyaz listesiyle çalışır; ağ yok.
- Her izin (`tts.text`, `files`, `storage`, `ui`, `models`, `asr.text`) açık
  onay ister; onay DataStore'da tutulur.
- DEX eklentileri `DexClassLoader` + `LyanePlugin` arayüzüyle çalışır.
- Kancalar: `tts.preprocess` (üretim öncesi metin), `asr.postprocess`
  (tanıma sonrası metin). Araçlar: bildirimsel arayüz şeması.

### 6. Drop: yalnız yerel ağ

NSD (`_lyane._tcp.`) ile keşif; NanoHTTPD üzerinde PIN'li eşleşme, 30 dk'lık
token, 4 MB'lık parçalı yükleme. Model gönderimi `lyane.json` ile birlikte
olduğundan karşı taraf **internet olmadan** modeli alıp kullanabilir.
Kod yolunda tek istisna `checkConnectivity`'dir: cihaz aynı Wi-Fi'da mı?

### 7. Depolama düzeni

```
filesDir/
  models/<id>/…            model dosyaları + lyane.json
  library/index.json       kütüphane indeksi
  library/audio/<id>.wav   üretilen sesler
  plugins/<id>/…           kurulmuş eklentiler
  plugins_bin/<id>.lyplugin kurulum paketleri
  benchmarks.json          kıyaslama geçmişi (son 30)
  catalogs/<ad>.json       kullanıcı katalogları
```

Yedekleme kuralları (`backup_rules.xml`) modelleri ve kütüphaneyi Android
yedeklemesinin dışında tutar: kullanıcı verisi cihazdan asla otomatik
çıkmaz.

## İş parçacığı modeli

- UI: ana iş parçacığı; tüm durum `StateFlow`.
- Motor yükleme/çıkarım: `Dispatchers.Default` (EngineManager kilidiyle).
- İndirme: `Dispatchers.IO` + servis bildirimi.
- Mikrofon okuma: ayrı iş parçacığı (`MicSource.thread`).
- Eklenti çağrıları: çağıranın bağlamında (kısa, bellek içi).
- NanoHTTPD: kendi iş parçacığı havuzu.
