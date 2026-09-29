/**
 * Lyane örnek eklentisi: Türkçe Sayı Okuyucu
 * ------------------------------------------------
 * TTS'e gönderilmeden önce metindeki sayıları Türkçe okunuşlarına çevirir.
 * Örnek: "1.250 TL" → "bin iki yüz elli TL"
 *
 * Lyane eklenti API'si (v1):
 *   Lyane.log(msg)            — eklenti günlüğüne yazar
 *   Lyane.toast(msg)          — kısa bildirim (ui izni gerekir)
 *   Lyane.storageGet/Set(k,v) — kalıcı anahtar/değer (storage izni)
 *   Lyane.readFile/writeFile  — eklenti dosya alanı (files izni)
 *   Lyane.listModels()        — kurulu modeller (models izni)
 *
 * Kancalar:
 *   onTtsPreprocess(text, modelId) → dönüştürülmüş metin
 *   onAsrPostprocess(text, modelId) → dönüştürülmüş metin
 *   onToolAction(toolId, actionId, state) → güncellenecek alanlar
 */

var BIRLER = ["", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz"];
var ONLAR = ["", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan"];
var GRUPLAR = ["", "bin", "milyon", "milyar", "trilyon"];

function uclu(n) {
    var parca = [];
    var y = Math.floor(n / 100);
    var o = Math.floor((n % 100) / 10);
    var b = n % 10;
    if (y > 0) parca.push((y > 1 ? BIRLER[y] + " " : "") + "yüz");
    if (o > 0) parca.push(ONLAR[o]);
    if (b > 0) parca.push(BIRLER[b]);
    return parca.join(" ");
}

function sayiToKelimeler(n) {
    n = Math.floor(Math.abs(n));
    if (n === 0) return "sıfır";
    if (n >= 1e15) return String(n); // çok büyük: olduğu gibi bırak
    var gruplar = [];
    var i = 0;
    while (n > 0) {
        var u = n % 1000;
        if (u > 0) {
            var kelime = uclu(u);
            // "bir bin" değil, "bin"
            if (i === 1 && u === 1) kelime = "bin";
            else if (i > 0) kelime = (kelime ? kelime + " " : "") + GRUPLAR[i];
            gruplar.push(kelime);
        }
        n = Math.floor(n / 1000);
        i++;
    }
    return gruplar.reverse().join(" ").replace(/\s+/g, " ").trim();
}

// Ondalıkları da oku: 1,5 → "bir buçuk" tarzı ("virgül" ile güvenli çözüm)
function ondalikToKelimeler(s) {
    var bolum = s.split(/[.,]/);
    var tam = bolum[0] || "0";
    var ond = bolum[1] || "";
    var sonuc = sayiToKelimeler(parseInt(tam, 10));
    if (ond.length > 0) {
        var hane = [];
        for (var j = 0; j < ond.length && j < 4; j++) {
            hane.push(BIRLER[parseInt(ond.charAt(j), 10)]);
        }
        sonuc += " virgül " + hane.join(" ");
    }
    return sonuc;
}

function cevirMetin(text) {
    if (!text) return text;
    // 12:30 gibi saatleri koru, sayıları çevir
    var sonuc = text.replace(/(\d{1,15})([.,]\d+)?/g, function (tum, tam, ond) {
        if (ond) return ondalikToKelimeler(tam + ond);
        // binlik ayracı olabilir: "1.250" — nokta 3 haneliyse ayraçtır
        return sayiToKelimeler(parseInt(tam, 10));
    });
    return sonuc;
}

// Binlik ayracı düzeltmesi: "1.250" öbeğini bütün olarak yakala
function cevirBinlikli(text) {
    var sonuc = text.replace(/\b\d{1,3}(\.\d{3})+(\,\d+)?\b/g, function (m) {
        var temiz = m.replace(/\./g, "");
        if (m.indexOf(",") >= 0) return ondalikToKelimeler(temiz.replace(",", "."));
        return sayiToKelimeler(parseInt(temiz, 10));
    });
    return cevirMetin(sonuc);
}

// ── Kancalar ────────────────────────────────────────────────────────

function onTtsPreprocess(text, modelId) {
    var sonuc = cevirBinlikli(text);
    if (sonuc !== text) {
        Lyane.log("Sayılar çevrildi: " + text.length + " → " + sonuc.length + " karakter");
    }
    return sonuc;
}

function onToolAction(toolId, actionId, state) {
    if (toolId === "sayi-cevirici" && actionId === "convert") {
        var input = state["input"] || "";
        var output = cevirBinlikli(input);
        return { "output": output };
    }
    return null;
}
