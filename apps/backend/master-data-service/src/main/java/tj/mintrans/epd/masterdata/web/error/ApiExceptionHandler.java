package tj.mintrans.epd.masterdata.web.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Единая модель ошибок RFC 7807 (application/problem+json).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Не найдено");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /** Нарушение ограничений БД (UNIQUE РМА/госномер, NOT NULL, гонки) → 409, не 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Конфликт данных");
        problem.setDetail("Нарушено ограничение целостности данных (возможно, дубликат или обязательное поле)");
        return problem;
    }

    /** Дубликаты для запроса, ожидающего единственную запись (напр. норма/тариф/коэффициент) → 409. */
    @ExceptionHandler(IncorrectResultSizeDataAccessException.class)
    public ProblemDetail nonUnique(IncorrectResultSizeDataAccessException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Неоднозначные данные");
        problem.setDetail("Найдено несколько записей там, где ожидалась одна — требуется устранение дублей");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Ошибка валидации");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Отказ авторизации (@PreAuthorize / ручные проверки «только своя организация») → 403 в едином формате. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail forbidden(org.springframework.security.access.AccessDeniedException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Доступ запрещён");
        problem.setDetail(e.getMessage() != null ? e.getMessage() : "Недостаточно прав для выполнения операции");
        return problem;
    }

    /** Некорректный ввод (в т.ч. NumberFormatException) → 422 вместо дефолтного 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Ошибка валидации");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /** Кривая дата (снимок мастер-данных, параметр) → 422 вместо дефолтного 500. */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ProblemDetail badDate(java.time.format.DateTimeParseException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Ошибка валидации");
        problem.setDetail("Некорректный формат даты");
        return problem;
    }

    /** Непредвиденное нарушение инварианта → 500, но в едином формате RFC 7807 (без утечки деталей). */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail internal(IllegalStateException e) {
        var problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Внутренняя ошибка");
        problem.setDetail("Внутренняя ошибка сервиса");
        return problem;
    }
}
