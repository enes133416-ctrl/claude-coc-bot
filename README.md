# CoC Bot (Android, derleme GitHub Actions'ta)

## 1) Depoya yükleme ve APK indirme
**Yol A (önerilen, telefondan kolay):**
1. GitHub'da yeni boş depo aç.
2. Add file → Upload files → `proje.zip` dosyasını yükle, Commit.
3. Add file → Create new file → ad kutusuna `.github/workflows/build.yml` yaz, bu dosyanın içeriğini yapıştır, Commit.
   (Workflow `proje.zip`'i kendisi açıp derler.)

**Yol B (bilgisayardan):** Klasörün tamamını (alt klasörlerle) depoya yükle.

Sonra: Actions sekmesi → "Build APK" (push'ta otomatik başlar; başlamazsa Run workflow) → yeşil tik olunca çalışmaya tıkla → en altta **Artifacts → app-debug.apk** indir (zip içinden çıkar).

## 2) Telefona kurma ve erişilebilirlik
1. `app-debug.apk`'yı aç, "Bilinmeyen kaynaklardan yükleme"ye izin ver, kur.
2. Android 13+ için: Ayarlar → Uygulamalar → CoC Bot → ⋮ → **Kısıtlanmış ayarlara izin ver**.
3. CoC Bot uygulamasını aç → "Erişilebilirlik Ayarları" → İndirilen uygulamalar → **CoC Bot** → aç → İzin ver.

## 3) Overlay ve başlatma
1. Overlay için ayrı izin gerekmez; servis açılınca ekranda yeşil **▶ Başlat** butonu ve yanında tek satır log çıkar.
2. Clash of Clans'ı yatay açıp köy ekranında bekle, yüzen butona bas. Durdurmak için kırmızı **■ Durdur**.
3. Butonu sürükleyerek istediğin yere taşıyabilirsin.

Not: Android, `takeScreenshot` çağrılarına ~0,3 sn aralık koyar; bot bu sınırın izin verdiği hızda döner.
