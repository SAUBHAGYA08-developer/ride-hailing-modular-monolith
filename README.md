# Ride Hailing Backend

A **modular monolith** ride-hailing platform: one Spring Boot application, eight
independently-owned MySQL schemas, Redis for live driver geography and hot-path
coordination.

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

# 2. Application (Flyway migrates all eight schemas on startup)
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
ride ──▶ user          (existence check)
pricing ──▶ coupon     (apply discount inside the quote)
driver ──▶ user        (a driver profile is backed by a user account)
security ──▶ user, rbac
every module ──▶ common, configuration, audit, redis, ratelimit
```

There are **no cycles**. `coupon`, `configuration`, `audit`, `rbac` and `user`
depend on no other business module. If you need a new edge, question it first.

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

Eight schemas, one per module. **No foreign key ever crosses a schema boundary.**
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
| `pricing_schema` | `pricing_rules` | unique `code` |
| | `pricing_distance_tiers` | unique `(pricing_rule_id, from_km)`, CHECK `to_km > from_km` |
| | `pricing_car_type_multipliers` | unique `(pricing_rule_id, car_type)` |
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
| `V1__create_schemas.sql` | the eight schemas |
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
| coupons | `COUPON_CREATE`, `COUPON_READ`, `COUPON_DELETE`, `COUPON_VALIDATE` |
| pricing | `PRICING_READ`, `PRICING_CREATE`, `PRICING_UPDATE` |
| configuration | `CONFIGURATION_READ`, `CONFIGURATION_UPDATE` |
| audit | `AUDIT_READ` |

Role → permission mapping:

* **ADMIN** — every permission.
* **USER** — `USER_READ`, `RIDE_CREATE`, `RIDE_READ`, `RIDE_CANCEL`, `COUPON_READ`, `COUPON_VALIDATE`, `PRICING_READ`.
* **DRIVER** — `DRIVER_READ`, `DRIVER_UPDATE`, `DRIVER_LOCATION_UPDATE`, `DRIVER_STATUS_UPDATE`, `VEHICLE_CREATE`, `VEHICLE_READ`, `VEHICLE_UPDATE`, `RIDE_READ`, `RIDE_START`, `RIDE_COMPLETE`, `RIDE_CANCEL`.

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
  `BUSY → AVAILABLE` and `total_rides` increments atomically.
* **cancel** — rider, assigned driver, or ADMIN; releases the driver back to
  `AVAILABLE` and reverses the coupon redemption so the rider gets the use back.
  `cancelled_by` is derived from the authenticated role, never from the request body.

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
| POST | `/rides/{rideId}/complete` | `RIDE_COMPLETE` | assigned driver or ADMIN | frees the driver |
| POST | `/rides/{rideId}/cancel` | `RIDE_CANCEL` | rider, assigned driver, or ADMIN | reverses the coupon |
| GET | `/users/{userId}/rides` | `RIDE_READ` | self or ADMIN | paginated |
| GET | `/drivers/{driverId}/rides` | `RIDE_READ` | self or ADMIN | paginated |
| POST | `/coupons` | `COUPON_CREATE` | ADMIN | |
| DELETE | `/coupons/{id}` | `COUPON_DELETE` | ADMIN | **soft delete** → `INACTIVE` |
| POST | `/coupons/{code}/validate` | `COUPON_VALIDATE` | user from token | rate limited; never throws |
| GET | `/pricing/rules` | `PRICING_READ` | | |
| POST | `/pricing/rules` | `PRICING_CREATE` | ADMIN | |
| PUT | `/pricing/rules/{id}` | `PRICING_UPDATE` | ADMIN | never affects existing rides |
| GET | `/configurations` | `CONFIGURATION_READ` | ADMIN | |
| GET | `/configurations/{key}` | `CONFIGURATION_READ` | ADMIN | |
| PUT | `/configurations/{key}` | `CONFIGURATION_UPDATE` | ADMIN | evicts the Redis cache |
| GET | `/admin/audit-logs` | `AUDIT_READ` | ADMIN | filter by entity or `requestId` |

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

* **No tests.** JUnit/Mockito/Testcontainers are deliberately excluded for now;
  testing is a separate planned phase.
* **No token refresh or logout.** Access tokens simply expire; there is no refresh
  token and no revocation list.
* **No driver rating submission flow.** `drivers.rating` is seeded and read by the
  matching strategy but nothing writes it yet.
* **No surge computation.** The surge multiplier is stored per pricing rule and
  gated by `surge.enabled`; there is no demand-based engine that adjusts it.
* **No expired-idempotency-record sweeper.** The `idx_idempotency_expires` index is
  in place for one, but no scheduled cleanup job runs.
* **No rider-facing driver ETA / live tracking endpoint.**
* **Verified end to end on MySQL 9.7 + Redis 7** (Homebrew, not Docker): all 14
  migrations apply, login/booking/start/complete/cancel, car-type upgrade,
  idempotent replay, rate limiting (429 + `Retry-After`), audit trail and Redis
  GEO matching all behave as documented. Not yet exercised on MySQL 8.0 itself,
  nor under real concurrent load.
