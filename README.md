# GoatSports Gateway Server

Cổng vào duy nhất của mọi request từ web, mobile tới backend (Spring Cloud Gateway, WebFlux).
Gateway xác thực JWT, giới hạn tần suất, gắn correlation ID rồi định tuyến tới đúng microservice.

## Chức năng chính

- **Định tuyến theo tiền tố**: `/{tên-service}/**` → service tương ứng (bỏ tiền tố trước khi chuyển tiếp).
  Ví dụ `GET /venue-service/api/v1/venues` → venue-service `GET /api/v1/venues`.
  Có route WebSocket riêng cho `/notification-service/ws/**` và `/social-service/ws/**`.
- **Xác thực**: đọc JWT từ cookie `accessToken`, kiểm tra token đã bị thu hồi trong Redis
  (`RedisTokenBlacklistFilter`), lấy quyền người dùng từ auth-service (`AuthClient`).
- **Truyền danh tính xuống service**: `AuthenticationHeaderFilter` **xóa mọi header `X-User-*` do client tự gửi**,
  rồi gắn `X-User-*` và `X-Gateway-Secret` từ token đã xác thực. Các service phía sau chỉ tin header này khi có secret.
- **Rate limit** theo IP bằng Redis (`RateLimiterConfig`).
- **Correlation ID**: `CorrelationIdFilter` gắn `X-Correlation-Id` để lần vết một request qua nhiều service.
- **CORS** theo `CORS_ALLOWED_ORIGINS`; lỗi trả về định dạng chung qua `GlobalErrorWebExceptionHandler`;
  `/fallback/**` trả lỗi thân thiện khi service đích không phản hồi.

## Cấu trúc

```
src/main/java/iuh/fit/gateway/
├── GatewayApplication.java        # khai báo route
├── application/dto/               # DTO phản hồi (quyền người dùng, BaseResponse)
├── infrastructure/
│   ├── config/                    # bảo mật, CORS, rate limiter, Feign
│   ├── filter/                    # AuthenticationHeaderFilter, CorrelationIdFilter
│   ├── external/                  # AuthClient (Feign), Redis blacklist
│   └── exception/
└── presentation/controller/       # GatewayFallbackController
```

## Cấu hình

| Biến | Ý nghĩa |
|---|---|
| `SERVER_PORT` | Cổng gateway (mặc định triển khai: `7070`) |
| `*_SERVICE_URL`, `*_SERVICE_WS_URL` | Địa chỉ từng service đích |
| `JWT_SECRET` | Khóa ký JWT |
| `REDIS_URL` | Redis cho rate limit và danh sách token thu hồi |
| `CORS_ALLOWED_ORIGINS` | Danh sách origin frontend được phép |
| `EUREKA_*` | Đăng ký với Eureka (tùy chọn) |

Actuator chạy trên cổng quản trị `7090` (`health`, `info`, `prometheus`).

## Chạy và kiểm thử

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # cần src/main/resources/application-dev.properties (bị .gitignore)
mvn test
```

## CI/CD

`.gitlab-ci.yml` trên nhánh mặc định: chạy test → `jib:build` đẩy image lên Docker Hub → gọi Jenkins job
`GoatSports-CD` khởi động lại container trên VPS.
