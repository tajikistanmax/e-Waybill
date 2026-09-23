description = "Мастер-данные: организации, водители, транспортные средства, сотрудники"

dependencies {
    // Redis-бэкенд Spring Cache: кэш справочников (классификаторы) и эффективных политик.
    // Стартер тянет spring-data-redis + Lettuce; RedisCacheManager настраивается в CacheConfig.
    "implementation"("org.springframework.boot:spring-boot-starter-data-redis")
    // Подпись токенов платформы (после отказа от Keycloak, 23.09.2026): служба сама выпускает
    // JWT. Проверка подписи приходит транзитивно с resource-server, но ВЫПУСК требует
    // библиотеку явно — иначе сборка зависит от случайного транзитивного попадания.
    "implementation"("com.nimbusds:nimbus-jose-jwt:9.40")
}
