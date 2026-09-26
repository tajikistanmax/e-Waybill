package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.City;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.IntegratorUpload;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.SubjectDocument;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.CityRepository;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.IntegratorUploadRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationDocumentRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.SubjectDocumentRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сверка 25.09, G4: регистрация субъектов внешней системой в legacy-формате — проверка полей
 * (все ошибки сразу), частичное обновление карточки, вложения из {@code files/upload}.
 */
class RefSubjectServiceTest {

    private OrganizationRepository organizations;
    private VehicleRepository vehicles;
    private DriverRepository drivers;
    private CityRepository cities;
    private IntegratorUploadRepository uploads;
    private SubjectDocumentRepository subjectDocuments;
    private RefSubjectService service;
    private Organization carrier;

    @BeforeEach
    void setUp() {
        organizations = mock(OrganizationRepository.class);
        vehicles = mock(VehicleRepository.class);
        drivers = mock(DriverRepository.class);
        cities = mock(CityRepository.class);
        uploads = mock(IntegratorUploadRepository.class);
        subjectDocuments = mock(SubjectDocumentRepository.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.username()).thenReturn(Optional.of("kvd"));
        DriverTabNumbers tabs = mock(DriverTabNumbers.class);
        when(tabs.resolve(any(), any(), any())).thenReturn("7");
        service = new RefSubjectService(organizations, vehicles, drivers, mock(EmployeeRepository.class), cities,
                uploads, mock(OrganizationDocumentRepository.class), subjectDocuments, tabs, currentUser,
                mock(AuditService.class));

        carrier = new Organization();
        ReflectionTestUtils.setField(carrier, "id", UUID.randomUUID());
        carrier.setRma("025680800");
        carrier.setName("КВД Автобуси Душанбе");
        carrier.setTypeCompany((short) 2);
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(carrier));
        when(organizations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vehicles.save(any())).thenAnswer(i -> i.getArgument(0));
        when(drivers.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> errors(Throwable t) {
        var e = (RefSubjectService.LegacyResponse) t;
        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.body()).containsEntry("message", "The given data was invalid.");
        return (Map<String, List<String>>) e.body().get("errors");
    }

    @Test
    @DisplayName("организация: все ошибки сразу; общего пользования — лицензия обязательна")
    void organizationValidation() {
        var t = catchThrowable(() -> service.storeOrganization(Map.of("rma", "12", "type_company_id", 1, "region_id", 9)));
        assertThat(errors(t)).containsKeys("name", "city_name", "registration_certificate", "iktibos", "rma",
                "address", "phone", "name_head", "region_id", "license_activity_from", "license_activity_to");
    }

    @Test
    @DisplayName("организация: неизвестный город — 404 {error: City not found}")
    void organizationCityNotFound() {
        when(cities.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        var in = Map.<String, Object>of("name", "Сомон", "city_name", "Атлантида", "registration_certificate", "1",
                "iktibos", "2", "rma", "510040663", "address", "ш. Душанбе", "phone", "+992", "name_head", "Раҳимов",
                "region_id", "1", "type_company_id", "2");
        var t = catchThrowable(() -> service.storeOrganization(in));
        var e = (RefSubjectService.LegacyResponse) t;
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.body()).containsEntry("error", "City not found");
    }

    @Test
    @DisplayName("организация создаётся с городом из справочника — 201")
    void organizationCreated() {
        var city = new City();
        ReflectionTestUtils.setField(city, "name", "Душанбе");
        when(cities.findFirstByNameIgnoreCase("душанбе")).thenReturn(Optional.of(city));
        when(organizations.findByRma("510040663")).thenReturn(Optional.empty());
        var in = new HashMap<String, Object>(Map.of("name", "Сомон", "city_name", "душанбе",
                "registration_certificate", "РС-1", "iktibos", "И-2", "rma", "510040663", "address", "ш. Душанбе",
                "phone", "+992", "name_head", "Раҳимов", "region_id", 1, "type_company_id", "2"));
        var saved = service.storeOrganization(in);
        assertThat(saved.created()).isTrue();
        assertThat(saved.body()).containsEntry("city_name", "Душанбе").containsEntry("iktibos", "И-2")
                .containsEntry("region_id", (short) 1);
    }

    @Test
    @DisplayName("водитель: обновление не затирает поля, которых нет в запросе (как updateOrCreate)")
    void driverPartialUpdate() {
        var existing = new Driver();
        existing.setRma("461930031");
        existing.setBirthDate(LocalDate.of(1985, 3, 1));
        existing.setVisaValidTo(LocalDate.of(2027, 1, 1));
        when(drivers.findByRma("461930031")).thenReturn(Optional.of(existing));
        var saved = service.storeDriver(Map.of("full_name", "Носиров Бехруз", "license", "AA123", "category", "B,C",
                "rma", "461930031", "address", "Душанбе", "phone", "+992935200201", "organization_rma", "025680800",
                "med_cert_valid_date", "31.12.2026", "degree", 2));
        assertThat(saved.created()).isFalse();
        assertThat(existing.getBirthDate()).isEqualTo(LocalDate.of(1985, 3, 1));
        assertThat(existing.getVisaValidTo()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(existing.getMedCertValidTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(existing.getLicenseCategories()).isEqualTo("B,C");
        assertThat(saved.body()).containsEntry("tab_number", "7");
    }

    @Test
    @DisplayName("ТС: прицепы и одометр не затираются; неизвестная организация — 404")
    void transportPartialUpdateAndOrganization() {
        var existing = new Vehicle();
        existing.setRegistrationNumber("0114TJ01");
        existing.setTrailer1Number("ТР-1");
        existing.setOdometer(125_000);
        when(vehicles.findByRegistrationNumber("0114TJ01")).thenReturn(Optional.of(existing));
        Map<String, Object> in = new HashMap<>(Map.of("transport_type", "1", "organization_rma", "025680800",
                "registration_number", "0114TJ01", "brand_name", "ПАЗ", "capacity", 45, "year_manufacture", 2019,
                "tech_inspection_number", "ТО-5", "tech_inspection_date_to", "2027-03-01"));
        service.storeTransport(in);
        assertThat(existing.getTrailer1Number()).isEqualTo("ТР-1");
        assertThat(existing.getOdometer()).isEqualTo(125_000);
        assertThat(existing.getTechInspectionValidTo()).isEqualTo(LocalDate.of(2027, 3, 1));

        in.put("organization_rma", "999999999");
        var t = catchThrowable(() -> service.storeTransport(in));
        assertThat(((RefSubjectService.LegacyResponse) t).body()).containsEntry("error", "Organization not found");
    }

    @Test
    @DisplayName("вложение: свой загруженный файл становится документом; чужой — 404 {error, field}")
    void attachments() {
        var upload = new IntegratorUpload();
        upload.setFileType("license_attach");
        upload.setFileName("prava_1790000000.pdf");
        upload.setContentType("application/pdf");
        upload.setData(new byte[] {1, 2, 3});
        upload.setUploadedBy("kvd");
        when(uploads.findByFileName("prava_1790000000.pdf")).thenReturn(Optional.of(upload));
        when(drivers.findByRma("461930031")).thenReturn(Optional.empty());
        Map<String, Object> in = new HashMap<>(Map.of("full_name", "Носиров Бехруз", "license", "AA123",
                "category", "B", "rma", "461930031", "address", "Душанбе", "phone", "+992",
                "organization_rma", "025680800", "license_attach", "prava_1790000000.pdf"));
        service.storeDriver(in);
        var doc = ArgumentCaptor.forClass(SubjectDocument.class);
        verify(subjectDocuments).save(doc.capture());
        assertThat(doc.getValue().getDocType()).isEqualTo("DRIVER_LICENSE");
        assertThat(doc.getValue().getSubjectKey()).isEqualTo("461930031");
        assertThat(doc.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(upload.getUsedAt()).isNotNull();

        upload.setUploadedBy("другая-система");
        var t = catchThrowable(() -> service.storeDriver(in));
        var e = (RefSubjectService.LegacyResponse) t;
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.body()).containsEntry("field", "license_attach");
    }

    @Test
    @DisplayName("files/upload: неверное расширение — 422, неизвестный file_type — 400, иначе 201 с file_name")
    void upload() {
        var exe = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[] {1});
        var t = catchThrowable(() -> service.upload(exe, "photo"));
        assertThat(((RefSubjectService.LegacyResponse) t).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        var pdf = new MockMultipartFile("file", "tex pasport.pdf", "application/pdf", new byte[] {1, 2});
        var t2 = catchThrowable(() -> service.upload(pdf, "unknown_attach"));
        assertThat(((RefSubjectService.LegacyResponse) t2).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(uploads, never()).save(any());

        var ok = service.upload(pdf, "tech_id_number_attach");
        assertThat(ok).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) ok.get("data");
        assertThat((String) data.get("file_name")).startsWith("tex_pasport_").endsWith(".pdf");
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        var t = org.assertj.core.api.Assertions.catchThrowable(c);
        assertThat(t).isInstanceOf(RefSubjectService.LegacyResponse.class);
        return t;
    }

    @Test
    @DisplayName("дата — как правило date legacy: ISO, d.m.Y, ISO со временем")
    void dates() {
        assertThat(RefSubjectService.parseDate("2026-09-26")).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(RefSubjectService.parseDate("26.09.2026")).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(RefSubjectService.parseDate("2026-09-26 10:00:00")).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(RefSubjectService.parseDate("вчера")).isNull();
        assertThatThrownBy(() -> service.storeEmployee(Map.of())).isInstanceOf(RefSubjectService.LegacyResponse.class);
    }
}
