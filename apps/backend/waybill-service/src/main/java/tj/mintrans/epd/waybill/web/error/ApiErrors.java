package tj.mintrans.epd.waybill.web.error;

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
