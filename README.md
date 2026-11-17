# Dosya Açıcı (Android)

Tek ekranlı, sade bir Android dosya görüntüleyici. **Hazır APK: [`dosya-acici.apk`](dosya-acici.apk)**

## Kurulum
1. `dosya-acici.apk` dosyasını telefona indirin ve açın.
2. "Bilinmeyen kaynaklardan yükleme" izni istenirse verin.

## Kullanım
- Uygulamayı açın → **Dosya Seç** → dosyayı seçin.
- Ya da dosya yöneticisinde/WhatsApp'ta bir dosyaya dokunup **Dosya Açıcı**'yı seçin.
- Gösterilemeyen dosyalar için sağ üstteki menüden **Başka uygulamayla aç**.

## Son açılanlar ve kaldığınız yer
Ana ekranda son açılan 20 dosya listelenir; dokununca kaldığınız yerden açılır (PDF'te sayfa, belgelerde kaydırma konumu, video/seste süre).
Başka bir uygulamadan (ör. WhatsApp) açılan dosyalara o uygulama kalıcı izin vermediği için daha sonra erişilemeyebilir; o zaman dosya listeden kaldırılır.

## Düzenleme
Sağ üstteki **Düzenle** düğmesiyle:
- **Metin dosyaları** (txt, csv, json, md, kod…): tüm metin değiştirilebilir. **Kaydet** dosyanın üzerine yazar (UTF-8 olarak).
- **Word (docx) ve PowerPoint (pptx)**: sarı alanlardaki metin yerinde değiştirilir; yazı tipi, kalınlık, resimler korunur. Yeni paragraf/tablo eklenemez.
- **PDF**: değiştirmek istediğiniz metnin üzerini parmağınızla çizerek seçin, yeni metni yazın. Seçilen alan beyazla kapatılıp yeni metin yazılır; kutuya tekrar dokunarak değiştirip silebilirsiniz. Kaydırmak için **Bitti**'ye basın. **Kaydet** her zaman *yeni bir PDF* oluşturur (`…-duzenlenmis.pdf`); bu kopyada sayfalar resim olarak saklandığı için metin seçilemez/aranamaz, orijinal dosya değişmez.
- Dosyanın üzerine yazılamıyorsa (ör. başka uygulamadan açıldıysa) nereye kaydedileceği sorulur.
- Excel, eski .doc/.xls, odt ve HTML dosyaları yalnızca görüntülenir.

## Desteklenen biçimler
| Tür | Uzantılar | Not |
|---|---|---|
| PDF | pdf | Sayfa sayfa; iki parmakla yakınlaştırma |
| Word | docx, odt, doc | Metin, paragraflar ve tablolar |
| Excel | xlsx, ods, xls | Tüm sayfalar tablo olarak |
| PowerPoint | pptx, odp | Slayt slayt metin |
| Resim | jpg, png, gif, webp, bmp, svg… | Yakınlaştırılabilir |
| Video / Ses | mp4, mkv, webm, mp3, wav, m4a, ogg… | Telefonun oynatabildiği her şey |
| Metin / kod | txt, csv, json, xml, md, log, html… | UTF-8 ve Türkçe Windows kodlaması |
| Arşiv | zip, apk, epub | İçerik listesi |

Eski `.doc` (Word 97–2003) ve `.xls` (Excel 97–2003) dosyaları da açılır. `.doc` tablolarındaki hücreler `|` ile ayrılmış satırlar olarak, `.xls` tarihleri ise sayı olarak görünür.
Word/Excel 95 ve öncesi, `.ppt`, şifreli dosyalar ve şifreli PDF'ler gösterilmez; "Başka uygulamayla aç" sunulur.
Office dosyalarında resimler ve yazı biçimlendirmesi (kalın, renk vb.) gösterilmez.

## Derleme
Gradle gerekmez (Ubuntu/Debian):
```sh
apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner libkxml2-java default-jdk
./test.sh            # dönüştürücü testleri
test/edit-flow.sh    # docx düzenleme uçtan uca (Chromium + LibreOffice gerekir)
./build.sh   # dosya-acici.apk üretir
```
`debug.keystore` (şifre: `android`) aynı imzayla güncelleme yapılabilmesi için depoda tutulur; Play Store'a yüklemek için kendi anahtarınızı kullanın.
