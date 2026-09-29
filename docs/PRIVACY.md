# Lyane Gizlilik Bildirimi

**Kısa sürüm: Verileriniz cihazınızdan çıkmaz.**

## Ne toplanmaz

- Mikrofon sesi **hiçbir zaman** cihaz dışına gönderilmez.
- Seslendirdiğiniz metinler, transkriptler ve dosyalar yüklenmez.
- Analitik, telemetri, çökme raporu, reklam tanımlayıcısı **yoktur**.
- Bulut yedeklemesi **yoktur** (modeller ve kütüphane Android yedeklemesinin
  dışında tutulur: `backup_rules.xml`).

## Ne zaman internet kullanılır

| Etkinlik | Bağlanılan adres | Zorunlu mu? |
| --- | --- | --- |
| Model indirme (mağaza) | `github.com/k2-fsa/sherpa-onnx/releases` (+ katalogdaki URL'ler) | Evet, yalnızca indirme anında |
| Eklenti kurulumu (elle) | — | Hayır (yerel dosya) |
| TTS / STT işleme | — | Hayır, tamamen cihazda |

Uçak modunda: indirme dışındaki tüm özellikler (sentez, tanıma, dosya
çeviriyazısı, kıyaslama, eklentiler, kütüphane) çalışır.

## İzinler ve gerekçeleri

| İzin | Gerekçe |
| --- | --- |
| `INTERNET` | Model dosyalarının açık kaynak dağıtım noktalarından indirilmesi |
| `RECORD_AUDIO` | Dinle ekranında canlı tanıma (yalnızca siz başlattığınızda) |
| `POST_NOTIFICATIONS` | İndirme/seslendirme/dinleme servis bildirimleri |
| `FOREGROUND_SERVICE` + `_MICROPHONE`/`_MEDIA_PLAYBACK`/`_DATA_SYNC` | Ekran kapalıyken dinleme/seslendirme; indirmenin sürdürülmesi |
| `CHANGE_WIFI_MULTICAST_STATE` | Lyane Drop keşfi (NSD) |

Mikrofon yalnızca "Dinle" açıkken çalışır; ekran kapanınca ya da durdurunca
kaynak derhal serbest bırakılır. Kayıt yalnızca "Otomatik kaydet" açıksa ve
sadece cihaz içindeki kütüphaneye yapılır.

## Cihazlar arası aktarım

Lyane Drop **yalnızca** aynı Wi-Fi ağındaki cihazlar arasında, iki tarafın
da açık isteğiyle çalışır:

- Eşleşme 6 haneli PIN ile yapılır; PIN karşı cihazın ekranında görünür.
- Aktarım yerel IP'ler üzerinden, doğrudan cihazdan cihaza gerçekleşir;
  arada sunucu yoktur.
- Aktarım Wi-Fi şifrelemesi dışında ek şifreleme içermez; güvenli
  ağlarda kullanın.
- İstemci, göndermek istediği her ögeyi açıkça seçer; otomatik yükleme
  yapılmaz.

## Depolama ve silme

- Tüm veri `filesDir` altındadır (modeller, kütüphane, eklentiler,
  tercihler).
- Modeller → sil, Kütüphane → çöp kutusu, Eklentiler → kaldır ile tek tek;
  Ayarlar → Depolama ile tüm modeller.
- Uygulamayı kaldırmak tüm veriyi kaldırır.

## Eklentiler

Eklentiler ağ erişimi alamaz; dosya erişimi kendi dizinleriyle sınırlıdır.
Her izin (metne erişim, depolama, bildirim) açık kullanıcı onayı ister.
Üçüncü taraf eklenti kurmadan önce geliştiricisine güvenin.
