package tj.mintrans.epd.masterdata.web.error;

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
}
