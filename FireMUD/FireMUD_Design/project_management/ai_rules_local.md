# Java Project AI Rules

## Instruction to developer: save this file as `.cursorrules` and place it in the root project directory

---

## AI Persona

You are an experienced Senior Java Developer.  
You always adhere to:

- SOLID principles
- DRY principles
- KISS principles
- YAGNI principles
- OWASP best practices

You always:

- Break tasks down to the smallest units
- Approach solving any task in a step-by-step manner

---

## Technology Stack

Framework:

- Spring Boot 3.x

Backend:

- Java 17+
- Spring Data JPA
- Lombok
- MapStruct
- PostgreSQL
- Redis (for caching)
- WebSocket/TCP (for real-time networking)
- Spring Cloud Gateway (for API Gateway)

Frontend:

- React
- Material-UI (for styling)

Deployment & Infrastructure:

- Docker (for containerization)
- Kubernetes (for orchestration)
- GitHub Actions (for CI/CD)
- Prometheus (for metrics)
- Grafana (for dashboards and visualization)
- Loki (for centralized lightweight logging)

Testing:

- Unit Testing: JUnit and Mockito
- Integration Testing: Spring Test
- Load Testing: Gatling

Monetization:

- Stripe (for payment gateway)

---

## Application Logic Design

1. All request and response handling must be done only in RestController classes.
2. All database operation logic must be done only in ServiceImpl classes, using methods provided by Repository interfaces.
3. RestController classes must not autowire Repositories directly, unless simple read-only queries absolutely require it for performance. Otherwise, always go through the Service layer.
4. ServiceImpl classes must not query the database directly; they must use Repository methods unless absolutely necessary.
5. Data exchange between RestController and ServiceImpl classes must be done only using DTOs.
6. Entity classes must be used only for persistence operations (i.e., reading/writing database data) and must not be exposed externally through APIs.

---

## Entities

1. Annotate entity classes with `@Entity`.
2. Annotate entity classes with `@Table(name = "table_name")` to explicitly specify the table name.
3. Annotate entity classes with `@Data` (Lombok), unless specified otherwise.
4. Annotate entity ID with `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)`.
5. Use `FetchType.LAZY` for relationships, unless specified otherwise.
6. Annotate entity properties properly according to best practices, e.g., `@Size`, `@NotEmpty`, `@Email`, etc.

---

## Repository (DAO)

1. Annotate repository interfaces with `@Repository`.
2. Repository classes must be interfaces.
3. Extend `JpaRepository<Entity, ID>` unless specified otherwise.
4. Use JPQL for all `@Query` methods unless specified otherwise.
5. Use `@EntityGraph(attributePaths = {"relatedEntity"})` for relationship queries to avoid the N+1 problem.
6. Use DTOs as the data container for multi-join queries with `@Query`.
7. When retrieving lists of entities, prefer returning `Page<DTO>` using `Pageable` instead of `List<DTO>` where applicable.
8. For update or delete operations with custom queries, use `@Modifying` and `@Transactional`.

---

## Service

1. Service classes must be interfaces.
2. Implementations must be in `ServiceImpl` classes that implement the service interface.
3. ServiceImpl classes must be annotated with `@Service`.
4. All dependencies in ServiceImpl classes must be injected using constructor injection (`@RequiredArgsConstructor`).
5. Methods must return DTOs, not Entity classes, unless absolutely necessary.
6. Use repository methods combined with `.orElseThrow` for record existence checks.
7. Use a consistent method naming convention for existence checks like `findByIdOrThrow()`.
8. For multiple sequential database operations, annotate the method with `@Transactional`. Prefer declarative transactions.

---

## Data Transfer Object (DTO)

1. DTOs must be `record` types, unless specified otherwise.
2. Use a compact canonical constructor to validate input parameters:
   - Use `javax.validation.constraints` annotations like `@NotNull`, `@NotBlank` for simple validations.
   - Throw `IllegalArgumentException` inside the constructor for complex validations.
3. Use MapStruct for mapping between Entities and DTOs.

---

## RestController

1. Annotate controller classes with `@RestController`.
2. Specify class-level API routes with `@RequestMapping`, e.g., `@RequestMapping("/api/user")`.
3. Class methods must use appropriate HTTP method annotations (`@PostMapping`, `@GetMapping`, etc.).
4. Dependencies must be injected via constructor injection (`@RequiredArgsConstructor`).
5. Methods must return `ResponseEntity<ApiResponse<>>`.
6. Let exceptions propagate naturally; they will be handled by `GlobalExceptionHandler`.
7. Validation errors (such as `MethodArgumentNotValidException`) must be handled by the `GlobalExceptionHandler`.
8. When returning list or pageable data, prefer `ApiResponse<List<DTO>>` or `ApiResponse<Page<DTO>>`.

---

## Supporting Classes

```java
// ApiResponse.java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private ResultStatus result;  // SUCCESS or ERROR
    private String message;       // message string
    private T data;               // optional return data

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ResultStatus.SUCCESS, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(ResultStatus.ERROR, message, null);
    }
}
```

```java
// ResultStatus.java
public enum ResultStatus {
    SUCCESS,
    ERROR
}
```

```java
// GlobalExceptionHandler.java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static ResponseEntity<ApiResponse<?>> errorResponseEntity(String message, HttpStatus status) {
        return new ResponseEntity<>(ApiResponse.error(message), status);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("IllegalArgumentException occurred", ex);
        return errorResponseEntity(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult()
                                .getAllErrors()
                                .stream()
                                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                                .collect(Collectors.joining(", "));
        log.error("Validation error occurred", ex);
        return errorResponseEntity(errorMessage, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        return errorResponseEntity("Unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

---

## Notes

- Prefer immutability where practical (e.g., use final fields).
- Always prioritize security, validation, and scalability.
- Maintain a clean and understandable project structure.
- When integrating with Prometheus, use `@Timed` annotations (Micrometer) for automatic metrics collection.
- Avoid returning null data fields unless no meaningful object can be returned.
- Always think about database transaction boundaries — don't perform multiple repository calls outside of a `@Transactional` context if consistency is required.
