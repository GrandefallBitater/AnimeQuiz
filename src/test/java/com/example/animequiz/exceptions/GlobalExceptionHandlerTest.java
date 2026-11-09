package com.example.animequiz.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "maxFileSize", "5MB");
    }

    @Test
    void invalidInputBecomesBadRequest() {
        ResponseEntity<Map<String, String>> response =
                handler.handleInvalidInputException(new InvalidInputException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "bad");
    }

    @Test
    void invalidInputWithoutMessageGetsFallback() {
        ResponseEntity<Map<String, String>> response =
                handler.handleInvalidInputException(new InvalidInputException(null));

        assertThat(response.getBody()).containsEntry("message", "Ошибка");
    }

    @Test
    void malformedBodyBecomesBadRequest() {
        ResponseEntity<Map<String, String>> response =
                handler.handleHttpMessageNotReadableException(new HttpMessageNotReadableException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Некорректное тело запроса");
    }

    @Test
    void notFoundBecomesNotFound() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNotFoundException(new NotFoundException("missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "missing");
    }

    @Test
    void missingStaticResourceBecomesNotFound() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNoResourceFoundException(new NoResourceFoundException(HttpMethod.GET, "/missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Страница не найдена");
    }

    @Test
    void unsupportedMethodBecomesMethodNotAllowed() {
        ResponseEntity<Map<String, String>> response =
                handler.handleHttpRequestMethodNotSupportedException(new HttpRequestMethodNotSupportedException("PUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("message", "Метод не поддерживается");
    }

    @Test
    void conflictBecomesConflict() {
        ResponseEntity<Map<String, String>> response = handler.handleConflictException(new ConflictException("taken"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "taken");
    }

    @Test
    void oversizedUploadBecomesPayloadTooLarge() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("message", "Файл больше 5 МБ");
    }

    @Test
    void externalServiceFailureBecomesBadGateway() {
        ResponseEntity<Map<String, String>> response =
                handler.handleExternalServiceException(new ExternalServiceException("upstream"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("message", "upstream");
    }

    @Test
    void accessDeniedBecomesForbidden() {
        ResponseEntity<Map<String, String>> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Доступ запрещён");
    }

    @Test
    void unexpectedExceptionBecomesInternalServerError() {
        ResponseEntity<Map<String, String>> response = handler.handleException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "Что-то пошло не так. Попробуйте позже");
    }
}
