package tj.mintrans.epd.waybill.print;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Отрисовка печатного бланка ПЛ: шаблон HTML (Thymeleaf) → PDF (openhtmltopdf).
 *
 * <p>Перенос {@code tj.etrans.rohkhat.print.PdfRenderService}. В отличие от браузерной
 * печати HTML-страницы, серверный PDF — воспроизводимый файл: его можно приложить к делу,
 * сдать в архив и подписать усиленной ЭП (PAdES) позже.</p>
 *
 * <p>Шрифт DejaVu Sans встроен в документ — бланки на таджикском (ӣ, ҳ, ҷ, қ, ӯ, ғ)
 * не отобразятся без него на машине без этого шрифта.</p>
 */
@Service
public class PdfRenderService {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderService.class);

    public static final String FONT_FAMILY = "DejaVu Sans";
    private static final String FONT_REGULAR = "fonts/DejaVuSans.ttf";
    private static final String FONT_BOLD = "fonts/DejaVuSans-Bold.ttf";
    private static final String BASE_URI = "classpath:/templates/print/";

    private final TemplateEngine templateEngine;

    public PdfRenderService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Отрисовка шаблона в PDF.
     *
     * @param template имя шаблона без расширения ({@code print/waybill})
     * @param model    значения для подстановки
     * @return содержимое PDF
     */
    public byte[] render(String template, Map<String, Object> model) {
        Context context = new Context();
        context.setVariables(model);
        String html = templateEngine.process(template, context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, BASE_URI);
            builder.useFont(() -> openFont(FONT_REGULAR), FONT_FAMILY, 400,
                    BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> openFont(FONT_BOLD), FONT_FAMILY, 700,
                    BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.toStream(out);
            builder.run();
            byte[] pdf = out.toByteArray();
            log.debug("Печатная форма {}: {} байт", template, pdf.length);
            return pdf;
        } catch (IOException e) {
            log.error("Не удалось отрисовать печатную форму {}", template, e);
            throw new UnprocessableException("Не удалось сформировать печатную форму");
        }
    }

    /** ASCII-имя файла для Content-Disposition (произвольные строки из БД в заголовок не попадают). */
    public static String fileName(String number) {
        String safe = number == null ? "no-number" : number.replaceAll("[^0-9A-Za-z-]", "");
        return "waybill-" + safe + ".pdf";
    }

    private InputStream openFont(String resource) {
        try {
            ClassPathResource cp = new ClassPathResource(resource);
            if (cp.exists()) {
                return cp.getInputStream();
            }
            throw new UnprocessableException("Шрифт печатной формы не найден: " + resource);
        } catch (IOException e) {
            throw new UnprocessableException("Шрифт печатной формы недоступен: " + resource);
        }
    }
}
