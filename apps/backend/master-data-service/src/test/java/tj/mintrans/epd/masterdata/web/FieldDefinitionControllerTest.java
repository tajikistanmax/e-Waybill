package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.domain.FieldDefinition;
import tj.mintrans.epd.masterdata.repository.FieldDefinitionRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Конструктор полей: определение поля проверяется на сервере (раньше принималось всё —
 * ключ с пробелами и кириллицей, несуществующий тип данных, список без вариантов).
 */
@WebMvcTest(FieldDefinitionController.class)
@Import(SecurityConfig.class)
class FieldDefinitionControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean FieldDefinitionRepository repository;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
    }

    @BeforeEach
    void setUp() {
        when(repository.findByWaybillTypeAndFieldKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/v1/field-definitions").with(admin())
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json));
    }

    @Test
    void rejectsBadKeyTypeAndEmptyEnum() throws Exception {
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"моё поле\",\"labelRu\":\"x\",\"dataType\":\"STRING\"}")
                .andExpect(status().isUnprocessableEntity());
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"weight\",\"labelRu\":\"x\",\"dataType\":\"FOO\"}")
                .andExpect(status().isUnprocessableEntity());
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"kind\",\"labelRu\":\"x\",\"dataType\":\"ENUM\",\"options\":\" , \"}")
                .andExpect(status().isUnprocessableEntity());
        postJson("{\"waybillType\":\"NOPE\",\"fieldKey\":\"weight\",\"labelRu\":\"x\",\"dataType\":\"STRING\"}")
                .andExpect(status().isUnprocessableEntity());
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"kind\",\"labelRu\":\"x\",\"dataType\":\"ENUM\",\"options\":\"A, A\"}")
                .andExpect(status().isUnprocessableEntity());
        verify(repository, never()).save(any());
    }

    @Test
    void normalisesEnumOptionsAndDropsOptionsForOtherTypes() throws Exception {
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"kind\",\"labelRu\":\"Вид\",\"labelTj\":\"Намуд\","
                + "\"dataType\":\"enum\",\"options\":\" A ,B,, C \",\"required\":true,\"sortOrder\":5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataType").value("ENUM"))
                .andExpect(jsonPath("$.options").value("A, B, C"))
                .andExpect(jsonPath("$.labelTj").value("Намуд"))
                .andExpect(jsonPath("$.sortOrder").value(5));
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"note\",\"labelRu\":\"Примечание\",\"dataType\":\"STRING\",\"options\":\"A\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options").isEmpty());
    }

    @Test
    void existingFieldIsUpdatedAndCanBeHidden() throws Exception {
        var f = new FieldDefinition();
        f.setWaybillType("WB_SPECIAL");
        f.setFieldKey("note");
        f.setLabelRu("Старое");
        f.setDataType("STRING");
        when(repository.findByWaybillTypeAndFieldKey("WB_SPECIAL", "note")).thenReturn(Optional.of(f));
        postJson("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"note\",\"labelRu\":\"Новое\",\"dataType\":\"STRING\",\"active\":false}")
                .andExpect(status().isOk());
        assertThat(f.getLabelRu()).isEqualTo("Новое");
        assertThat(f.isActive()).isFalse();
    }

    @Test
    void onlyPlatformAdminChangesFields() throws Exception {
        mvc.perform(post("/api/v1/field-definitions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"waybillType\":\"WB_SPECIAL\",\"fieldKey\":\"note\",\"labelRu\":\"x\",\"dataType\":\"STRING\"}"))
                .andExpect(status().isForbidden());
    }
}
