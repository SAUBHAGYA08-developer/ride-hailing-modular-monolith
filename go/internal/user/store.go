package user

import (
	"database/sql"
	"errors"
	"strings"
)

const (
	StatusActive    = "ACTIVE"
	StatusSuspended = "SUSPENDED"
)

const credentialsByEmailSQL = `
SELECT id, email, password_hash, full_name, phone, role, status
FROM user_schema.users
WHERE email = ?`

// Everything authentication needs and nothing else, as UserCredentials.
type Credentials struct {
	UserID       int64
	Email        string
	PasswordHash string
	FullName     string
	Phone        string
	Role         string
	Status       string
}

// Nil without an error when no such account exists, mirroring Optional.empty().
func findCredentialsByEmail(db *sql.DB, email string) (*Credentials, error) {
	var c Credentials
	err := db.QueryRow(credentialsByEmailSQL, normalizeEmail(email)).
		Scan(&c.UserID, &c.Email, &c.PasswordHash, &c.FullName, &c.Phone, &c.Role, &c.Status)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// Accounts are stored lowercased by register(), so lookups must lowercase too.
func normalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}
