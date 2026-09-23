package tj.mintrans.epd.waybill.web.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Единая модель ошибок RFC 7807; коды 404/409/422 сохраняют legacy-семантику. */
@RestControllerAdvice
public class ApiErrors {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiErrors.class);

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }

    public static class UnprocessableException extends RuntimeException {
        public UnprocessableException(String m) { super(m); }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String m) { super(m); }
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Не найдено", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Конфликт", e.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    public ProblemDetail unprocessable(UnprocessableException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Ошибка валидации", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", e.getMessage());
    }

    /**
     * Отказ @PreAuthorize/hasRole без этого обработчика уходит клиенту ПУСТЫМ телом (дефолт
     * Spring Security) — фронтенд, ожидающий {@code error.detail}, показывает пользователю
     * пустое сообщение вместо причины (найдено УАТ 2026-09-04, компакт-находка «Тела ошибок
     * непоследовательны»).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail accessDenied(AccessDeniedException e) {
        // «Access Denied» — стандартный английский текст Spring Security для @PreAuthorize; до
        // пользователя он доходил как есть (например, бухгалтер на странице региональных отчётов).
        String m = e.getMessage();
        boolean generic = m == null || m.isBlank() || "Access Denied".equalsIgnoreCase(m.trim());
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён",
                generic ? "Недостаточно прав для этого действия" : m);
    }

    /**
     * master-data ответил 429 (rate-limit по IP при массовых справочных запросах отчётов) —
     * это временная перегрузка зависимости, а не ошибка сервера: 503 + Retry-After, а не 500.
     */
    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class)
    public ProblemDetail masterDataThrottled(org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
        String retry = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        var p = problem(HttpStatus.SERVICE_UNAVAILABLE, "Справочная служба временно перегружена",
                "Служба мастер-данных ограничила частоту запросов"
                        + (retry != null ? " — повторите через " + retry + " с" : " — повторите позже"));
        if (retry != null) {
            p.setProperty("retryAfterSeconds", retry);
        }
        return p;
    }

    /** master-data недоступен (сеть/таймаут) — 503, а не 500. */
    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ProblemDetail masterDataUnavailable(org.springframework.web.client.ResourceAccessException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Справочная служба недоступна",
                "Служба мастер-данных не отвечает — повторите позже");
    }

    /**
     * {@link ResponseStatusException} (напр. в WaybillAttachmentController) без явного
     * обработчика получает тело от стандартного {@code BasicErrorController} Spring Boot
     * (поле {@code message}, не RFC 7807 {@code detail}) — приводим к общему виду.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail responseStatus(ResponseStatusException e) {
        var p = ProblemDetail.forStatus(e.getStatusCode());
        p.setTitle(e.getStatusCode().toString());
        p.setDetail(e.getReason() != null ? e.getReason() : e.getMessage());
        return p;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataIntegrity(DataIntegrityViolationException e) {
        // Нарушение ограничения БД (уникальность номера/ПЛ на ТС-водителя, NOT NULL, гонки) → 409, не 500.
        // Текст нейтральный: тот же класс исключения летит для разных ограничений — не вводим в заблуждение.
        return problem(HttpStatus.CONFLICT, "Конфликт данных",
                "Нарушено ограничение целостности данных (возможно, дубликат или обязательное поле)");
    }

    /** Дубликаты там, где ожидалась одна запись (напр. строка оплаты) → 409 (паритет с master-data). */
    @ExceptionHandler(org.springframework.dao.IncorrectResultSizeDataAccessException.class)
    public ProblemDetail nonUnique(org.springframework.dao.IncorrectResultSizeDataAccessException e) {
        return problem(HttpStatus.CONFLICT, "Неоднозначные данные",
                "Найдено несколько записей там, где ожидалась одна — требуется устранение дублей");
    }

    /** Некорректный ввод (в т.ч. NumberFormatException при разборе снимков) → 422 вместо 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Ошибка валидации", e.getMessage());
    }

    /** Кривая дата в снимке мастер-данных/параметре → 422 вместо 500. */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ProblemDetail badDate(java.time.format.DateTimeParseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Ошибка валидации", "Некорректный формат даты");
    }

    /**
     * Внутренние сбои (подпись QR/CAdES, сервисный токен) → 500 в едином формате RFC 7807, без утечки деталей.
     * Причина обязательно пишется в журнал службы: до 23.09.2026 здесь не было ни строки лога, и
     * закрытие путевого листа падало с 500 «Внутренняя ошибка сервиса» при полностью чистом
     * {@code docker logs} — причину (не задан пароль служебной учётной записи) пришлось искать по коду.
     */
    /**
     * Служба не смогла войти служебной учётной записью (закрытие листа переносит одометр ТС в
     * справочник именно ею). Это не ошибка данных пользователя и не «внутренняя ошибка» —
     * 503 с понятным текстом, чтобы диспетчер позвал администратора, а не повторял закрытие.
     */
    @ExceptionHandler(tj.mintrans.epd.waybill.client.ServiceTokenProvider.ServiceAccountUnavailableException.class)
    public ProblemDetail serviceAccount(tj.mintrans.epd.waybill.client.ServiceTokenProvider.ServiceAccountUnavailableException e) {
        log.error("Служебная учётная запись недоступна: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Служебная учётная запись недоступна",
                "Служба путевых листов не может обратиться к справочникам от своего имени "
                        + "(служебная учётная запись не настроена или отклонена) — обратитесь к администратору платформы");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail internal(IllegalStateException e) {
        log.error("Внутренняя ошибка обработки запроса: {}", e.getMessage(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка", "Внутренняя ошибка сервиса");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        var problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Ошибка валидации", null);
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var p = ProblemDetail.forStatus(status);
        p.setTitle(title);
        if (detail != null) p.setDetail(detail);
        return p;
    }
}
