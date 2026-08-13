package user

import (
	"database/sql"
	"log/slog"
	"sort"

	"golang.org/x/crypto/bcrypt"
	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
	"ridehailing/internal/rbac"
)

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type LoginResponse struct {
	AccessToken      string   `json:"accessToken"`
	TokenType        string   `json:"tokenType"`
	ExpiresInSeconds int64    `json:"expiresInSeconds"`
	UserID           int64    `json:"userId"`
	Email            string   `json:"email"`
	Role             string   `json:"role"`
	Permissions      []string `json:"permissions"`
}

var (
	errInvalidCredentials = func() httpx.Coded { return httpx.Err("INVALID_CREDENTIALS", 401, "Invalid email or password") }
	errAccountSuspended   = func() httpx.Coded { return httpx.Err("ACCOUNT_SUSPENDED", 403, "This account is suspended") }
)

type Service struct {
	db    *sql.DB
	perms *rbac.Store
	jwt   *auth.Service
}

func NewService(db *sql.DB, perms *rbac.Store, jwt *auth.Service) *Service {
	return &Service{db: db, perms: perms, jwt: jwt}
}

func (s *Service) Login(request LoginRequest) (LoginResponse, error) {
	credentials, err := findCredentialsByEmail(s.db, request.Email)
	if err != nil {
		slog.Error("credential lookup failed", "err", err)
		return LoginResponse{}, httpx.ErrInternal()
	}

	// Same error for unknown email and wrong password, so the endpoint does not enumerate accounts.
	if credentials == nil || bcrypt.CompareHashAndPassword([]byte(credentials.PasswordHash), []byte(request.Password)) != nil {
		return LoginResponse{}, errInvalidCredentials()
	}
	if credentials.Status != StatusActive {
		return LoginResponse{}, errAccountSuspended()
	}

	permissions, err := s.perms.PermissionsOf(credentials.Role)
	if err != nil {
		slog.Error("permission lookup failed", "role", credentials.Role, "err", err)
		return LoginResponse{}, httpx.ErrInternal()
	}

	principal := auth.Principal{UserID: credentials.UserID, Email: credentials.Email,
		Role: credentials.Role, Permissions: permissions}
	token, err := s.jwt.Issue(principal)
	if err != nil {
		slog.Error("token issue failed", "err", err)
		return LoginResponse{}, httpx.ErrInternal()
	}

	return LoginResponse{
		AccessToken:      token,
		TokenType:        "Bearer",
		ExpiresInSeconds: int64(s.jwt.Expiry().Seconds()),
		UserID:           principal.UserID,
		Email:            principal.Email,
		Role:             principal.Role,
		Permissions:      sortedCodes(permissions),
	}, nil
}

// The Java query orders by permission code, so a sorted array keeps responses diffable.
func sortedCodes(permissions map[string]bool) []string {
	codes := make([]string, 0, len(permissions))
	for code := range permissions {
		codes = append(codes, code)
	}
	sort.Strings(codes)
	return codes
}
