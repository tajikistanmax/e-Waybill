description = "Ядро платформы: путевые листы, титулы Т1–Т6, статусная модель, QR"

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("org.springframework.kafka:spring-kafka") // события жизненного цикла ПЛ
    // Печать бланков ПЛ: шаблон HTML (Thymeleaf) -> PDF (openhtmltopdf) + QR (ZXing).
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    // Выгрузка отчётов в XLSX (Apache POI, OOXML).
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // Интеграционные тесты жизненного цикла ПЛ против реального Postgres + Flyway
    // (см. src/test/.../integration) — версии управляются Spring Boot BOM (без пиннинга).
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
