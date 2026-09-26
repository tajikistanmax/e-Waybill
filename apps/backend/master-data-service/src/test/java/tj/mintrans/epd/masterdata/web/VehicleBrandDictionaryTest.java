package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Brand;
import tj.mintrans.epd.masterdata.repository.BrandRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Марка ТС при ручном вводе — из справочника марок (сверка 25.09, F4): опечатка давала норму топлива 0. */
class VehicleBrandDictionaryTest {

    private VehicleController controller;

    @BeforeEach
    void setUp() {
        BrandRepository brands = mock(BrandRepository.class);
        Brand kamaz = new Brand();
        kamaz.setName("КамАЗ");
        when(brands.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(brands.findFirstByNameIgnoreCase("камаз")).thenReturn(Optional.of(kamaz));
        controller = new VehicleController(null, null, null, null, null, null, null, null, null, brands);
    }

    @Test
    @DisplayName("марка из справочника приводится к справочному написанию")
    void canonical() {
        assertThat(controller.dictionaryBrand(" камаз ", null)).isEqualTo("КамАЗ");
    }

    @Test
    @DisplayName("опечатка — 422 с подсказкой выбрать из списка")
    void typo() {
        assertThatThrownBy(() -> controller.dictionaryBrand("КАМАС", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("не найдена в справочнике марок");
    }

    @Test
    @DisplayName("марка не меняется — старое написание перенесённой записи не мешает правке других полей")
    void unchangedLegacySpelling() {
        assertThat(controller.dictionaryBrand("Kamaz-5320", "KAMAZ-5320")).isEqualTo("KAMAZ-5320");
        assertThat(controller.dictionaryBrand("", "X")).isNull();
    }
}
