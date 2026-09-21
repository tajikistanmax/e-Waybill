package tj.mintrans.epd.waybill.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §2.6 — подпись водителя на бланке: байты одобренного документа SIGNATURE → data URI
 * для {@code <img>} в Thymeleaf/openhtmltopdf; не изображение или пусто → null (бланк без картинки).
 */
class MasterDataClientDataUriTest {

    @Test
    @DisplayName("image/png → data:image/png;base64,…; параметры типа отбрасываются")
    void imageToDataUri() {
        byte[] png = "PNG".getBytes(StandardCharsets.US_ASCII);
        assertThat(MasterDataClient.dataUri("image/png", png)).isEqualTo("data:image/png;base64,UE5H");
        assertThat(MasterDataClient.dataUri("image/jpeg; charset=binary", png)).startsWith("data:image/jpeg;base64,");
    }

    @Test
    @DisplayName("PDF, пустое тело, null → null")
    void nonImageIsNull() {
        byte[] b = new byte[]{1};
        assertThat(MasterDataClient.dataUri("application/pdf", b)).isNull();
        assertThat(MasterDataClient.dataUri("image/png", new byte[0])).isNull();
        assertThat(MasterDataClient.dataUri(null, b)).isNull();
        assertThat(MasterDataClient.dataUri("image/png", null)).isNull();
    }
}
