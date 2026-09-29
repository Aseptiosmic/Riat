# Lyane Eklenti Sistemi

Lyane'nin eklenti mimarisi "son derece açık" olacak şekilde tasarlandı: küçük
bir ön işlemci yazmaktan, kendi araç arayüzünüzü eklemeye kadar her şey
mümkün. **Ağ erişimi yoktur** — eklentiler tamamen cihaz üzerinde çalışır.

## Paket biçimi

`.lyplugin` = aşağıdaki yapıda bir ZIP:

```
plugin.json          (zorunlu) manifest
main.js              (script eklentisi) ES5/ES2015 JavaScript — Rhino'da çalışır
plugin.dex           (dex eklentisi) LyanePlugin arayüzünü uygulayan sınıf
```

## plugin.json

```json
{
  "id": "com.sen.eklenti",          // benzersiz, ters alan adı önerilir
  "name": "Eklenti Adı",
  "version": "1.0.0",
  "author": "Adınız",
  "description": "Ne yaptığına dair kısa açıklama",
  "api": 1,                          // Lyane eklenti API sürümü
  "type": "script",                  // "script" | "dex"
  "entry": "main.js",                // script ise js dosyası; dex'te "plugin.dex"
  "hooks": ["tts.preprocess", "asr.postprocess"],
  "permissions": ["tts.text"],
  "tools": [
    {
      "id": "arac-id",
      "title": "Araç Başlığı",
      "description": "Araç ne yapar?",
      "icon": "calculate",            // Material icon adı (isteğe bağlı)
      "ui": [ … bildirimsel arayüz … ]
    }
  ],
  "help": "Kullanıcıya gösterilecek uzun yardım metni"
}
```

## İzinler

| İzin | Ne sağlar |
| --- | --- |
| `tts.text` | `tts.preprocess` kancasında metni okuma |
| `asr.text` | `asr.postprocess` kancasında metni okuma |
| `storage` | `Lyane.storageGet/storageSet` (kalıcı anahtar/değer) |
| `files` | `Lyane.readFile/writeFile/listFiles` (yalnız eklenti dizini) |
| `ui` | `Lyane.toast` (kısa bildirim) |
| `models` | `Lyane.listModels()` (kurulu model listesi) |

İzinler kullanıcıya açıkça sorulur (Eklentiler → Ayrıntılar → İzinler) ve
onay `DataStore`'da tutulur. Onay verilmeden ilgili API çağrısı sessizce
başarısız olur ve günlüğe düşer.

## JS eklentisi API'si (`Lyane` kök nesnesi)

```js
Lyane.log("mesaj")                 // eklenti günlüğü (Ekranda görünür)
Lyane.toast("bildirim")            // ui izni
Lyane.storageGet("k")              // storage izni — String|null
Lyane.storageSet("k", "v")         // storage izni
Lyane.readFile("ad")               // files izni — String|null
Lyane.writeFile("ad", "içerik")    // files izni — boolean
Lyane.listModels()                 // models izni — ["id", …]
```

Rhino (ES5 + bazı ES6 özellikleri) üzerinde çalışır; `java.*` sınıflarına
erişim `ClassShutter` ile kapatılmıştır. Ağ erişimi yoktur.

## Kancalar

```js
// TTS üretime girmeden önce: dönüştürülmüş metni döndür
function onTtsPreprocess(text, modelId) { return text; }

// STT tanıma sonrası: dönüştürülmüş metni döndür
function onAsrPostprocess(text, modelId) { return text; }
```

Kancalar kısa sürmelidir (kullanıcı arayüzünü bekletirler). Yalnızca
karşılık gelen izne (`tts.text` / `asr.text`) sahip eklentiler çağrılır.

## Araçlar ve bildirimsel arayüz

`tools[].ui` bir arayüz şemasıdır; Lyane bunu otomatik render eder —
eklentide UI kodu yazmak gerekmez. Desteklenen öğeler:

```json
[
  { "type": "textfield",  "id": "girdi", "label": "Metin" },
  { "type": "textarea",   "id": "uzun",  "label": "Uzun metin", "multiline": true },
  { "type": "slider",     "id": "hiz",   "label": "Hız", "min": 1, "max": 10, "initial": "5" },
  { "type": "switch",     "id": "acik",  "label": "Etkin", "initial": "true" },
  { "type": "text",       "id": "bilgi", "label": "Salt okunur metin", "initial": "…" },
  { "type": "button",     "id": "calistir", "label": "Çalıştır", "action": "cevir" }
]
```

`button` tıklanınca `onToolAction` çağrılır; döndürülen nesne alanları
arayüzü günceller:

```js
function onToolAction(toolId, actionId, state) {
  // state = { "girdi": "…", "hiz": "5", … } (tüm değerler String)
  if (toolId === "arac-id" && actionId === "cevir") {
    return { "uzun": donustur(state.girdi) };   // id → yeni değer
  }
  return null;
}
```

## DEX eklentileri

Kotlin/Java ile derlenmiş `plugin.dex`:

```kotlin
class MyPlugin : LyanePlugin {
    override val manifestId = "com.sen.eklenti"
    override fun onTtsPreprocess(text: String, modelId: String, ctx: PluginContext): String = …
    override fun onAsrPostprocess(text: String, modelId: String, ctx: PluginContext): String = …
    override fun onToolAction(toolId: String, actionId: String, state: Map<String, String>,
                              ctx: PluginContext): Map<String, String>? = null
}
```

DEX, `DexClassLoader` ile eklentinin kendi dizinine yüklenir. `manifest.json`
aynıdır (`type: "dex"`, `entry: "plugin.dex"`).

## Kurulum

1. **Uygulama içinden**: Araçlar → Eklentiler → `.lyplugin kur` (dosya seçici)
2. **Lyane Drop ile**: karşı cihazdan `plugin:` ögesi alındığında otomatik kurulur
3. **Gömülü örnek**: "Örnek eklentiyi kur" düğmesi (`assets/plugins/sample-tr-number`)
4. **Geliştirme sırasında**: `sample-plugin/build.sh` ile paket üretin

## Güvenlik notları

- Eklentiler ağ erişemez; dosya erişimi kendi dizinleriyle sınırlıdır.
- Eklenti kodu günlük ve izin kararları dışında sistem durumuna dokunamaz.
- Kötü behave eden bir script en fazla kendi çağrısında zaman aşımına yol açar;
  motor oturumları izole tutulur.
