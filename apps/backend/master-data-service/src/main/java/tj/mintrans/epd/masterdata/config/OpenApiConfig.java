package tj.mintrans.epd.masterdata.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI: кнопка Authorize (Bearer JWT от Keycloak realm "epd").
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        var bearerAuth = new SecurityScheme()
                .name("bearerAuth")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");
        return new OpenAPI()
                .info(new Info()
                        .title("master-data-service API")
                        .description("Мастер-данные: организации, водители, транспорт, сотрудники")
                        .version("v1"))
                .components(new Components().addSecuritySchemes("bearerAuth", bearerAuth))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
