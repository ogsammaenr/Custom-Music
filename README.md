# Custom Discs — Fabric 26.3 (Multiplayer Compatible)

Oyuncuların oyun içindeki **Plak Yazıcı (Disc Burner)** arayüzüne doğrudan **YouTube linki yapıştırarak** veya `.mp3`, `.wav`, `.ogg`, `.webm` dosyası sürükleyip bırakarak özel müzik diskleri üretebildiği, **Minecraft 26.3 (Fabric)** için çok oyunculu uyumlu mod.

## Özellikler
- **Tek Adımda YouTube Linki ile Disk Yazma:**
  - Slota Boş Müzik Diski yerleştirip YouTube linkini (`youtube.com/watch?v=...`, `youtu.be/...`, `music.youtube.com/...`, `shorts/...`) yapıştırıp **Enter**'a (veya **İndir** butonuna) basmanız yeterli.
  - Şarkı adı ve sanatçı otomatik doldurulur; slotta disk varsa indirme bitince **otomatik olarak sunucuya yüklenip diske yazılır**.
  - **FFmpeg gerekmez:** YouTube'un yerel WebM/Opus ses akışı saf Java `WebmOpusDemuxer` + **Concentus** ile doğrudan çözülüp 48 kHz Mono Opus'a dönüştürülür.
  - `yt-dlp` sistem PATH'inde veya `.minecraft/customdiscs_tools/yt-dlp(.exe)` konumunda varsa otomatik olarak onu kullanır; yoksa yerleşik saf Java YouTube istemcisini dener.
- **Sürükle-Bırak (Drag & Drop):** `.mp3`, `.wav`, `.ogg` ve `.webm` dosyaları da doğrudan oyun penceresine sürüklenebilir.
- **Multiplayer Senkronizasyonu & 3D OpenAL Ses:**
  - Parçalar `SHA-256` hash'i ile `<world>/data/customdiscs/<sha256>.cdopus` altında tekilleştirilerek saklanır.
  - 24 KB'lık parçalı paketlerle hız sınırlamalı yükleme/indirme ve `.minecraft/customdiscs_cache/` önbelleği.
  - Sonradan yaklaşan veya sunucuya giren oyuncular çalan müziği kaldığı saniyeden 3D konumsal sesle duyar.
- **Vanilla Jukebox Entegrasyonu:** Sağ tık veya Huni (Hopper) ile takılır, nota parçacığı çıkarır ve Redstone Comparator (`15`) verir.
- **Türkçe (`tr_tr`) ve İngilizce (`en_us`) Dil Desteği.**

## YouTube İpucu (Önerilen)
Bazı ağlarda veya videolarda YouTube ek doğrulama/şifre çözme gerektirebilir. En yüksek uyumluluk için `yt-dlp` aracını sisteminize kurmanız (`winget install yt-dlp.yt-dlp`) veya `yt-dlp.exe` dosyasını `.minecraft/customdiscs_tools/` klasörüne koymanız yeterlidir (FFmpeg kurmanıza gerek yoktur).

## Üretim Tarifleri (Crafting)
- **Boş Müzik Diski (`customdiscs:blank_disc`):** 4 Demir Külçesi + ortada 1 Nether Kuvarsı (2 adet verir).
- **Plak Yazıcı (`customdiscs:disc_burner`):** Üstte Demir–Kızıltaş–Demir, ortada Tahta–Nota Bloğu–Tahta, altta 3 Tahta.

## Sunucu Yapılandırması (`config/customdiscs.json`)
```json
{
  "maxFileSizeBytes": 6291456,
  "maxDurationSeconds": 600,
  "uploadCooldownSeconds": 5,
  "opusBitrateBps": 64000,
  "allowAllPlayersToUpload": true,
  "maxStoredTracks": 500,
  "jukeboxHearRange": 64.0
}
```

## Gereksinimler & Derleme
- **Minecraft:** `26.3` | **Fabric Loader:** `>= 0.20.1` | **Fabric API:** `0.167.0+26.3` | **Java:** `25`

```bash
./gradlew test build
```
Çıktı dosyası: `build/libs/customdiscs-1.0.0.jar`
