description = "Мастер-данные: организации, водители, транспортные средства, сотрудники"

dependencies {
    // Redis-бэкенд Spring Cache: кэш справочников (классификаторы) и эффективных политик.
    // Стартер тянет spring-data-redis + Lettuce; RedisCacheManager настраивается в CacheConfig.
    "implementation"("org.springframework.boot:spring-boot-starter-data-redis")
}
