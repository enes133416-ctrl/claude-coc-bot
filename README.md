# CoC Bot (Android, derleme GitHub Actions'ta)

Erişilebilirlik servisi ile Clash of Clans ekranını okuyup dokunuş gönderen bir bot.
Sürüm **3.0** — 50 tazı + kral saldırısı, uzaklaştırma, doğal savaş sonu ve eve dönme otomatiktir.

## 1) Depoya yükleme ve APK indirme

Bu deponun kökünde hazır bir Gradle projesi var (`app/`, `build.gradle.kts`, `gradle/`).

1. GitHub'da depoyu aç → **Add file → Upload files** ile `app/`, `build.gradle.kts`, `settings.gradle.kts`,
   `gradle.properties`, `gradle/` klasörünü yükle (veya depoyu olduğu gibi kullan), Commit.
2. Actions sekmesi → **Build APK** (push'ta otomatik başlar; başlamazsa Run workflow) → yeşil tik olunca
   çalışmaya tıkla → en altta **Artifacts → app-debug.apk** indir (zip içinden çıkar).

> `proje.zip` yalnızca depoda hiç kaynak yoksa açılır. Kaynaklar kökte olduğu için normalde kullanılmaz.

## 2) Telefona kurma ve erişilebilirlik

1. `app-debug.apk`'yı aç, "Bilinmeyen kaynaklardan yükleme"ye izin ver, kur.
2. Android 13+ için: Ayarlar → Uygulamalar → CoC Bot → ⋮ → **Kısıtlanmış ayarlara izin ver**.
3. CoC Bot uygulamasını aç → "Erişilebilirlik Ayarları" → İndirilen uygulamalar → **CoC Bot** → aç → İzin ver.

## 3) Overlay ve başlatma

1. Overlay için ayrı izin gerekmez; servis açılınca ekranda yeşil **▶ Başlat** butonu ve yanında tek satır log çıkar.
2. Clash of Clans'ı yatay açıp köy ekranında bekle, yüzen butona bas. Durdurmak için kırmızı **■ Durdur**.
3. Butonu sürükleyerek istediğin yere taşıyabilirsin.

## 4) Bot ne yapıyor?

**Tur:** Köy → Savaş → Eşleşme Bul → Ordu → Saldır! → rakip önizlemesi → savaş → sonuç → köy

### Keşif
Ganimet (altın + iksir) okunur. Toplam **1.000.000**'u geçmiyorsa **İleri** ile yeni köy aranır.
Geçiyorsa saldırı planı çalışır.

### Saldırı planı (v3)
1. **Uzaklaştır:** Ekran iki parmakla kıstırılıp **uzaklaştırılır** (iki kez). Harita bundan
   **sonra** taranır; böylece görüş alanı genişler ve bırakma noktaları bu görünüme göre seçilir.
2. **Tazılar (x50):** Karta bir kez basılır; ardından **15 saniye boyunca durmadan**, her seferinde
   **aynı anda 3 ayrı geçerli noktaya 3 tazı** bırakılır (tek jestte üç parmak; cihaz çoklu
   dokunuşu kabul etmezse dokunuşlar ~45 ms arayla tek tek gider).
   - Noktalar kırmızı çizginin dışında, yasak arayüz bölgelerinden uzak, bina/ağaç/su üstünde değil.
   - Geçersiz yere denk gelen dokunuş önemli değildir: 15 sn boyunca basılmaya devam edilir,
     böylece **kartta tazı kalmaz**.
   - Kart sayacı okunmaz/okunması beklenmez.
3. **Kral:** 15 sn dolunca kral kartına basılır ve **sağ üst / ön cepheye** bırakılır.
4. **Kalan tazı:** Kartta hâlâ tazı görünüyorsa kısa bir boşaltma turu yapılır (en fazla 6 tur).
5. **Teslim olunmaz.** Savaş kendiliğinden bitene kadar beklenir (CoC saldırı süresi 3 dk).
   Sonuç ekranında **Eve Dön**'e basılır → köye dönülür ve tur baştan başlar.
   Savaş 3,5 dakikada hâlâ bitmediyse bot **durmaz, teslim de olmaz**: uyarı verip durur.

Bir sahne tanınamazsa **hiçbir yere dokunulmaz** (güvenlik).

## 5) Zaman çizelgesi

| An | Olay |
|---|---|
| 0 sn | Ganimet > 1M → plan başlar |
| 0–1 sn | Ekran uzaklaştırılır (2 kıstırma) |
| ~1 sn | Tazı kartı seçilir; 15 sn'lik bırakma penceresi başlar |
| 1–16 sn | 3'er 3'er, aynı anda üç noktaya tazı bırakma |
| ~16 sn | Kral sağ üste bırakılır |
| 16–18 sn | (gerekirse) kalan tazıların boşaltılması |
| ~3 dk | Savaş doğal olarak biter → **Eve Dön** → köy |
| 3,5 dk | Emniyet sınırı: teslim olmadan durur |

## 6) Not

Android, `takeScreenshot` çağrılarına ~0,3 sn aralık koyar; bot bu sınırın izin verdiği hızda döner.
Bırakma sırasında ise her kare beklemeden, art arda üçlü dokunuşlar gönderilir.
