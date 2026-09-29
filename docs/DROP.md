# Lyane Drop Protokolü (v1)

Lyane Drop, **yalnızca aynı Wi-Fi ağındaki** iki Lyane kurulumu arasında
uygulama içi veri aktarımı yapar. Hiçbir sunucu, bulut deposu veya üçüncü
taraf yoktur.

## Keşif

- Sunucu, NSD/NsdManager üzerinde `_lyane._tcp.` servisi yayınlar.
  Servis adı = kullanıcının ayarladığı cihaz adı.
- İstemci aynı servisi tarar; bulduğu her sunucu için ad + IP + port listesi
  ekranda görünür.
- Çoklu yayın kilitleri (`WifiManager.MulticastLock`) keşif boyunca tutulur.

## Eşleşme

```
İstemci                          Sunucu
   │  GET /info                     │
   │ ◄──── {app:"lyane", api:1,     │
   │        name, requiresPin:true} │
   │                                │  (PIN ekranda gösterilir: 6 hane,
   │                                │   10 dk geçerli, 5 deneme hakkı)
   │  POST /pair  {"pin":"123456"}  │
   │ ◄──── {token, name}            │  (token: 30 dk geçerli)
```

Sonraki tüm istekler `?token=…` sorgu parametresini taşır. Sunucu, token'ı
yalnızca IP eşleşmesiyle kabul eder.

## Uç noktalar

| Uç nokta | Açıklama |
| --- | --- |
| `GET /api/list` | Paylaşılabilir ögeler (aşağıya bakın) |
| `GET /api/item/<id>` | Öge verisi. `Range` başlığı desteklenir (parçalı indirme, duraklat/devam) |
| `GET /api/ping` | Bağlantı sağlığı (keşif doğrulama) |
| `POST /api/push?uploadId=&seq=&last=&kind=&name=` | İstemciden sunucuya yükleme. Gövde: ham 4 MB'a kadar parça |

## Öge kimlikleri

| Kimlik | Tür | İçerik |
| --- | --- | --- |
| `model:<id>` | model | Model dizininin ZIP'i (`lyane.json` dahil) |
| `plugin:<id>` | plugin | `.lyplugin` paketi |
| `audio:<libId>` | audio | Kütüphanedeki WAV |
| `transcript:<libId>` | transcript | Transkript ögesi (metin + bölümler) |
| `catalog:<name>` | catalog | Kullanıcı katalog JSON'u |

`GET /api/list` yanıtı:

```json
{ "items": [ { "id": "model:kokoro", "kind": "model", "name": "Kokoro 82M",
               "sizeBytes": 103248205, "detail": "tts" } ] }
```

## Aktarım akışı

**Model alımı (istemci):**
1. `model:<id>` indirilir (Range ile parça parça; ilerleme çubuğu).
2. ZIP açılır, `lyane.json` okunur → `ModelStore.register(spec)`.
3. Model hemen kullanılabilir: **internet gerekmez**, katalog eşleşmesi gerekmez.

**Push (istemci → sunucu):**
`kind` değerine göre sunucu tarafında otomatik işlenir:
- `model` → model dizini olarak kurulur (`lyane.json` aranır)
- `plugin` → eklenti olarak kurulur
- `audio`/`transcript` → kütüphaneye eklenir
- `catalog` → kullanıcı katalogları arasına kaydedilir

## Güvenlik ve sınırlar

- Aktarım Wi-Fi üzerinden şifreli **değildir** (NSD + HTTP). Wi-Fi
  yalnızca iki tarafın da güvendiği ağ olarak kabul edilir; PIN eşleşmesi
  yanlış cihaza bağlanmayı engeller.
- Kimlik doğrulama: 6 haneli PIN (10 dk) + 30 dk'lık token; 5 hatalı
  denemede sunucu o istemciyi 1 dk reddeder.
- Dosya boyutu sınırı yoktur; parçalar 4 MB'tır.
- Lyane'de veri çıkışı **yalnızca** bu kod yolundan geçer: Drop dışında
  hiçbir bileşen ağ üzerinden içerik göndermez.
