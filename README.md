# Ride Hailing Backend

A **modular monolith** ride-hailing platform: one Spring Boot application, nine
independently-owned MySQL schemas, Redis for live driver geography and hot-path
coordination.

> **Want to run a ride end to end?** See **[WORKFLOW.md](WORKFLOW.md)** — driver
> login, rider login, booking, the bill explained line by line, start, in
> progress, complete, plus the audit trail and every alternate flow.

> **Reading this as an AI agent picking up the work?** Read
> [Section 15 — Rules for future changes](#15-rules-for-future-changes) first,
> then [Section 3 — Module map](#3-module-map) and
> [Section 4 — Cross-module contracts](#4-cross-module-contracts). Those three
> sections contain everything needed to make a correct change without re-reading
> the whole codebase.

---

## Table of contents

1. [Tech stack](#1-tech-stack)
2. [Running it](#2-running-it)
3. [Module map](#3-module-map)
4. [Cross-module contracts](#4-cross-module-contracts)
5. [Database architecture](#5-database-architecture)
6. [Master data and seed data](#6-master-data-and-seed-data)
7. [Demo accounts](#7-demo-accounts)
8. [Security: authentication, RBAC, ownership](#8-security-authentication-rbac-ownership)
9. [Redis usage](#9-redis-usage)
10. [The booking flow and concurrency model](#10-the-booking-flow-and-concurrency-model)
11. [Pricing](#11-pricing)
12. [Ride lifecycle](#12-ride-lifecycle)
13. [Cross-cutting: request tracking, idempotency, rate limiting, audit, errors](#13-cross-cutting-concerns)
14. [API reference](#14-api-reference)
15. [Rules for future changes](#15-rules-for-future-changes)
16. [Not implemented yet](#16-not-implemented-yet)
17. [Assumptions](#17-assumptions)
18. [What I would do differently with more time](#18-what-i-would-do-differently-with-more-time)
19. [How AI was used](#19-how-ai-was-used)

---

## 1. Tech stack

| Concern | Choice |
|---|---|
| Language | Java 17 (`maven.compiler.release=17`; builds fine on a JDK 24 toolchain) |
| Framework | Spring Boot 3.5.6 |
| Build | Maven (`./mvnw`) |
| Persistence | Spring Data JPA / Hibernate 6, MySQL 8 |
| Schema migrations | Flyway (`flyway-core` + `flyway-mysql`) |
| Cache / geo / limits | Redis 7 (Lettuce, `StringRedisTemplate`) |
| Security | Spring Security, stateless JWT (HS256, jjwt 0.12.6), BCrypt cost 10 |
| Boilerplate | Lombok |

**Deliberately absent**, and they must stay absent: Swagger/OpenAPI, JUnit/Mockito/
Testcontainers, Kafka/RabbitMQ, Elasticsearch, Kubernetes, GraphQL, CQRS, event
sourcing, service mesh. Testing is a separate later phase.

Root package is `com.ridehailing`. Never `com.example.*`.

---

## 2. Running it

```bash
# 1. Infrastructure (MySQL 8 + Redis 7)
docker compose up -d

# 2. Application (Flyway migrates all nine schemas on startup)
./mvnw spring-boot:run
```

The app listens on `http://localhost:8080`.

### Configuration split

This is a rule, not a preference:

* **Infrastructure config** lives in `application.yml` / environment variables and
  is *never* database driven — datasource URL, Redis host, JWT secret, server port,
  connection pool sizes, cache TTLs.
* **Business config** lives in `configuration_schema.configurations` and is
  changeable at runtime through the API — search radius, matching strategy, active
  pricing rule, surge toggle, rate limits, TTLs. See [Section 6](#configuration).

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3306` / `ridehailing` | MySQL |
| `DB_USERNAME` / `DB_PASSWORD` | `ridehailing` / `ridehailing` | MySQL credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `JWT_SECRET` | dev placeholder | **Must be ≥ 32 bytes. Override in any real environment.** |
| `JWT_EXPIRATION_SECONDS` | `3600` | Access token lifetime |
| `CONFIG_CACHE_TTL` | `300` | Redis TTL for cached configuration values |

`docker/mysql-init/01-grants.sql` grants the app user rights across all schemas,
because the application owns eight databases rather than one.

`spring.jpa.hibernate.ddl-auto` is **`none`**. Flyway is the only thing that
creates or alters a table. Never turn this to `update`.

---

## 3. Module map

Each module owns exactly one schema and is the only code allowed to read or write
that schema's tables. Everything else goes through the module's published service.

```
com.ridehailing
├── controller/            ← ALL REST controllers live here, and only here
├── common/                ← shared kernel: no module-specific logic
│   ├── api/               ApiResponse, ApiError, PageResponse
│   ├── domain/            CarType, CarTypePolicy
│   ├── error/             ErrorCode (code → HTTP status)
│   ├── exception/         BusinessException, RateLimitExceededException, GlobalExceptionHandler
│   ├── geo/               GeoUtils (haversine)
│   ├── jpa/               BaseEntity (audit columns + @Version), JpaAuditingConfig
│   ├── util/              Money (the only place money is rounded)
│   └── web/               RequestContext, RequestIdFilter
├── config/                WebMvcConfig (interceptor registration)
├── security/              JWT, SecurityConfig, AuthPrincipal, CurrentUser, AccessGuard, AuthService
├── ratelimit/             RateLimiter abstraction, RedisRateLimiter, policies, @RateLimited, interceptor
├── redis/                 RedisKeys, DistributedLockService
├── rbac/          →  rbac_schema           roles, permissions, role_permissions
├── user/          →  user_schema           users (the single identity store)
├── driver/        →  driver_schema         drivers, vehicles  (+ Redis GEO)
├── ride/          →  ride_schema           rides, idempotency_keys
├── payment/       →  payment_schema        payments  (strategy per method + partner port)
├── pricing/       →  pricing_schema        pricing_rules, tiers, car-type multipliers
├── coupon/        →  coupon_schema         coupons, coupon_redemptions
├── audit/         →  audit_schema          audit_logs
└── configuration/ →  configuration_schema  configurations
```

Every module follows the same internal shape:

```
<module>/
├── entity/      JPA entities (extend BaseEntity), enums
├── repository/  Spring Data repositories — the only DB access for this schema
├── service/     business logic and transaction boundaries
├── dto/         request/response records for this module's API surface
└── api/         records other modules are allowed to consume
```

### Allowed dependency directions

```
controller ──▶ every module's service        (thin, no logic)

ride ──▶ driver        (find candidates, reserve/release a driver)
ride ──▶ pricing       (fare quote)
ride ──▶ coupon        (redeem / reverse)
ride ──▶ payment       (collect the fare / the cancellation fee)
ride ──▶ user          (existence check)
pricing ──▶ coupon     (apply discount inside the quote)
driver ──▶ user        (a driver profile is backed by a user account)
security ──▶ user, rbac
every module ──▶ common, configuration, audit, redis, ratelimit
```

There are **no cycles**. `coupon`, `payment`, `configuration`, `audit`, `rbac` and
`user` depend on no other business module — `payment` knows a ride only by its id,
which is what keeps the `ride ──▶ payment` edge one-directional. If you need a new edge, question it first.

### Why controllers are centralised but logic is not

All controllers sit in `com.ridehailing.controller` (a stated project convention),
but each one delegates straight into its owning module:

```
RideController → ride.service.RideBookingService → ride.repository.RideRepository → ride_schema.rides
```

A controller never touches another module's repository or entity.

---

## 4. Cross-module contracts

These are the exact types one module may consume from another. **Changing a
signature here is a breaking change across modules — grep for callers first.**

```java
// --- driver module ---------------------------------------------------------
package com.ridehailing.driver.api;
record NearbyDriver(Long driverId, double distanceKm) {}
record AvailableDriver(Long driverId, Long vehicleId, CarType carType, BigDecimal rating, long version) {}
record DriverSummary(Long id, String fullName, String phone, BigDecimal rating) {}

package com.ridehailing.driver.service;
class DriverLocationService {                    // the ONLY class allowed to touch Redis GEO
    void updateLocation(Long driverId, BigDecimal latitude, BigDecimal longitude);
    List<NearbyDriver> findNearby(BigDecimal latitude, BigDecimal longitude, double radiusKm, int limit);
    void removeLocation(Long driverId);
}
class DriverService {
    DriverResponse register(CreateDriverRequest request);   // creates the user account too
    DriverResponse getById(Long driverId);
    DriverSummary  getSummary(Long driverId);
    Optional<Long> findDriverIdByUserId(Long userId);
    Long           requireDriverIdForUser(Long userId);
    void           requireOwnership(AuthPrincipal principal, Long driverId);
    DriverResponse updateStatus(Long driverId, DriverStatus status);
}
class DriverReservationService {
    List<AvailableDriver> findAvailableCandidates(Collection<Long> driverIds, Collection<CarType> carTypes);
    boolean reserve(Long driverId, long expectedVersion);   // atomic CAS; true = you won the driver
    boolean release(Long driverId);                          // BUSY → AVAILABLE
    boolean completeRide(Long driverId);                     // BUSY → AVAILABLE, total_rides + 1
}

// --- pricing module --------------------------------------------------------
package com.ridehailing.pricing.api;
record FareQuote(String pricingRuleCode, BigDecimal distanceKm, BigDecimal distanceFare,
                 BigDecimal carTypeMultiplier, BigDecimal surgeMultiplier, BigDecimal minimumFare,
                 boolean minimumFareApplied, BigDecimal fareBeforeDiscount, String couponCode,
                 Long couponId, BigDecimal discountAmount, BigDecimal totalFare,
                 Map<String, Object> breakdown) {}

package com.ridehailing.pricing.service;
class PricingService { FareQuote quote(BigDecimal distanceKm, CarType requestedCarType, String couponCode, Long userId); }

// --- coupon module ---------------------------------------------------------
package com.ridehailing.coupon.api;
record CouponDiscount(Long couponId, String code, BigDecimal discountAmount) {}

package com.ridehailing.coupon.service;
class CouponService {
    CouponDiscount           evaluate(String code, BigDecimal fareAmount, Long userId);  // throws on invalid
    void                     redeem(Long couponId, Long userId, Long rideId, BigDecimal discountAmount);
    void                     reverse(Long rideId);
    CouponValidationResponse validate(String code, BigDecimal fareAmount, Long userId);  // never throws
    CouponResponse           create(CreateCouponRequest request);
    void                     deactivate(Long couponId);
}

// --- payment module --------------------------------------------------------
package com.ridehailing.payment.api;
record PaymentRequest(Long rideId, Long userId, Long driverId, BigDecimal amount, PaymentPurpose purpose) {}
record PaymentSummary(Long id, Long rideId, PaymentPurpose purpose, PaymentMethod method, PaymentStatus status,
                      BigDecimal amount, String reference, String failureReason, Instant collectedAt) {}

package com.ridehailing.payment.service;
class PaymentService {
    PaymentSummary           collect(PaymentMethod method, PaymentRequest request);  // idempotent per (ride, purpose)
    PaymentSummary           retry(PaymentRequest request, PaymentMethod override);  // 409 if already SUCCESS
    List<PaymentSummary>     findByRide(Long rideId);
    Optional<PaymentSummary> findLatest(Long rideId, PaymentPurpose purpose);
}

// --- user module -----------------------------------------------------------
package com.ridehailing.user.service;
class UserService {
    UserResponse register(CreateUserRequest request, UserRole role);   // role is set by the server
    UserResponse getById(Long userId);
    Optional<UserCredentials> findCredentialsByEmail(String email);    // for the security layer only
    void requireExists(Long userId);
}

// --- configuration module --------------------------------------------------
package com.ridehailing.configuration.service;
class ConfigurationService {
    String     getString(String key);   String     getString(String key, String fallback);
    int        getInt(String key);      int        getInt(String key, int fallback);
    BigDecimal getDecimal(String key);  BigDecimal getDecimal(String key, BigDecimal fallback);
    boolean    getBoolean(String key);  boolean    getBoolean(String key, boolean fallback);
    ConfigurationResponse update(String key, String value);
}

// --- audit module ----------------------------------------------------------
package com.ridehailing.audit.service;
class AuditService {
    void record(String entityType, Object entityId, String action, Object oldValue, Object newValue);
}
```

---

## 5. Database architecture

### The schema-per-module rule

Nine schemas, one per module. **No foreign key ever crosses a schema boundary.**
`ride_schema.rides.user_id` is a plain `BIGINT` with a comment saying what it
points at; there is no FK to `user_schema.users`. Within a schema, real FKs are
used (`vehicles.driver_id → drivers.id`, `pricing_distance_tiers.pricing_rule_id →
pricing_rules.id`, `coupon_redemptions.coupon_id → coupons.id`).

That constraint is what makes a module extractable later: cut the schema out, put
it behind an HTTP/gRPC boundary, and nothing in the database has to be untangled.

**JPA note:** MySQL has no schemas, only catalogs. Entities therefore use
`@Table(name = "...", catalog = "ride_schema")`. Using `schema = ...` silently does
nothing on MySQL — this is a real trap.

### Every business entity carries

`created_at`, `created_by`, `updated_at`, `updated_by`, `version` — supplied by
`common.jpa.BaseEntity` with Spring Data auditing (`@CreatedDate`, `@CreatedBy`,
`@LastModifiedDate`, `@LastModifiedBy`, `@Version`). `created_by`/`updated_by` come
from `CurrentUser.actorName()` (the principal's email, or `SYSTEM`).

`audit_schema.audit_logs` deliberately does *not* extend `BaseEntity`: an audit row
is append-only, so a version column and an "updated by" would be meaningless.

### Tables

| Schema | Table | Notes |
|---|---|---|
| `rbac_schema` | `roles` | unique `code` |
| | `permissions` | unique `code`, unique `(resource, action)` |
| | `role_permissions` | PK `(role_id, permission_id)`, FKs within schema |
| `user_schema` | `users` | unique `email`, unique `phone`, CHECK on `role` and `status` |
| `driver_schema` | `drivers` | unique `user_id`/`phone`/`license_number`, index on `status`, CHECK on status/rating |
| | `vehicles` | unique `registration_number`, index `(driver_id, active, car_type)`, FK to `drivers` |
| `ride_schema` | `rides` | full pricing snapshot, indexes `(user_id, requested_at DESC)` and `(driver_id, requested_at DESC)`, CHECKs on status/car types/amounts |
| | `idempotency_keys` | **unique `(user_id, idempotency_key)`** — the real duplicate-ride guard |
| `payment_schema` | `payments` | index `(ride_id, purpose, status)`, unique `reference`, CHECKs on purpose/method/status/amount; **no** unique `(ride_id, purpose)` — see [Payments](#payments) |
| `pricing_schema` | `pricing_rules` | unique `code` |
| | `pricing_distance_tiers` | unique `(pricing_rule_id, from_km)`, CHECK `to_km > from_km` |
| | `pricing_car_type_multipliers` | unique `(pricing_rule_id, car_type)` |
| | `pricing_zones` | unique `code`, index `(active, priority)`, FK to `pricing_rules`, CHECKs on lat/lng/radius |
| `coupon_schema` | `coupons` | unique `code`, CHECKs on type/status/values |
| | `coupon_redemptions` | **unique `ride_id`** — a ride can consume at most one coupon, ever |
| `audit_schema` | `audit_logs` | index `(entity_type, entity_id, changed_at)` and `(request_id)` |
| `configuration_schema` | `configurations` | unique `config_key`, CHECK on `value_type` |

Indexes were chosen from actual query patterns (ride history by user/driver newest
first; candidate resolution by driver status and active vehicle car type; audit
lookup by entity or by request). There are no speculative indexes.

### Flyway migrations

| File | Contents |
|---|---|
| `V1__create_schemas.sql` | the first eight schemas |
| `V2__create_rbac_tables.sql` | roles, permissions, role_permissions |
| `V3__create_user_tables.sql` | users |
| `V4__create_driver_tables.sql` | drivers, vehicles |
| `V5__create_ride_tables.sql` | rides, idempotency_keys |
| `V6__create_pricing_tables.sql` | pricing rules, tiers, car-type multipliers |
| `V7__create_coupon_tables.sql` | coupons, coupon_redemptions |
| `V8__create_audit_tables.sql` | audit_logs |
| `V9__create_configuration_tables.sql` | configurations |
| `V10__seed_rbac_master_data.sql` | roles, permissions, role→permission mapping |
| `V11__seed_pricing_master_data.sql` | STANDARD pricing rule, tiers, multipliers |
| `V12__seed_configuration_master_data.sql` | all business settings |
| `V13__seed_coupon_master_data.sql` | WELCOME10, FLAT50, FIRST100 |
| `V14__seed_demo_users_drivers_vehicles.sql` | demo users, drivers, vehicles |
| `V15__add_city_pricing_zones.sql` | `pricing_zones` table, `rides.pricing_zone_code`, Delhi/Pune rules + 3 city zones |
| `V18__create_payment_tables.sql` | `payment_schema` + `payments`, payment settings, `PAYMENT_READ` / `PAYMENT_COLLECT` |
| `V20__add_ride_pickup_distance.sql` | `rides.driver_pickup_distance_km`, `ride.pickup.average.speed.kmph` |

Required data is created by migrations, never by application startup code. The one
startup component that exists (`DriverLocationWarmupRunner`) only republishes
already-persisted coordinates into Redis, which is a cache-warming concern.

---

## 6. Master data and seed data

### RBAC

Roles: `ADMIN`, `USER`, `DRIVER`. Permissions are `RESOURCE_ACTION` codes.

| Resource | Permissions |
|---|---|
| users | `USER_CREATE`, `USER_READ`, `USER_UPDATE`, `USER_DELETE` |
| drivers | `DRIVER_CREATE`, `DRIVER_READ`, `DRIVER_UPDATE`, `DRIVER_LOCATION_UPDATE`, `DRIVER_STATUS_UPDATE` |
| vehicles | `VEHICLE_CREATE`, `VEHICLE_READ`, `VEHICLE_UPDATE` |
| rides | `RIDE_CREATE`, `RIDE_READ`, `RIDE_START`, `RIDE_COMPLETE`, `RIDE_CANCEL` |
| payments | `PAYMENT_READ`, `PAYMENT_COLLECT` |
| coupons | `COUPON_CREATE`, `COUPON_READ`, `COUPON_DELETE`, `COUPON_VALIDATE` |
| pricing | `PRICING_READ`, `PRICING_CREATE`, `PRICING_UPDATE` |
| configuration | `CONFIGURATION_READ`, `CONFIGURATION_UPDATE` |
| audit | `AUDIT_READ` |

Role → permission mapping:

* **ADMIN** — every permission.
* **USER** — `USER_READ`, `RIDE_CREATE`, `RIDE_READ`, `RIDE_CANCEL`, `COUPON_READ`, `COUPON_VALIDATE`, `PRICING_READ`, `PAYMENT_READ`.
* **DRIVER** — `DRIVER_READ`, `DRIVER_UPDATE`, `DRIVER_LOCATION_UPDATE`, `DRIVER_STATUS_UPDATE`, `VEHICLE_CREATE`, `VEHICLE_READ`, `VEHICLE_UPDATE`, `RIDE_READ`, `RIDE_START`, `RIDE_COMPLETE`, `RIDE_CANCEL`, `PAYMENT_READ`, `PAYMENT_COLLECT`.

A rider gets `PAYMENT_READ` but never `PAYMENT_COLLECT`: they may see what they were
charged, but reporting that money arrived is the driver's job.

Adding a role is pure data: insert a row and its mappings. No Java change.

### Pricing — `STANDARD`

| Setting | Value |
|---|---|
| Minimum fare | ₹50 |
| 0–2 km | ₹10 / km |
| 2–5 km | ₹8 / km |
| 5+ km | ₹5 / km |
| SEDAN multiplier | 1.0 |
| HATCHBACK multiplier | 0.9 |
| Surge multiplier | 1.00 (and `surge.enabled = false`) |

These numbers exist **only** in `pricing_schema`. A literal fare number anywhere in
Java is a defect.

### Configuration

| Key | Seeded value | Type |
|---|---|---|
| `ride.search.radius.km` | `5` | DECIMAL |
| `matching.strategy` | `NEAREST` | STRING |
| `pricing.active.rule` | `STANDARD` | STRING |
| `surge.enabled` | `false` | BOOLEAN |
| `driver.location.ttl.seconds` | `60` | INTEGER |
| `api.rate-limit.ride.max` | `10` | INTEGER |
| `api.rate-limit.ride.window.seconds` | `60` | INTEGER |
| `api.rate-limit.location.max` | `60` | INTEGER |
| `api.rate-limit.location.window.seconds` | `60` | INTEGER |
| `api.rate-limit.login.max` | `5` | INTEGER |
| `api.rate-limit.login.window.seconds` | `60` | INTEGER |
| `api.rate-limit.coupon.max` | `20` | INTEGER |
| `api.rate-limit.coupon.window.seconds` | `60` | INTEGER |
| `api.rate-limit.admin.max` | `30` | INTEGER |
| `api.rate-limit.admin.window.seconds` | `60` | INTEGER |
| `idempotency.ttl.seconds` | `86400` | INTEGER |
| `ride.cancellation.allowed.statuses` | `REQUESTED,DRIVER_ASSIGNED` | STRING |
| `ride.booking.lock.ttl.seconds` | `10` | INTEGER |

Constants for these keys are in `configuration.ConfigKeys` — always use them, never
a string literal.

### Coupons

| Code | Type | Value | Cap | Min ride | Per-user limit |
|---|---|---|---|---|---|
| `WELCOME10` | PERCENTAGE | 10% | ₹100 | ₹100 | unlimited |
| `FLAT50` | FLAT | ₹50 | — | ₹200 | unlimited |
| `FIRST100` | FLAT | ₹100 | — | ₹300 | 1 |

---

## 7. Demo accounts

Seeded by `V14`. Passwords are BCrypt (cost 10) hashes; **no plain-text password is
stored anywhere**. These are development credentials only.

### Users

| Email | Password | Role | Name |
|---|---|---|---|
| `admin@ridehailing.com` | `Admin@123` | ADMIN | Platform Admin |
| `rahul@ridehailing.com` | `User@123` | USER | Rahul Mehta |
| `priya@ridehailing.com` | `User@123` | USER | Priya Nair |
| `amit@ridehailing.com` | `User@123` | USER | Amit Joshi |

### Drivers

Every driver also has a login account with role `DRIVER` and password `Driver@123`.

| Driver | Login email | Vehicle | Reg. number | Coordinates | Distance from demo pickup |
|---|---|---|---|---|---|
| Raj Kumar | `raj.kumar@ridehailing.com` | SEDAN | `KA01AB1234` | 12.9750, 77.5990 | ≈ 0.6 km — **inside** radius |
| Amit Sharma | `amit.sharma@ridehailing.com` | HATCHBACK | `KA01CD5678` | 12.9820, 77.6050 | ≈ 1.6 km — **inside** radius |
| Vikram Singh | `vikram.singh@ridehailing.com` | SEDAN | `KA01EF9012` | 12.9352, 77.6245 | ≈ 5.2 km — outside radius |
| Neha Verma | `neha.verma@ridehailing.com` | HATCHBACK | `KA01GH3456` | 12.9141, 77.6788 | ≈ 10.5 km — outside radius |

Demo pickup point: **MG Road, Bangalore — 12.9716, 77.5946**. All drivers are
seeded `AVAILABLE` with a location snapshot, and `DriverLocationWarmupRunner`
publishes those coordinates into Redis GEO at startup.

### Scenarios these coordinates make demonstrable

1. **Nearest matching** — request a SEDAN from MG Road → Raj Kumar (0.6 km) wins over Vikram Singh.
2. **Search radius** — Vikram and Neha are outside the seeded 5 km radius and are never considered. Raise `ride.search.radius.km` to `12` through the API and they appear.
3. **Car-type upgrade** — request a HATCHBACK while Amit Sharma is BUSY → Raj Kumar's SEDAN is assigned, `requested_car_type = HATCHBACK`, `assigned_car_type = SEDAN`, **no extra charge**.
4. **No driver** — request from a far-away coordinate → `NO_DRIVER_IN_RADIUS`.
5. **Concurrency** — fire two bookings at the same driver simultaneously; exactly one gets `DRIVER_ASSIGNED`, the other rolls to the next candidate or fails with `NO_DRIVER_IN_RADIUS`.

### Getting a token

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"rahul@ridehailing.com","password":"User@123"}'
```

Then send `Authorization: Bearer <accessToken>` on every other call.

---

## 8. Security: authentication, RBAC, ownership

### Identity

`user_schema.users` is the **single identity store** for every principal. A driver
is a user with role `DRIVER` plus a profile row in `driver_schema.drivers` that
references `users.id`. This keeps one login flow instead of two credential stores.

### Token

Stateless HS256 JWT. Claims: `sub` (userId), `email`, `role`, `permissions`.
Permissions are baked into the token at login, so authorisation costs no database
round-trip. **Consequence:** a role's permission set changing does not affect
already-issued tokens until they expire (default 1 h).

`JwtAuthenticationFilter` converts the token into an `AuthPrincipal` whose Spring
authorities are the individual permission codes *plus* `ROLE_<role>`.

### Three independent checks — all are required

1. **Authentication** — is there a valid token? (`SecurityConfig`, stateless.)
2. **Permission** — `@PreAuthorize("hasAuthority('RIDE_READ')")` on the controller
   method. Role checks alone are never sufficient.
3. **Ownership** — enforced in the service layer via `AccessGuard` /
   `requireOwnership`. A `USER` with `RIDE_READ` may read **only their own** rides;
   a `DRIVER` with `DRIVER_LOCATION_UPDATE` may update **only their own** location.

**The identity always comes from the token, never from the request.** `POST /rides`
has no `userId` field by design; `PUT /drivers/{driverId}/location` verifies that
`{driverId}` belongs to the authenticated user before writing anything.

### Public endpoints

Only `POST /api/v1/auth/login`, `POST /api/v1/users` (rider signup) and
`POST /api/v1/drivers` (driver self-onboarding). Everything else requires a token.
On signup the server fixes the role; a caller cannot ask to be an ADMIN.

---

## 9. Redis usage

Redis is used where it is architecturally right, and is **never the source of truth**
for rides, driver persistent state, coupon usage, pricing or users. MySQL is.

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `driver:locations` | GEO (zset) | live driver positions; `GEOADD` / `GEOSEARCH` | none (entries pruned when stale) |
| `driver:location:fresh:{driverId}` | string | freshness marker for the GEO entry | `driver.location.ttl.seconds` (60) |
| `config:{key}` | string | cached business configuration value | `CONFIG_CACHE_TTL` (300 s) |
| `ratelimit:{POLICY}:{subject}` | counter | fixed-window rate limit | the policy's window |
| `lock:booking:user:{userId}` | string | short booking coordination lock | `ride.booking.lock.ttl.seconds` (10) |

All keys are built in `redis.RedisKeys`. Redis calls are confined to
`DriverLocationService`, `ConfigurationService`, `RedisRateLimiter` and
`DistributedLockService` — no Redis code is scattered through the modules.

### Why the freshness marker exists

GEO set members do not expire. A driver who stops sending GPS would otherwise stay
"nearby" forever. Each position write also sets a TTL'd marker key; a search result
whose marker is missing is treated as stale, removed from the GEO set, and skipped.

### Degradation behaviour

* **Configuration** — a Redis failure falls back to reading MySQL. Never an error.
* **Rate limiting** — fails **open** with an error log. Rate limiting protects
  capacity; refusing all traffic because the protection is down would be a
  self-inflicted outage.
* **Lock** — release failures are logged; the TTL cleans up.
* **Location** — failures propagate. A booking that cannot find candidates must not
  silently look like "no drivers available".

---

## 10. The booking flow and concurrency model

```
POST /api/v1/rides
   ↓  authentication (JWT → AuthPrincipal)
   ↓  rate limiting  (@RateLimited(RIDE_CREATE) → 10/min/user, DB configured)
   ↓  idempotency    (Idempotency-Key → replay or begin)
   ↓  request validation (coordinates, pickup ≠ drop, distance > 0)
   ↓  read configuration (search radius, matching strategy)
   ↓  Redis GEO search for nearby driver ids          ← candidates only
   ↓  MySQL: which of those are AVAILABLE with a matching active vehicle
   ↓  matching strategy ranks them
   ↓  ── transaction ──────────────────────────────
   ↓    atomic driver reservation (CAS, walk candidates)
   ↓    create ride (DRIVER_ASSIGNED) + pricing snapshot
   ↓    redeem coupon
   ↓  ── commit ───────────────────────────────────
   ↓  audit (ride created, driver AVAILABLE → BUSY)
   ↓  store idempotent response
   ↓  201 Created
```

**Redis finds candidates. MySQL decides who actually got the driver.**

### How far away the driver is

The booking response tells the rider where their driver is, which it previously did
not: `GEOSEARCH` already returned the distance and `DriverCandidate` already carried
it, but it was used for ranking and then thrown away. The winner's distance is now
persisted on the ride as `driver_pickup_distance_km` and returned on every ride read.

| Field | Meaning |
|---|---|
| `driverPickupDistanceKm` | `1.87` — km from the driver to the pickup |
| `estimatedPickupEtaMinutes` | `6` — that distance at `ride.pickup.average.speed.kmph` (default `20`), rounded **up** |

Two honest limits, both of which matter more than the feature:

* **It is straight-line, not road distance.** The value comes from the haversine
  distance in the Redis GEO index, so it always reads lower than the driver's real
  approach — 600 m away may be the wrong side of a one-way. The field is named
  `driverPickupDistanceKm`, not `distanceToPickup`, so nobody reads it as driving
  distance, and the ETA rounds up rather than down because an ETA that rounds down is
  a promise the driver cannot keep.
* **It is a snapshot taken at assignment, not a live figure.** The driver starts
  moving immediately afterwards, so the number ages from the moment it is written. A
  live distance belongs behind its own endpoint reading the driver's current Redis
  position on each call — a ride row is the wrong place for a value that changes every
  few seconds — and that endpoint is deliberately not built here.

### The reservation CAS

Two riders must never book the same driver. This is enforced by a guarded
conditional `UPDATE`, not by a read-modify-write:

```sql
UPDATE driver_schema.drivers
   SET status = 'BUSY', version = version + 1
 WHERE id = ? AND status = 'AVAILABLE' AND version = ?;
```

* affected rows = 1 → the driver is ours;
* affected rows = 0 → someone else took them; move to the next candidate.

`driver.setStatus(BUSY); repository.save(driver);` is **not** acceptable here and
must never be reintroduced.

### Locking strategy

| Problem | Mechanism | Why |
|---|---|---|
| Driver reservation | atomic conditional UPDATE + `@Version` | lock-free, exactly one winner, no deadlock |
| Ride state transitions | optimistic (`@Version` on `Ride`) | conflicts are rare; a retry is cheap |
| Coupon consumption | guarded atomic UPDATE (`used_count < usage_limit`) | simpler and safer than a pessimistic lock |
| Configuration edits | optimistic (`@Version`) | two admins editing one key |
| Duplicate booking bursts | Redis lock, 10 s TTL, unique token, safe release | *coordination only* — never the correctness guarantee |

### Transaction discipline

The transaction covers **driver reservation + ride creation + coupon redemption**
and nothing else. Redis calls, the GEO search and the pricing quote all happen
*before* it opens. Auditing happens *after* it commits. No transaction is ever held
across a network call.

---

## 11. Pricing

Fares are computed only in `pricing.service.PricingService` — never in
`RideService`. The steps, in order:

1. Load the active rule (`pricing.active.rule` → `STANDARD`).
2. Sum the distance tiers.
3. Apply the car-type multiplier for the **requested** car type.
4. Apply surge, if `surge.enabled` is true.
5. Apply the minimum fare (`max(computed, minimumFare)`).
6. Apply the coupon.
7. Return a detailed breakdown.

Worked example — **6 km, SEDAN, no coupon**, seeded rule:

```
0–2 km : 2 × 10 = 20
2–5 km : 3 ×  8 = 24
5–6 km : 1 ×  5 =  5
                 ---
distance fare    49.00
× SEDAN 1.0   =  49.00
× surge 1.0   =  49.00
max(49, 50)   =  50.00   ← minimum fare applied
total            50.00
```

Same trip as a **HATCHBACK**: `49.00 × 0.9 = 44.10` → minimum fare → **50.00**.

### The pricing snapshot

Every ride stores its own `distance_fare`, `car_type_multiplier`, `surge_multiplier`,
`minimum_fare`, `fare_before_discount`, `discount_amount`, `total_fare`,
`pricing_rule_code` and the full JSON `fare_breakdown`. **Changing a pricing rule
never changes the fare of a historical ride.**

### City based pricing (zones)

Fares depend on **where the ride starts**. A zone is a circle — centre, radius,
priority — that maps a pickup point to a pricing rule:

| Zone | Centre | Radius | Rule | Min fare | ₹/km tiers |
|---|---|---|---|---|---|
| `DELHI` | 28.6139, 77.2090 | 50 km | `DELHI_STANDARD` | ₹60 | 12 / 9 / 6 |
| `PUNE` | 18.5204, 73.8567 | 30 km | `PUNE_STANDARD` | ₹45 | 9 / 7 / 5 |
| `BANGALORE` | 12.9716, 77.5946 | 40 km | `STANDARD` | ₹50 | 10 / 8 / 5 |

A pickup outside every zone falls back to the global `pricing.active.rule`.

Design decisions worth keeping:

* **The pickup decides the zone, never the drop.** The pickup is fixed for the
  whole life of a ride, so the fare cannot shift under the rider mid-trip.
* **A circle, not a polygon.** No `GEOMETRY` column, no spatial index, no PostGIS
  equivalent to maintain — and city-level pricing does not need street-accurate
  borders. Swap in polygons only when a real boundary dispute demands it.
* **Priority resolves overlaps.** A small airport zone at priority 200 nested
  inside a city zone at 100 wins automatically. Zones are returned ordered by
  priority then ascending radius, so the first containing zone is the most
  specific one — no extra comparison, no Java change to add one.
* **Every active zone is scanned in memory per quote.** One row per city makes
  this cheaper than a spatial query. Revisit if zones ever reach the hundreds.
* **A zone pointing at a deactivated rule falls back to the global default** with
  a warning, rather than failing the booking. Losing a ride is worse than pricing
  it at the platform default; the log makes the misconfiguration visible.
* **`rides.pricing_zone_code` is part of the snapshot.** Redraw or delete a zone
  later — historical fares stay exactly as charged.

Adding a city is one API call, no deployment:

```bash
POST /api/v1/pricing/zones
{"code":"MUMBAI","name":"Mumbai","pricingRuleCode":"MUMBAI_STANDARD",
 "centreLatitude":19.0760,"centreLongitude":72.8777,
 "radiusKm":45,"priority":100,"active":true}
```

**Operational note:** to book in a city you also need a driver there. The Redis
GEO search runs *before* pricing, so a pickup with no nearby driver returns
`NO_DRIVER_IN_RADIUS` and the zone's rates are never reached.

### Car-type upgrade

Requested HATCHBACK with no hatchback available → a SEDAN is assigned.
`requested_car_type = HATCHBACK`, `assigned_car_type = SEDAN`, and the rider pays
the **hatchback** price. This rule lives in exactly one place —
`common.domain.CarTypePolicy.acceptableFor(...)` — and is used by both matching and
pricing. Do not re-implement it anywhere.

### Matching strategy

`DriverMatchingStrategy` with `NearestDriverMatchingStrategy` (`"NEAREST"`) today.
The implementation is selected at runtime from `matching.strategy`. Adding
`HighestRatedDriverMatchingStrategy` means adding one `@Component` and one
configuration value — no `if/else` in the booking service.

### Fare estimate — pricing without booking

`POST /pricing/quote` prices a trip nobody has booked. `PRICING_READ` is enough, and
the `USER` role already holds it, so riders can call it directly.

Without it, a rider only learned the price from the `POST /rides` response — after a
driver had been claimed, marked `BUSY`, and the coupon redeemed. **Price discovery
cost a driver reservation**, and after the cancellation fee was added it could cost
the rider ₹30 as well. It also meant a rider could not compare car types, could not
check a coupon without a fare to type in by hand, and saw surge only once already
committed.

`FareEstimateService` sits in front of `PricingService.quote(...)`, which is already
`@Transactional(readOnly = true)` and side-effect free — no driver reserved, no
coupon consumed, no ride row written. Verified by comparing `COUNT(*)` on `rides`
before and after.

Two decisions worth naming:

* **A rejected coupon is data, not an error.** `CouponService.evaluate` throws for an
  unusable coupon, and `quote()` calls it directly, so passing a bad coupon to
  `quote()` throws. An estimate must never fail because of a coupon — the rider asked
  what the *trip* costs. So the five coupon rejections (`COUPON_NOT_FOUND`,
  `_INACTIVE`, `_EXPIRED`, `_NOT_APPLICABLE`, `_EXHAUSTED`) are caught, the trip is
  quoted again without the coupon, and the rejection is returned as
  `couponApplicable: false` plus the reason on a **200**. Anything else `quote()` can
  raise — an unpriceable trip, a missing rule — is a genuine fault and still surfaces
  as an error.
* **`FareEstimateService` is deliberately *not* `@Transactional`.** If it were, the
  first attempt's rejection would mark that transaction rollback-only, and the commit
  after the successful second attempt would die with `UnexpectedRollbackException`.
  Calling through the injected `PricingService` proxy gives each attempt its own
  read-only transaction — the same way `RideBookingService` already calls `quote()`.

It has its own rate limit (`PRICING_QUOTE`, 60/min/user) rather than reusing the
booking limit of 10/min: a real client calls this on every map drag.

Verified end to end: a 28 km trip with `WELCOME10` estimates **143.13**, and booking
the identical trip charges **143.13**. An estimate that does not match the booking
that follows it is worse than no estimate.

---

## 12. Ride lifecycle

```
REQUESTED ──▶ DRIVER_ASSIGNED ──▶ STARTED ──▶ COMPLETED
    │                │
    └──▶ CANCELLED ◀─┘
```

Legal transitions, and nothing else:

| From | To |
|---|---|
| `REQUESTED` | `DRIVER_ASSIGNED`, `CANCELLED` |
| `DRIVER_ASSIGNED` | `STARTED`, `CANCELLED` |
| `STARTED` | `COMPLETED` |
| `COMPLETED`, `CANCELLED` | *(terminal)* |

Anything else is rejected with `INVALID_RIDE_STATE_TRANSITION` (409). The rules live
in `ride.RideStateMachine` and every transition goes through it.

Side effects:

* **start** — assigned driver or ADMIN only; sets `started_at`.
* **complete** — assigned driver or ADMIN; sets `completed_at`; driver goes
  `BUSY → AVAILABLE` and `total_rides` increments atomically; **collects the fare**
  (below), so this endpoint now requires a body naming the payment method.
* **cancel** — rider, assigned driver, or ADMIN; releases the driver back to
  `AVAILABLE`, reverses the coupon redemption so the rider gets the use back, and
  decides a cancellation fee (below). `cancelled_by` is derived from the
  authenticated role, never from the request body.

Both **complete** and **cancel** also copy the driver's current Redis position into
`drivers.last_known_*`, so a Redis restart warms the GEO set from where each driver
actually finished rather than from wherever they last changed status.

### Cancellation fee

`ride.CancellationFeePolicy` is the only place that decides whether a cancellation
costs anything. It is a pure function — no clock, no database, no Spring context —
so the rule is readable and testable on its own.

| Input | Source |
|---|---|
| who cancelled | `cancelled_by`, derived from the token |
| status before cancelling | the ride's status when `cancel` was called |
| how long the driver was held | `now − assigned_at` |
| fee amount | `ride.cancellation.fee.amount` (default `30.00`) |
| grace window | `ride.cancellation.fee.grace.seconds` (default `120`) |

The fee is charged only when **all** of these hold; otherwise it is `0.00`:

1. `cancelled_by = USER`. A driver or ADMIN cancellation is always free — a rider
   must not pay for someone else changing their mind.
2. The ride was `DRIVER_ASSIGNED`. Before assignment nobody was dispatched, so
   there is no cost to recover.
3. `now − assigned_at >= grace`. An immediate change of mind is free.

Decisions worth naming, none of which the requirements specify:

* **Flat, not a percentage of the fare.** What is recovered is the driver's wasted
  approach to the pickup, which does not grow with the length of the trip the
  rider abandoned.
* **`total_fare` is never touched.** It stays the quoted price of a trip that did
  not happen; the fee is its own `rides.cancellation_fee` column. Overwriting the
  fare would destroy the record of what the rider had agreed to pay, and the
  pricing snapshot is documented as immutable after creation.
* **Stored, not derived on read.** Raising the configured amount next month must
  not change what a rider was charged last month — the same reasoning as the fare
  snapshot.
* **A column, not a table.** One cancellation per ride means one fee, so this is
  strictly 1:1 with the ride row. It becomes a row in a `ride_charges` ledger the
  day a second money line item exists (waiting charge, toll, tip, commission,
  driver payout) — see the earnings gap in section 16.
* **Zero versus null.** `null` means the ride was never cancelled; `0.00` means it
  was cancelled and the policy decided it was free. Worth keeping distinct for
  anyone auditing later.

`cancellationFee` is exposed at the top level of `RideResponse` rather than inside
`FareSummary`, because `FareSummary` is the pricing snapshot and is contractually
frozen at creation, whereas this value is decided at cancellation time.

A non-zero fee is written to the audit trail; a free cancellation is not, because
it is not a financial event.

Verified against a running instance: rider inside grace → `0.00`; driver cancel past
grace → `0.00`; rider past grace → `30.00`; `total_fare` unchanged at `50.00` in all
three.

### Payments

Money is collected **at completion, not at booking**, because that is the first
moment the method is a fact rather than an intention — a rider who chose UPI on the
map may still hand over notes, and only the driver knows which happened.

```
POST /api/v1/rides/{id}/complete   {"paymentMethod": "UPI"}
   ↓  RideLifecycleService.complete  (assigned driver or ADMIN)
   ↓  ride → COMPLETED, driver BUSY → AVAILABLE
   ↓  PaymentStrategyFactory.forMethod(UPI) → UpiPaymentStrategy
   ↓  PaymentPartner.charge(...)  → MockPaymentPartner  (synchronous, no network)
   ↓  payments row: SUCCESS + reference, or FAILED + reason
   ↓  audit PAYMENT_COLLECTED / PAYMENT_FAILED against the ride
```

**Five methods**, each with its own `PaymentStrategy` bean:
`CASH`, `UPI`, `CARD`, `WALLET`, `NETBANKING`. Adding a sixth is an enum constant
plus one `@Component` — `PaymentStrategyFactory` refuses to start the application if
any `PaymentMethod` has no strategy, or if two claim the same one, so the enum and
the strategies cannot drift apart silently. That startup check is the whole reason a
new method cannot become a 500 in front of a rider.

**`CASH` is the one method with no payment partner.** The driver already has the
notes in hand, so there is nothing to authorise; the other four delegate to the
`PaymentPartner` port. That asymmetry is why strategy and partner are two
abstractions rather than one — collapsing them would need a no-op partner for cash.

**The partner is mocked, deliberately.** `PaymentPartner` is the outbound port a real
PSP (Razorpay, PayU, Cashfree) will implement; `MockPaymentPartner` is the only
implementation today and does no network I/O, no sleeping and nothing random.
Integrating a real one means adding one `@Component` — no strategy, service or
controller changes. It is intentionally **not** `@Primary`: when a second partner
appears, injection becomes ambiguous and startup fails, which is exactly when the
`payment.partner` config key and a resolver (mirroring
`DriverMatchingStrategyResolver`) should be written. A `@Primary` mock would quietly
keep serving production traffic instead.

**A failed payment never blocks or rolls back the completion.** The ride happened and
the driver is free; punishing that with a rollback would trap a driver against a trip
they have already finished. So the ride completes, the row is written `FAILED`, the
reason is visible on `RideResponse.payment`, and the retry endpoint chases the money.
This is only safe because a decline is a return value rather than an exception — an
infrastructure failure (a dead database, not a declined card) still rolls the whole
transaction back, which is right.

**Collection is idempotent.** A replayed completion must never double-charge. The
constraint wanted is *at most one `SUCCESS` per `(ride_id, purpose)`*, which MySQL
cannot express — it has no partial unique index, and a plain unique key would also
forbid the failed attempt a retry has to sit beside. `PaymentService` enforces it
instead: an existing `SUCCESS` row is returned unchanged and no strategy is asked
twice. Concurrent completions are stopped a layer up by the ride's `@Version`.

**The cancellation fee flows through the same module**, distinguished only by
`purpose = CANCELLATION_FEE`, and only when the computed fee is greater than zero — a
free cancellation leaves no payment row at all. It cannot be cash (rider and driver
never met), so the method comes from `payment.cancellation.fee.method` rather than
from the till.

**Simulated failures are configuration, never random**, so the decline path can be
demonstrated on request and asserted on later:

```bash
# Make every card payment decline, reproducibly
curl -s -X PUT http://localhost:8080/api/v1/configurations/payment.simulated.failure.methods \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"value": "CARD"}'
```

| Key | Default | Meaning |
|---|---|---|
| `payment.simulated.failure.methods` | `NONE` | CSV of methods the mock partner declines. Case- and whitespace-insensitive; an unknown name is logged and ignored, never fatal. `NONE` rather than `""` because a blank `STRING` is rejected on update, which would make a cleared row unrecoverable. |
| `payment.cancellation.fee.method` | `UPI` | How a cancellation fee is taken. A typo degrades to `UPI` with a warning rather than failing the cancellation. |

Complete a ride and take the fare:

```bash
curl -s -X POST http://localhost:8080/api/v1/rides/42/complete \
  -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"paymentMethod": "UPI"}'
```

```json
{
  "success": true,
  "data": {
    "id": 42, "status": "COMPLETED",
    "fare": { "totalFare": 118.00 },
    "payment": {
      "purpose": "RIDE_FARE", "method": "UPI", "status": "SUCCESS",
      "amount": 118.00, "reference": "UPI-42-1754899532114"
    }
  }
}
```

Then, if it had declined:

```bash
# Same method again, or switch instrument: {"paymentMethod": "CASH"}
curl -s -X POST http://localhost:8080/api/v1/rides/42/payment/retry \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H 'Content-Type: application/json' -d '{}'
```

Retrying an already-settled payment is `409 PAYMENT_ALREADY_SETTLED` rather than a
silent success, so a broken client cannot retry for ever without noticing. A ride
with nothing to retry is `404 PAYMENT_NOT_FOUND`.

No amount, rider id or driver id is ever read from a request body — every figure
comes off the ride row, so no caller can redirect a charge or change its price.

---

## 13. Cross-cutting concerns

### Request tracking

`RequestIdFilter` (highest precedence, runs before the security chain) reads
`X-Request-Id` or generates a UUID, puts it in the SLF4J MDC, echoes it in the
response header, includes it in every API response body, and stores it on every
audit record. Client IP (honouring `X-Forwarded-For`) is in the MDC too.

### Idempotency

`POST /api/v1/rides` accepts `Idempotency-Key`. The guarantee comes from the unique
key `(user_id, idempotency_key)` in `ride_schema.idempotency_keys`, not from an
application-level check:

* first call → `IN_PROGRESS` row, then `COMPLETED` with the stored response body;
* repeat of a completed call → the original response is replayed, **no second ride**;
* repeat while still in flight → `409 REQUEST_ALREADY_IN_PROGRESS`;
* same key with a different request body → `422 IDEMPOTENCY_KEY_REUSED`;
* a failed attempt deletes its row so the client may retry;
* records expire after `idempotency.ttl.seconds` (24 h).

### Rate limiting

`RateLimiter` (interface) → `RedisRateLimiter` (fixed window; `INCR` + `PEXPIRE` in
one Lua script so a window can never exist without a TTL). Applied declaratively:

```java
@RateLimited(RateLimitPolicy.RIDE_CREATE)
```

`RateLimitInterceptor` reads the annotation; **no Redis code in any controller**.
Exceeding a limit returns `429` with a `Retry-After` header.

| Policy | Limit | Keyed by |
|---|---|---|
| `RIDE_CREATE` | 10 / min | user |
| `DRIVER_LOCATION` | 60 / min | driver (1:1 with user) |
| `LOGIN` | 5 / min | client IP |
| `COUPON_VALIDATE` | 20 / min | user |
| `PRICING_QUOTE` | 60 / min | user |
| `ADMIN_API` | 30 / min | admin |

All limits are DB-configurable; the Java constants are only fallbacks used when
configuration cannot be read.

### Audit

`audit_schema.audit_logs` records `entity_type, entity_id, action, old_value,
new_value, changed_by, changed_at, request_id, ip_address`. Writes run in
`REQUIRES_NEW` and swallow failures — an audit problem must never fail or roll back
a completed business operation.

Audited: driver registration, driver `AVAILABLE ↔ BUSY`, ride created, every ride
status transition, coupon created/deactivated/redeemed/reversed, pricing rule
created or changed, configuration changed. Routine reads and technical operations
are deliberately **not** audited — a noisy audit trail is a useless one.

### Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) produces one shape for every
failure:

```json
{
  "success": false,
  "error": { "code": "NO_DRIVER_IN_RADIUS", "message": "No available driver found" },
  "requestId": "0b3f...",
  "timestamp": "2026-08-10T09:15:32.114Z"
}
```

Successful responses use the same envelope with `success: true` and `data`.
`ErrorCode` maps each domain code to its HTTP status, so a status is never chosen
ad hoc at a throw site. Notable codes: `NO_DRIVER_IN_RADIUS` (404),
`INVALID_RIDE_STATE_TRANSITION` (409), `CONCURRENT_MODIFICATION` (409),
`IDEMPOTENCY_KEY_REUSED` (422), `COUPON_NOT_APPLICABLE` (422),
`RATE_LIMIT_EXCEEDED` (429).

---

## 14. API reference

All paths are prefixed `/api/v1`. All responses use the envelope above.
`—` in the Permission column means the endpoint is public.

| Method | Path | Permission | Ownership | Notes |
|---|---|---|---|---|
| POST | `/auth/login` | — | — | rate limited per IP |
| POST | `/users` | — | — | rider signup, role fixed by server |
| GET | `/users/{userId}` | `USER_READ` | self or ADMIN | |
| GET | `/users` | `USER_READ` + ADMIN | — | paginated, optional `role` filter |
| POST | `/drivers` | — | — | driver self-onboarding (creates the account too) |
| GET | `/drivers/{driverId}` | `DRIVER_READ` | self or ADMIN | |
| PUT | `/drivers/{driverId}/location` | `DRIVER_LOCATION_UPDATE` | self or ADMIN | Redis only; rate limited |
| PUT | `/drivers/{driverId}/status` | `DRIVER_STATUS_UPDATE` | self or ADMIN | `OFFLINE ↔ AVAILABLE` only |
| POST | `/drivers/{driverId}/vehicles` | `VEHICLE_CREATE` | self or ADMIN | one active vehicle per driver |
| GET | `/drivers/{driverId}/vehicles` | `VEHICLE_READ` | self or ADMIN | |
| POST | `/rides` | `RIDE_CREATE` | rider = token | `Idempotency-Key` supported; rate limited |
| GET | `/rides/{rideId}` | `RIDE_READ` | rider, assigned driver, or ADMIN | |
| POST | `/rides/{rideId}/start` | `RIDE_START` | assigned driver or ADMIN | |
| POST | `/rides/{rideId}/complete` | `RIDE_COMPLETE` | assigned driver or ADMIN | frees the driver; **body `{"paymentMethod":…}` required**; collects the fare |
| POST | `/rides/{rideId}/cancel` | `RIDE_CANCEL` | rider, assigned driver, or ADMIN | reverses the coupon; collects the fee if it is non-zero |
| GET | `/rides/{rideId}/payment` | `PAYMENT_READ` | rider, assigned driver, or ADMIN | fare and any cancellation fee, oldest first |
| POST | `/rides/{rideId}/payment/retry` | `PAYMENT_COLLECT` | assigned driver or ADMIN | body optional; 409 if already settled |
| GET | `/users/{userId}/rides` | `RIDE_READ` | self or ADMIN | paginated |
| GET | `/drivers/{driverId}/rides` | `RIDE_READ` | self or ADMIN | paginated |
| POST | `/coupons` | `COUPON_CREATE` | ADMIN | |
| DELETE | `/coupons/{id}` | `COUPON_DELETE` | ADMIN | **soft delete** → `INACTIVE` |
| POST | `/coupons/{code}/validate` | `COUPON_VALIDATE` | user from token | rate limited; never throws |
| POST | `/pricing/quote` | `PRICING_READ` | rider = token | fare estimate; books nothing; an unusable coupon comes back as a reason on a 200 |
| GET | `/pricing/rules` | `PRICING_READ` | | |
| POST | `/pricing/rules` | `PRICING_CREATE` | ADMIN | |
| PUT | `/pricing/rules/{id}` | `PRICING_UPDATE` | ADMIN | never affects existing rides |
| GET | `/pricing/zones` | `PRICING_READ` | ADMIN | city → pricing rule mapping |
| POST | `/pricing/zones` | `PRICING_CREATE` | ADMIN | add a city in one call |
| PUT | `/pricing/zones/{id}` | `PRICING_UPDATE` | ADMIN | redrawing never affects existing rides |
| GET | `/configurations` | `CONFIGURATION_READ` | ADMIN | |
| GET | `/configurations/{key}` | `CONFIGURATION_READ` | ADMIN | |
| PUT | `/configurations/{key}` | `CONFIGURATION_UPDATE` | ADMIN | evicts the Redis cache |
| GET | `/admin/audit-logs` | `AUDIT_READ` | ADMIN | filter by entity or `requestId` |

### Postman collection

`postman_collection.json` in the repo root — import it into Postman (**Import →
File**). 46 requests in 9 folders, covering every endpoint plus the failure
cases (403 ownership, 409 invalid transition, 422 idempotency reuse, 429 rate
limit).

* Set the `baseUrl` collection variable if the app is not on port 8080.
* Run **0. Auth** first: each login stores its JWT in `riderToken` /
  `driverToken` / `adminToken` automatically, and every other request already
  references the right one.
* Run **2. Drivers → Update driver location** before booking — see the TTL
  gotcha in §16.
* **Book a ride** stores the new id in `rideId`, so the start/complete/cancel
  requests work with no copy-pasting.

### Example: book a ride

```bash
TOKEN=...   # from /auth/login as rahul@ridehailing.com

curl -s -X POST http://localhost:8080/api/v1/rides \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 9f1c8a2e-0d1b-4f77-8a2c-2f0d4a6b1c33' \
  -H 'X-Request-Id: demo-booking-1' \
  -d '{
        "pickupLatitude": 12.9716, "pickupLongitude": 77.5946, "pickupAddress": "MG Road",
        "dropLatitude": 12.9352,  "dropLongitude": 77.6245,  "dropAddress": "Koramangala",
        "carType": "HATCHBACK",
        "couponCode": "WELCOME10"
      }'
```

Repeating that exact call returns the **same ride**, not a second one.

---

## 15. Rules for future changes

Non-negotiable invariants. Breaking one of these is a defect even if the code
compiles and the tests (when they exist) pass.

1. **Before writing a new class, look for an existing one.** Does this already
   exist? Can it be reused? Does another module already own this concern? Do not
   create duplicate services, repositories, utilities, exceptions, entities or DTOs.
2. **No cross-schema foreign keys.** Cross-module references are plain columns with
   a comment naming the target.
3. **A module's tables are touched only by that module's repositories.** Need data
   from elsewhere? Call the owning module's service.
4. **Entities never leave the service layer.** Request DTO → controller → service →
   repository, and entity → mapper → response DTO.
5. **Controllers stay thin** and stay in `com.ridehailing.controller`. No business
   logic, no transactions, no Redis, no repositories.
6. **Business rules are DB-driven** (`configuration_schema`); infrastructure config
   is not. Use `ConfigKeys` constants.
7. **No money literals in Java.** All pricing inputs come from `pricing_schema`.
   Round only through `common.util.Money`.
8. **Driver reservation stays an atomic conditional UPDATE.** Never a
   read-modify-write. Never a Redis lock as the sole guarantee.
9. **Transactions are short.** Never hold one across Redis, HTTP or any other
   network call. Audit after commit.
10. **Authorisation is permission-based *and* ownership-checked.** Never trust a
    `userId` or `driverId` from the request when the authenticated identity should
    be used.
11. **Flyway owns the schema.** Add a new versioned migration; never edit an applied
    one; never switch `ddl-auto` off `none`.
12. **Audit business changes, not technical noise.**
13. **On MySQL, JPA needs `catalog =`, not `schema =`.**
14. **Do not add** Swagger/OpenAPI, tests (that is a separate later phase), message
    brokers, Elasticsearch, CQRS/event sourcing, GraphQL, or an abstraction that
    does not solve a problem you can name.
15. **Do not rewrite working code for style.** Understand it, preserve behaviour,
    make the smallest clean change, and re-check transactions, concurrency,
    authorisation and audit implications.

---

## 16. Not implemented yet

### Operational gotcha: drivers must be sending location

A driver is only bookable while a **fresh** GPS position exists in Redis.
`driver.location.ttl.seconds` is 60, so the freshness marker written by
`DriverLocationWarmupRunner` at startup expires after one minute, and the next
search prunes that driver from the GEO set. Seeded demo drivers therefore stop
being matchable ~60 s after boot and every booking returns
`NO_DRIVER_IN_RADIUS`.

This is correct behaviour — a driver who stopped reporting their position must
not be dispatched — but it surprises anyone demoing the API. Before booking,
have the driver ping:

```bash
DTOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"raj.kumar@ridehailing.com","password":"Driver@123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

curl -s -X PUT http://localhost:8080/api/v1/drivers/1/location \
  -H "Authorization: Bearer $DTOK" -H 'Content-Type: application/json' \
  -d '{"latitude":12.9750,"longitude":77.5990}'
```

Raising `driver.location.ttl.seconds` through `PUT /configurations/{key}` keeps
demo drivers alive longer, at the cost of dispatching staler positions.

### Gaps

Known gaps, listed so nobody assumes they exist:

* **No unit tests.** JUnit/Mockito/Testcontainers are deliberately excluded;
  unit testing is a separate planned phase. There *is* an end-to-end smoke
  workflow (`scripts/e2e-ride-workflow.sh`, run by CI against real MySQL and
  Redis) but it exercises the API from outside, not individual units.
* **No token refresh or logout.** Access tokens simply expire; there is no refresh
  token and no revocation list.
* **No driver rating submission flow.** `drivers.rating` is seeded and read by the
  matching strategy but nothing writes it yet.
* **No surge computation.** The surge multiplier is stored per pricing rule and
  gated by `surge.enabled`; there is no demand-based engine that adjusts it.
* **No expired-idempotency-record sweeper.** The `idx_idempotency_expires` index is
  in place for one, but no scheduled cleanup job runs.
* **No road-distance ETA.** A straight-line pickup distance and a speed-derived
  pickup ETA now exist (see [How far away the driver is](#how-far-away-the-driver-is)),
  but both are honest approximations, and the trip duration is still not estimated at
  all. A defensible ETA needs a routing provider for real road distance and duration
  plus estimated-versus-actual tracking to know whether the number can be trusted —
  that is a project, not a field. The measurement half is already in place:
  `assigned_at` / `started_at` / `completed_at` record what actually happened, so the
  current estimate can be scored against reality before anyone relies on it.

  The same missing input limits pricing: fares are built on straight-line distance
  and are therefore systematically low. A routing provider would fix the fare and
  supply the ETA in one call, which is why this belongs behind a `DistanceProvider`
  seam at `RideBookingService.validateTrip` — the single place a distance is
  produced — rather than being sprinkled through the pricing engine. Turning on a
  road-distance correction would be a **pricing** change, not a technical one:
  a 1.3 factor raises every fare by 30 %, so it has to ship alongside re-tuned tier
  rates or a deliberate decision to charge more.
* **No live tracking endpoint** — a rider cannot watch the assigned driver approach.
* **No demand-based surge computation.** The surge multiplier is stored per pricing
  rule and gated by `surge.enabled`; nothing adjusts it from live demand or supply.
  It is an administrative lever, not an algorithm.
* **Only one matching strategy is implemented.** The seam is complete — a
  `DriverMatchingStrategy` bean is selected by the `matching.strategy` config row
  and an unknown value degrades to `NEAREST` — but `NEAREST` is the only
  implementation. `HIGHEST_RATED` would be a new `@Component` and nothing else.
* **No driver earnings, payouts or refunds.** Collection exists (see
  [Payments](#payments)) but only inbound: there is no commission split, payout,
  settlement or refund path, and no decision recorded about who funds a coupon
  discount. `SUM(amount)` over `payments` answers "what did riders pay", not "what do
  we owe a driver".
* **The payment partner is mocked.** `MockPaymentPartner` is the only `PaymentPartner`,
  so no money has ever actually moved. The port is the seam a real PSP plugs into, and
  a real one also brings the things this design has no answer for yet: asynchronous
  callbacks (hence no `PENDING` status), refunds, and reconciliation.
* **`ride.cancellation.allowed.statuses` is dead configuration.** The constant
  exists in `ConfigKeys` and the row is seeded, but nothing reads it —
  `RideStateMachine` is the single source of truth for legal transitions. It should
  be deleted rather than wired, to avoid two definitions of the same rule.
* **Client-supplied `X-Forwarded-For` is trusted.** `RequestIdFilter` takes the
  first value blindly, so the IP-keyed login rate limit can be bypassed by
  rotating the header. Fix is to trust the header only from known proxies.
* **Verified end to end on MySQL 9.7 + Redis 7** (Homebrew, not Docker): the
  migrations apply, login/booking/start/complete/cancel, car-type upgrade,
  cancellation fee, idempotent replay, rate limiting (429 + `Retry-After`), audit
  trail and Redis GEO matching all behave as documented. Not yet exercised on
  MySQL 8.0 itself, nor under real concurrent load. **Payments and the pickup
  distance/ETA are the exception: they are not yet exercised against a running
  instance**, so treat the payment walkthrough above as the intended behaviour rather
  than an observed one.

---

## 17. Assumptions

Everywhere the brief was silent, this is what was decided and why.

| Area | Assumption |
|---|---|
| Distance | Straight-line haversine, not road distance. Fares are therefore lower than a real trip. Swapping in a routing provider changes one call in `RideBookingService.validateTrip`, not the pricing engine. |
| Pricing tiers | Slab, not flat-rate: a 6 km trip bills 2 km, 3 km and 1 km at the three tier rates. Each tier line is rounded on its own so the printed breakdown sums exactly to the distance fare. |
| Rounding | Every monetary amount is `HALF_UP` at scale 2, in `Money` and nowhere else. Distance is scale 3. |
| Car-type billing | An upgraded rider is billed at the multiplier of the type they **requested**, not the one they got. |
| Zone selection | The **pickup** decides the pricing zone; the drop is never considered, so the fare cannot change under the rider mid-ride. `quote()` is not even given the drop coordinates. |
| Overlapping zones | Resolved by `priority DESC, radius ASC`, first match wins. Equal priority *and* equal radius overlapping the same point is genuinely ambiguous and is not validated against. |
| Coupon timing | Applied at **booking**, not at `start`. The fare snapshot is frozen when the ride is created, so the discount has to be computed then. |
| Coupon eligibility | Tested against the fare **after** surge and **after** the minimum-fare floor. Enabling surge can therefore make a previously-rejected coupon valid. |
| Coupon deletion | `DELETE /coupons/{id}` deactivates rather than hard-deletes; redemption history must survive. |
| Coupon on cancellation | The redemption is reversed, so the rider keeps the use they never got a ride for. |
| Driver acceptance | There is none. Booking claims a driver directly; a driver can only cancel afterwards. A real offer/accept flow needs a new state between `REQUESTED` and `DRIVER_ASSIGNED` plus a timeout worker. |
| Driver position | Owned by the device and stored only in Redis. A GPS ping never writes to MySQL, because the `drivers` row is also the reservation row and per-ping writes would both create a hot spot and churn the `version` the booking compares against. |
| Stale positions | A driver with no fresh position is not dispatched, even though MySQL still says `AVAILABLE`. Losing pings is acceptable: a position is a re-sent sample, not a fact. |
| Cancellation fee | See section 12 — flat, rider-only, grace-windowed, stored on the ride. |
| Storage | MySQL is used even though the brief allows in-memory, because the concurrency guarantee is a conditional `UPDATE`, which needs a real database to be meaningful. |
| Ride history | Returns every status, so "ongoing and completed" is satisfied without a filter parameter. |

---

## 18. What I would do differently with more time

In the order I would actually do them.

1. **Automated tests.** The largest gap, and the pricing engine is the natural
   place to start: it is a pure function of its inputs, so tiers, the minimum-fare
   floor, car-type multipliers, percentage caps, flat coupons and the free upgrade
   can all be covered without a database. `CancellationFeePolicy` and
   `RideStateMachine` are equally pure. The concurrency guarantee needs a real
   MySQL, so that one belongs in a Testcontainers integration test that fires two
   concurrent bookings at one driver and asserts exactly one wins.
2. **A complete analytics dataset.** This is the one I would push hardest for, and
   it is a product decision more than a technical one: a business cannot scale or
   make decisions without data, and nobody signs off on a change they cannot measure.
   Today the platform records what *happened* to each ride, but nothing that answers
   *why the business is performing the way it is*.

   Two things already work in its favour. Every ride carries an immutable pricing
   snapshot — `pricing_rule_code`, `pricing_zone_code` and the full `fare_breakdown`
   JSON — so historical analysis stays correct even after rules, zones or surge are
   changed. And `audit_logs` is already event-shaped, which makes it a usable source
   for a change-data-capture pipeline rather than something to bolt on later.

   What is missing is the measurement layer:

   * **A funnel, with reasons.** requested → matched → started → completed, against
     cancelled. Right now a `NO_DRIVER_IN_RADIUS` is an error response and nothing
     else; it should be a recorded demand signal, because unmet demand is the single
     most valuable number a marketplace has.
   * **Supply and demand per zone per time bucket.** Requests, available drivers,
     match rate and median pickup distance. This is the same input demand-based
     surge needs, so the analytics work and item 5 are really one project — build the
     measurement first, and surge becomes a consumer of it rather than a guess.
   * **Matching quality.** Candidates considered per booking, how often the first
     choice lost the reservation race, and how often a hatchback request had to be
     upgraded — that last one is a direct read on fleet-mix shortfall.
   * **Unit economics per ride.** Fare, discount borne, cancellation fee, and later
     commission and driver payout, which is why this and the `ride_charges` ledger
     in item 4 belong together.
   * **Driver behaviour.** Utilisation, idle time, cancellation rate, ping
     continuity.

   Two design constraints I would hold to. First, **analytics must not run on the
   OLTP tables** — an unbounded `SUM(total_fare)` over `rides` is a full scan of the
   largest table, and the current indexes only support per-user and per-driver
   lookups by `requested_at`. That means a read replica or warehouse plus scheduled
   rollups, and reporting endpoints with a mandatory date range so nobody can
   accidentally trigger a lifetime scan. Second, **pre-aggregate into fact tables**
   rather than computing on read, for exactly the reason the fare and the
   cancellation fee are snapshotted: a number that changes retrospectively is not a
   number anyone can act on.
3. **Vehicle category as data, not an enum.** Adding a car type today means a Java
   enum change plus a migration for four `CHECK` constraints. With `bike` on any
   roadmap, the catalogue and the substitution graph belong in tables, with the
   `CHECK`s replaced by foreign keys — same integrity, no deploy per category.
4. **A `ride_charges` ledger.** Cancellation fee is the first money line item that
   is not the fare. The second one (waiting charge, toll, tip, commission, payout)
   should arrive as a ledger rather than another column, and driver earnings fall
   out of it.
5. **Demand-based surge.** The multiplier is already threaded through pricing and
   snapshotted per ride, so the missing part is only the input: a rolling count of
   unmatched requests versus available drivers per zone — which item 2 produces.
6. **A second matching strategy**, mostly to prove the seam in review rather than
   because the product needs it.
7. **Trusted-proxy handling** for `X-Forwarded-For`, and a rate limit on the two
   public registration endpoints, which have none today.
8. **Observability.** Structured request logging exists via the request-id filter,
   but there are no metrics — booking success rate, match latency, candidates per
   booking and reservation-race counts are the four I would add first. This is the
   operational twin of item 2: metrics tell you the system is healthy, analytics
   tells you the business is.

---

## 19. How AI was used

> **This section is a scaffold, not a finished answer.** It records what is
> verifiable from the session logs; the earlier phases need filling in by hand
> before this is submitted. Anything left as `TODO` is unowned, and unowned text is
> worse than no text.

**TODO — earlier phases.** The initial domain modelling, schema design, module
layout and the first working booking flow predate the session below. Fill in: what
was prompted for, what came back unusable, and what was rewritten by hand.

**Verifiable from the most recent session:**

* **Reviewed rather than accepted.** Two real defects were found by reading the
  code rather than by running it, and both were then reproduced deliberately: a
  booking that lost its `Idempotency-Key` when Redis was unreachable, poisoning the
  client's own retry for 24 hours; and the recovery snapshot only being refreshed
  when a driver changed status, so warm-up after a Redis restart placed drivers
  wherever they last went online rather than where they actually were.
* **Verification was adversarial, not confirmatory.** The Redis-down behaviour was
  tested by pointing an instance at a dead port rather than by reasoning about it.
  The snapshot fix was proved by pinging a *different* position mid-ride and
  asserting the stored value moved to it, which a passing happy-path test would not
  have caught.
* **Arithmetic was checked independently.** Fares were computed by hand from the
  tier tables and compared against live API responses — `50.00` on a floored short
  trip, `143.13` on a 28 km trip with a percentage coupon, `250.04` under a nested
  airport zone, `94.60` in Delhi. All matched to the paisa.
* **A suggested design was pushed back on and changed.** Setting the driver's
  position from the ride's drop coordinates on completion was rejected as
  fabricating a GPS fix; the implementation copies the driver's real last reported
  position instead, and does nothing when there is none.
* **What was deliberately not built.** A demand-based surge engine, a driver
  accept/reject flow, and an earnings ledger were all scoped and then declined as
  out of scope rather than half-built. Section 16 lists them as gaps instead.

---
