## v2.0.30 — Pil ve performans düzeltmeleri

- Elmasın hızı sıfırken yalnızca statik bir kare çizilir; gereksiz çizim döngüsü durduruldu.
- Launcher arka plana geçince elmasın çizim işi tamamen iptal edilir; hareketli görüntü ekranın kare zamanlamasını izler.
- Widget güncellemeleri yalnızca widget görünürken dinlenir; kapalı modda ve ayarlar ekranında dinleme durur.
- Uygulama değişiklikleri paket bazında yenilenir; ilgisiz ikon önbellekleri korunur ve arka plandaki yenilemeler dönüşe ertelenir.
- İkon paketlerinin bitmap ve maskeleme işlemleri UI thread'i dışına taşındı; yalnızca gösterilen ikonlar hazırlanır.
- Tek widget sınırı ve sade ana ekran görünümü korundu.
