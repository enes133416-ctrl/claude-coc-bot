# CoC Bot (Android, derleme GitHub Actions'ta)

Erişilebilirlik servisi ile Clash of Clans ekranını okuyup dokunuş gönderen bir bot.
Sürüm **2.3** — saldırı öncesi otomatik zoom-out, 30. sn'de ekranı aşağı kaydırıp alt köşelere asker bırakma, kartları otomatik sınıflandırma (kahraman/büyü), kalan askerleri tek tek köşelere bırakma, 85. saniyede otomatik teslim.

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
Ganimet (altın + iksir) okunur. Toplam **1.6M**'nin altındaysa **İleri** ile yeni köy aranır.
Üstündeyse saldırı planı çalışır.

### Saldırı planı
0. **Uzaklaştırma:** ilk asker bırakılmadan önce ekran **iki parmakla sıkıştırılarak** (pinch)
   olabildiğince uzaklaştırılır. Böylece büyük köyler de ekrana sığar, kırmızı çizgi ve bırakma
   alanı görülür. Alan yine bulunamazsa ekran bir kez daha uzaklaştırılıp yeniden taranır.
1. **Askerler:** sol alttaki **x40** kartına basılır ve **10'ar asker** bırakılır.
   Sonra **10 sn** beklenir, yine 10 asker… toplam **4 grup = 40 asker**.
   Her asker, kullanılan noktaların **en uzağına** bırakılır (min. 150 px, kırmızı çizginin dışında,
   bina/ağaç/su üstüne asla). Yani askerler birbirine yakın düşmez.
1b. **Kalan askerler:** 4 grup bitince (≈40. sn) x40 kartının **sayacı okunur**; asker kalmışsa
   (ör. 15) hepsi **tek tek, sırayla farklı köşelere** bırakılır. Köşe hedefleri kırmızı çizgi
   kutusuna göre **dinamik** hesaplanır (4 kenar + 4 çapraz) — ekran kaydırılsa bile doğru yeri gösterir.
2. **Kart sınıflandırma:** savaş başında her kartın sayacı okunur: **"xN" sayacı olan → büyü,
   olmayan → kahraman.** Böylece ordu dizilimi değişse de doğru kartlar kullanılır.
3. **Alt köşeler:** **30. sn'de ekran aşağı kaydırılır** (alt köşeler arayüzün arkasından görünür),
   bırakma alanı yeniden taranır; sonraki asker ve kahramanlar da **alt bölgeye** bırakılır.
4. **Kahramanlar:** güvenli **köşelere** bırakılır (sağ üst yerine — köşeler her zaman düz çimdir);
   savaş sonunda bir kez daha denenir.
5. **Büyüler:** üssün **ortasına (kral bölgesi)** atılır; atış sayısı karttaki `xN` etiketinden okunur.
6. **Yetenekler:** son kahraman bırakıldıktan **10 sn** sonra kahraman kartlarına tekrar basılır.
7. **Kalan kartlar:** x40 + kahramanlar köşelere, büyüler üs merkezine boşaltılır.

### Savaştan çıkış
**70-85 sn arası:** kartlarda kalan son askerler (x40 ve ikinci asker kartı) **tek tek,
sırayla farklı köşelere** bırakılır. Sayaç okunamadıysa yine de denenir (boş kartla dokunmak
zararsızdır).

Savaş başlangıcından **85 sn** sonra (plan erken bitse de bekleme fazında sayılır):
1. Sol alttaki kırmızı **Teslim Ol** butonuna basılır,
2. çıkan "Teslim olalım mı?" ekranında yeşil **Tamam**'a basılır,
3. savaş bitiş ekranında **Eve Dön**'e basılır → köye dönülür ve tur baştan başlar.

Bir sahne tanınamazsa **hiçbir yere dokunulmaz** (güvenlik). Plan 95 sn'yi aşarsa yine teslim olunur.

## 5) Not

Android, `takeScreenshot` çağrılarına ~0,3 sn aralık koyar; bot bu sınırın izin verdiği hızda döner.
