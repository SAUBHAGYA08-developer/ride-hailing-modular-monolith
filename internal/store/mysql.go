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
	// Idle equals open so a warm connection is always ready: re-handshaking TLS to a managed MySQL costs more
	// than holding the socket, and an idle Go connection costs almost nothing.
	db.SetMaxIdleConns(poolSize)
	db.SetConnMaxIdleTime(0)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.Ping(); err != nil {
		return nil, err
	}
	return db, nil
}
