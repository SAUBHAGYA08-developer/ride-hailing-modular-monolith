package store

import (
	"database/sql"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

// Pool sized from config for the same reason as Hikari: a small container shares few connections.
func OpenMySQL(dsn string, poolSize int) (*sql.DB, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(poolSize)
	db.SetMaxIdleConns(poolSize)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.Ping(); err != nil {
		return nil, err
	}
	return db, nil
}
