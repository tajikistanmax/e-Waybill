package tj.mintrans.epd.waybill.web.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Единая модель ошибок RFC 7807; коды 404/409/422 сохраняют legacy-семантику. */
@RestControllerAdvice
public class ApiErrors {

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

    /** Отказ авторизации @PreAuthorize → 403 в едином формате (как и ForbiddenException). */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail accessDenied(org.springframework.security.access.AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён",
                e.getMessage() != null ? e.getMessage() : "Недостаточно прав для выполнения операции");
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

    /** Внутренние сбои (подпись QR/CAdES, сервисный токен) → 500 в едином формате RFC 7807, без утечки деталей. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail internal(IllegalStateException e) {
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
