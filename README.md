# Log Anomaly Assistant

## Yaklaşım

Çalıştırma: `mvn spring-boot:run`. Test: `mvn test`. HTTP API: `GET /stats`, `GET /anomalies`, `POST /ask`.

Uygulama classpath'teki JSONL dosyasını yerelde okur; servis istatistiklerini ve deterministik anomalileri hesaplar. `/ask`, soruya ilgili sınırlı istatistik/anomali özetlerini ve küçük kanıt örneklerini seçer. Tam JSONL dosyası veya ham `LogEntry` nesneleri LLM'e gönderilmez. E-posta `user1071@example.com` → `u***@example.com`, IPv4 `185.220.101.47` → `185.220.xxx.xxx` olarak maskelenir; IPv6 adresleri de maskelenir. `LlmClient` sağlayıcı bağımlılığını ayırır.

`OPENAI_API_KEY` yoksa dış çağrı yapmayan deterministik `FallbackLlmClient` kullanılır; bu gerçek bir LLM değildir. Anahtar varsa `OpenAiLlmClient` yalnızca maskelenmiş, kompakt prompt'u OpenAI'ye gönderir. Model doğrulanmış bulguları doğal biçimde açıklar; bunlardan türetilen yorumları “Olası yorum” olarak belirsizlik diliyle ayırır ve en fazla bir sonraki inceleme adımı önerir. Varsayılan model `gpt-4o-mini`dir ve `OPENAI_MODEL` ile değiştirilebilir. `LLM_API_KEY`, `LLM_MODEL` ve `LLM_BASE_URL` OpenAI-uyumlu sağlayıcılar için desteklenen alternatiflerdir ve varsa önceliklidir. Örnek: `export OPENAI_API_KEY='...'; export OPENAI_MODEL='gpt-4o-mini'; mvn spring-boot:run`.

İki milyon satır için giriş stream edilir, servis/saat metrikleri önceden toplanır, zaman ve servisle bölümleme veya indeksleme yapılır, dedektör kanıt pencereleri sınırlandırılır; LLM'e yalnızca bu özetler gönderilir. Geliştirme promptları `PROMPTS.md` dosyasındadır.

## Bulgular

- **auth-service:** 85 adet HTTP 401 login hatası, `2026-09-21T03:12:00.830Z`–`03:26:44.030Z`; tek IP, 53 kullanıcı; örnek traceId `632662c553bd`.
- **inventory-service:** 38 negatif stok/HTTP 409 çatışması, `12:00:23.674Z`–`12:59:33.207Z`; `SKU-88231`, `stock went negative`; traceId `11fe04943aec`.
- **order-service:** `order-reconciliation` için açıkça duyurulmuş `2026-09-21T14:00:00Z` çalışması yok; scheduler kaydında traceId bulunmuyor.
- **checkout-service:** Aynı checkout mesajıyla 45 HTTP 500, `15:40:49.128Z`–`16:10:23.196Z`; traceId `0cf74d8fa950`.
- **payment-service:** 71 yüksek gecikmeli authorization isteği, `21:05:18.890Z`–`21:35:07.734Z`, 1.517–7.799 ms; traceId `c70a48835071`.

Bu bulgular loglardaki korelasyonu gösterir; kök neden kanıtı değildir.

## AI Kullanım Notu

OpenAI Codex; proje yapısı, JSONL ayrıştırma, istatistik/anomali mantığı, testler ve dokümantasyon için kullanıldı. Öneriler kod ve gerçek log çıktısıyla gözden geçirildi.

- Codex'in yalnızca gözlenen çalışma ritminden eksik job çıkarımı önerisi reddedildi; uygulama açık bir planlanmış zaman kaydı olmadan eksik job bildirmez.
- Codex'in p95'i tek gecikme tabanı olarak kullanma önerisi reddedildi; yaygın olay p95'i şişirebileceğinden, dedektör servis medyanının üç katını ve tekrar eşiğini kullanır.
