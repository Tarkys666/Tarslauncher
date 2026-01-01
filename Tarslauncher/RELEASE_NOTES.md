# TarsLauncher v1.0 - Sürüm Notları

**Sürüm:** 1.0 (Build 4)  
**Tarih:** Ağustos 2025

## ✨ Yenilikler
- Pro sürüm entegrasyonu tamamlandı
- Firestore bulut veritabanı desteği
- Google Play Billing sistemi
- Şeffaf launcher tasarımı
- Özelleştirilebilir grid sistemi (3x3 - 5x5)
- Icon pack desteği
- Batarya dostu mod

## 🔧 Teknik İyileştirmeler
- Android 14 (API 36) desteği
- Jetpack Compose UI
- Güvenli veri saklama
- Performans optimizasyonları

## 🚀 Ana Özellikler

### 🎨 Modern Tasarım ve Arayüz
- **Şeffaf Tasarım**: Sistem duvar kağıdını gösteren tamamen şeffaf arka plan
- **Jetpack Compose UI**: Modern Android UI framework'ü ile geliştirilmiş
- **Özelleştirilebilir Grid**: 3x3'ten 5x5'e kadar ayarlanabilir uygulama grid'i
- **Dinamik Klasör Sistemi**: Uygulamaları organize etmek için esnek klasör yapısı
- **Icon Pack Desteği**: Üçüncü parti icon pack'leri ile görsel özelleştirme

### ⚡ Performans ve Optimizasyon
- **Batarya Dostu Mod**: Animasyonları azaltarak pil ömrünü uzatan özel mod
- **Güvenli Uygulama Yönetimi**: Paket adı doğrulama ve güvenlik kontrolleri
- **Akıllı Uygulama Sıralama**: Kullanım sıklığına göre otomatik sıralama
- **Favori Sistem**: En çok kullanılan uygulamalar otomatik favori olur (10+ kullanım)

### 🔒 Güvenlik Özellikleri
- **Şifreli Tercihler**: Hassas verilerin güvenli saklanması
- **Paket Doğrulama**: Kötü amaçlı uygulamalara karşı koruma
- **Güvenli Broadcast Receiver**: Sistem olaylarının güvenli işlenmesi
- **Çift Dokunma Kilit**: Ekranı çift dokunuşla kilitleme özelliği

### 💰 Pro Sürüm ve Faturalandırma
- **Google Play Billing Entegrasyonu**: Tam entegre satın alma sistemi
- **Pro Sürüm Özellikleri**: Gelişmiş özellikler için premium abonelik
- **Güvenli Satın Alma**: Google Play Store üzerinden güvenli ödeme
- **Otomatik Lisans Doğrulama**: Mevcut satın almaların otomatik kontrolü

### 🎛️ Özelleştirme Seçenekleri
- **Buton Renk Modları**: Siyah ve normal renk seçenekleri
- **Uygulama Çekmece Boyutu**: 20dp-36dp arası icon boyutu ayarı
- **Saat Görünümü**: Ana ekranda saat gösterimi açma/kapama
- **TARS Başlık**: Launcher başlığını gizleme/gösterme seçeneği
- **Klasör Kilidi**: Klasörlerin yanlışlıkla değiştirilmesini engelleme

## 🔧 Teknik Detaylar

### Geliştirme Ortamı
- **Android Gradle Plugin**: 8.4.1
- **Kotlin**: 1.9.22
- **Compose BOM**: 2024.05.00
- **Target SDK**: 36 (Android 14)
- **Min SDK**: 26 (Android 8.0)

### Kullanılan Kütüphaneler
- **AndroidX Core KTX**: 1.13.1
- **Lifecycle Runtime KTX**: 2.8.0
- **Activity Compose**: 1.9.0
- **Material3**: En son sürüm
- **Google Play Billing**: 6.1.0
- **Google Sign-In**: 20.7.0
- **Coil Compose**: 2.5.0 (Görsel yükleme)
- **Gson**: 2.13.1 (JSON işleme)
- **Accompanist**: 0.32.0 (Drawable painter)

### Güvenlik ve İzinler
- **SET_WALLPAPER**: Duvar kağıdı değiştirme
- **INTERNET**: Google Play Billing ve Sign-In
- **ACCESS_NETWORK_STATE**: Ağ durumu kontrolü
- **QUERY_ALL_PACKAGES**: Yüklü uygulamaları listeleme
- **BIND_DEVICE_ADMIN**: Ekran kilitleme özelliği

### Build Konfigürasyonu
- **ProGuard**: Release build'lerde kod gizleme ve optimizasyon
- **Resource Shrinking**: Kullanılmayan kaynakların temizlenmesi
- **Security Build Fields**: Build güvenliği için özel alanlar
- **Signing Config**: Release imzalama konfigürasyonu

## 📱 Uygulama Bileşenleri

### Ana Aktivite (MainActivity)
- **Şeffaf Window**: Sistem duvar kağıdını göstermek için şeffaf pencere
- **Pro Mode Manager**: Premium özellikler yönetimi
- **Dinamik Uygulama Listesi**: Gerçek zamanlı uygulama güncellemeleri
- **Güvenli Broadcast Handling**: Sistem olaylarının güvenli işlenmesi

### ProModeManager
- **Billing Client**: Google Play Billing entegrasyonu
- **Google Sign-In**: Kullanıcı kimlik doğrulama
- **Purchase Handling**: Satın alma işlemlerinin yönetimi
- **License Validation**: Lisans doğrulama ve güvenlik

### IconPackLoader
- **XML Parsing**: Icon pack appfilter.xml dosyalarının işlenmesi
- **Resource Loading**: Üçüncü parti icon kaynaklarının yüklenmesi
- **Component Mapping**: Uygulama-icon eşleştirmesi

### DeviceAdminReceiver
- **Screen Lock**: Çift dokunuşla ekran kilitleme
- **Admin Permissions**: Cihaz yöneticisi izinleri

## 🛡️ Güvenlik Önlemleri

### Paket Güvenliği
- **Paket Adı Doğrulama**: Regex tabanlı paket adı kontrolü
- **Kara Liste**: Bilinen kötü amaçlı paketlerin engellenmesi
- **Sistem Paketi Tanıma**: Sistem uygulamalarının güvenli işlenmesi

### Veri Güvenliği
- **Şifreli SharedPreferences**: Hassas verilerin şifrelenmesi
- **Güvenli Intent Handling**: Intent'lerin güvenli işlenmesi
- **Rate Limiting**: Broadcast receiver'lar için hız sınırlaması

### Build Güvenliği
- **Security Build Fields**: Build zamanı güvenlik kontrolleri
- **Timestamp Validation**: Build zaman damgası doğrulama
- **Hash Generation**: Build hash'i ile bütünlük kontrolü

## 🎯 Kullanıcı Deneyimi

### Performans Optimizasyonları
- **Lazy Loading**: Uygulamaların gerektiğinde yüklenmesi
- **Memory Management**: Bellek kullanımının optimize edilmesi
- **Animation Scaling**: Batarya dostu mod için animasyon kontrolü
- **Efficient Recomposition**: Compose recomposition optimizasyonu

### Erişilebilirlik
- **Büyük Dokunma Alanları**: Kolay kullanım için geniş butonlar
- **Yüksek Kontrast**: Görsel erişilebilirlik için kontrast optimizasyonu
- **Basit Navigasyon**: Sezgisel kullanıcı arayüzü

## 📋 Sistem Gereksinimleri

### Minimum Gereksinimler
- **Android Sürümü**: 8.0 (API 26) ve üzeri
- **RAM**: 2GB minimum, 4GB önerilen
- **Depolama**: 50MB uygulama + 100MB cache alanı
- **İşlemci**: ARMv7 veya ARM64 mimarisi

### Önerilen Gereksinimler
- **Android Sürümü**: 10.0 (API 29) ve üzeri
- **RAM**: 4GB ve üzeri
- **Depolama**: 1GB boş alan
- **GPU**: Adreno 530, Mali-G71 MP8 veya eşdeğeri

## 🔄 Bilinen Sorunlar ve Çözümler

### Build Sorunları
- **Keystore Path**: Release build'lerde keystore yolu sorunu
  - **Çözüm**: Keystore dosyasının proje root'unda olması gerekiyor
  - **Konum**: `C:\Users\emiro\Desktop\LAUNCHER\Tarslauncher\Tarslauncher\tarslauncher-release.jks`

### Performans Notları
- **İlk Açılış**: İlk açılışta uygulama listesi yükleme süresi 2-3 saniye
- **Icon Pack Loading**: Büyük icon pack'lerde yükleme gecikmesi olabilir
- **Memory Usage**: Ortalama 80-120MB RAM kullanımı

## 🚀 Gelecek Sürümler İçin Planlar

### v1.1 Planları
- **Widget Desteği**: Ana ekran widget'ları
- **Gesture Navigation**: Özel hareket kontrolleri
- **Backup/Restore**: Ayarların yedeklenmesi
- **Tema Sistemi**: Daha fazla renk seçeneği

### v1.2 Planları
- **Search Functionality**: Uygulama arama özelliği
- **App Categories**: Otomatik kategori sınıflandırması
- **Usage Analytics**: Detaylı kullanım istatistikleri
- **Cloud Sync**: Ayarların bulut senkronizasyonu

## 📞 Destek ve İletişim

### Geliştirici Bilgileri
- **E-posta**: emiro@example.com
- **Gizlilik Politikası**: Uygulama içinde mevcut
- **Lisans**: Özel lisans

### Hata Bildirimi
- Hataları e-posta ile bildirin
- Cihaz modeli ve Android sürümünü belirtin
- Hata oluşma adımlarını detaylandırın

---

**Not**: Bu sürüm notları TarsLauncher v1.0'ın tüm özelliklerini ve teknik detaylarını kapsamaktadır. Uygulama sürekli geliştirilmekte ve güncellenektedir.
