package com.gateway.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayResponse<T> {
    private int code;
    private String status;
    private String message;
    private T data;
    private String correlationId;
    private LocalDateTime timestamp;

    public static <T> GatewayResponse<T> success(T data, String correlationId) {
        return GatewayResponse.<T>builder().code(0).status("SUCCESS").message("Request processed")
                .data(data).correlationId(correlationId).timestamp(LocalDateTime.now()).build();
    }

    public static <T> GatewayResponse<T> error(int code, String message, String correlationId) {
        return GatewayResponse.<T>builder().code(code).status("ERROR").message(message)
                .correlationId(correlationId).timestamp(LocalDateTime.now()).build();
    }
}
