package tj.mintrans.epd.masterdata.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Страница списка для реестров. Отдаётся вместо полного списка таблицы: на боевых данных
 * (89 287 ТС / 68 006 водителей) неограниченный {@code GET /api/v1/vehicles} возвращал
 * 87 МБ за 42 с, и браузер администратора платформы вставал (находка приёмки 22.09.2026).
 *
 * @param content  строки текущей страницы
 * @param total    всего строк, удовлетворяющих фильтру
 * @param page     номер страницы, с нуля
 * @param size     размер страницы
 * @param totalPages число страниц (минимум 1, чтобы интерфейс не делил на ноль)
 */
public record PagedResult<T>(List<T> content, long total, int page, int size, int totalPages) {

    public static <T> PagedResult<T> of(Page<T> p) {
        return new PagedResult<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize(),
                Math.max(1, p.getTotalPages()));
    }
}
