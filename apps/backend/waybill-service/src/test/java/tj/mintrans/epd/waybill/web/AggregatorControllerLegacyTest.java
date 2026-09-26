package tj.mintrans.epd.waybill.web;

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
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.service.AggregatorService;
import tj.mintrans.epd.waybill.web.error.ApiErrors;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сверка 25.09, G3: ответы канала агрегатора — как legacy {@code waybill_neru}
 * ({@code statusCode}, {@code waybill_id}, {@code error}, {@code errors: {поле: [...]}}).
 */
@WebMvcTest(AggregatorController.class)
@Import(SecurityConfig.class)
class AggregatorControllerLegacyTest {

    @Autowired MockMvc mvc;
    @MockitoBean AggregatorService service;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String TODAY = LocalDate.now().toString();

    private static RequestPostProcessor integrator() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_API_INTEGRATOR"));
    }

    private static String body(String exit, String entry) {
        return """
                {"organization_rma":"025680800","transport_registration_number":"0114TJ01","driver_rma":"461930031",
                 "exit_date":"%s","entry_date":"%s","distance":50}""".formatted(exit, entry);
    }

    private static Waybill confirmed() {
        var wb = new Waybill();
        org.springframework.test.util.ReflectionTestUtils.setField(wb, "id", UUID.randomUUID());
        wb.setNumber("01-26-02-0000128-6");
        wb.setStatus(WaybillStatus.ISSUED);
        wb.setValidFrom(OffsetDateTime.now());
        wb.setValidTo(OffsetDateTime.now().plusHours(10));
        wb.setOrganizationSnapshot(Map.of("id", "7", "rma", "025680800", "name", "Сомон Такси"));
        wb.setVehicleSnapshot(Map.of("id", "9", "registrationNumber", "0114TJ01", "transportType", 4, "odometer", 1200));
        wb.setDriverSnapshot(Map.of("id", "3", "fullName", "Носиров Бехруз", "rma", "461930031"));
        return wb;
    }

    @Test
    void newRequestIs201WithWaybillId() throws Exception {
        var wb = confirmed();
        when(service.submit(anyString(), anyString(), anyString(), isNull(), any(), any(), anyInt()))
                .thenReturn(new AggregatorService.Submission(wb, true));
        mvc.perform(post("/api/v1/aggregator/waybills").with(integrator()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(TODAY + " 08:00", TODAY + " 21:00:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.waybill_id").value(wb.getId().toString()));
    }

    @Test
    void repeatedRequestReturnsCurrentWaybill() throws Exception {
        when(service.submit(anyString(), anyString(), anyString(), isNull(), any(), any(), anyInt()))
                .thenReturn(new AggregatorService.Submission(confirmed(), false));
        mvc.perform(post("/api/v1/aggregator/waybills").with(integrator()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(TODAY + " 08:00", TODAY + " 21:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.number").value("01-26-02-0000128-6"))
                .andExpect(jsonPath("$.indication_counter_exit").value(1200))
                .andExpect(jsonPath("$.parking.registration_number").value("0114TJ01"))
                .andExpect(jsonPath("$.parking.transport_type_id").value(4))
                .andExpect(jsonPath("$.timesheet.full_name").value("Носиров Бехруз"));
    }

    @Test
    void validationErrorsAreKeyedByField() throws Exception {
        mvc.perform(post("/api/v1/aggregator/waybills").with(integrator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization_rma\":\"12\",\"exit_date\":\"26.09.2026\",\"distance\":\"много\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.statusCode").value(422))
                .andExpect(jsonPath("$.message").value("The given data was invalid."))
                .andExpect(jsonPath("$.errors.organization_rma").isArray())
                .andExpect(jsonPath("$.errors.transport_registration_number[0]").value("Параметр transport_registration_number обязателен."))
                .andExpect(jsonPath("$.errors.exit_date[0]").value("Дата и время должны быть в формате Y-m-d H:i или Y-m-d H:i:s"))
                .andExpect(jsonPath("$.errors.entry_date[0]").value("Параметр entry_date обязателен."))
                .andExpect(jsonPath("$.errors.distance").isArray());
        verify(service, never()).submit(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void serviceErrorsInLegacyShape() throws Exception {
        when(service.submit(anyString(), anyString(), anyString(), isNull(), any(), any(), anyInt()))
                .thenThrow(new ApiErrors.NotFoundException("Organization not found"));
        mvc.perform(post("/api/v1/aggregator/waybills").with(integrator()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(TODAY + " 08:00", TODAY + " 21:00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.error").value("Organization not found"));

        UUID id = UUID.randomUUID();
        when(service.getConfirmed(id)).thenThrow(new ApiErrors.ConflictException("Доктор не подтвердил путёвку"));
        mvc.perform(get("/api/v1/aggregator/waybills/" + id).with(integrator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409))
                .andExpect(jsonPath("$.error").value("Доктор не подтвердил путёвку"));

        org.mockito.Mockito.doThrow(new ApiErrors.FieldException("exit_date", "Дата выезда должна быть сегодняшней"))
                .when(service).submit(anyString(), anyString(), anyString(), isNull(), any(), any(), anyInt());
        mvc.perform(post("/api/v1/aggregator/waybills").with(integrator()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(TODAY + " 08:00", TODAY + " 21:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.exit_date[0]").value("Дата выезда должна быть сегодняшней"));
    }
}
