# CoC Bot (Android, derleme GitHub Actions'ta)

Erişilebilirlik servisi ile Clash of Clans ekranını okuyup dokunuş gönderen bir bot.
Sürüm **2.0** — saldırı planı, teslim olma ve eve dönme adımları otomatiktir.

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
1. **Askerler:** sol alttaki **x40** kartına basılır ve **10'ar asker** bırakılır.
   Sonra **10 sn** beklenir, yine 10 asker… toplam **4 grup = 40 asker**.
   Her asker, kullanılan noktaların **en uzağına** bırakılır (min. 150 px, kırmızı çizginin dışında,
   bina/ağaç/su üstüne asla). Yani askerler birbirine yakın düşmez.
2. **Kahramanlar:** 3 kahraman kartı sırayla basılıp **sağ üst** bölgeye bırakılır.
3. **Büyüler:** herolardan sonra büyü kartları basılır ve **üssün ortasına (kral bölgesi)** atılır;
   atış sayısı karttaki `xN` etiketinden okunur.
4. **Yetenekler:** son kahraman bırakıldıktan **10 sn** sonra kahraman kartlarına tekrar basılır
   (oyunda kart tekrar basılınca özellik/güç açılır).
5. **Kalan kartlar:** x40 dahil bütün kartlar tekrar basılır; elde kalan askerler geçerli alanlara,
   büyüler üs merkezine boşaltılır. Kart sayacı hâlâ dolu görünüyorsa ikinci tur yapılır —
   yani geçersiz yere dokunulmuş olsa bile **hiç asker/büyü kartta kalmaz**.

### Savaştan çıkış
Savaş başlangıcından **70 sn** sonra (plan erken bitse de bekleme fazında sayılır):
1. Sol alttaki kırmızı **Teslim Ol** butonuna basılır,
2. çıkan "Teslim olalım mı?" ekranında yeşil **Tamam**'a basılır,
3. savaş bitiş ekranında **Eve Dön**'e basılır → köye dönülür ve tur baştan başlar.

Bir sahne tanınamazsa **hiçbir yere dokunulmaz** (güvenlik). Plan 95 sn'yi aşarsa yine teslim olunur.

## 5) Not

Android, `takeScreenshot` çağrılarına ~0,3 sn aralık koyar; bot bu sınırın izin verdiği hızda döner.
