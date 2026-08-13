-- ---------------------------------------------------------------------------
-- V1 : Module schemas.
-- Each module owns exactly one schema. No cross-schema foreign keys are ever
-- created, so any schema can be lifted into its own database later.
-- In MySQL a SCHEMA is a DATABASE; the names below are the module boundaries.
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS rbac_schema          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS user_schema          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS driver_schema        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS ride_schema          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS pricing_schema       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS coupon_schema        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS audit_schema         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS configuration_schema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
