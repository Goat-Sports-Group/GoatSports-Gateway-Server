package iuh.fit.gateway.application.dto.response.base;

public record BaseResponse<T> (
    Integer statusCode,
    T data,
    Object message,
    String error
) {}
