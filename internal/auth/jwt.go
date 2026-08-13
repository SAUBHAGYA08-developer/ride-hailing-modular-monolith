package auth

import (
	"errors"
	"strconv"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// The claim set the Java JwtService issues, so a token from either service is accepted by both.
type Principal struct {
	UserID      int64
	Email       string
	Role        string
	Permissions map[string]bool
}

func (p Principal) IsAdmin() bool  { return p.Role == "ADMIN" }
func (p Principal) IsDriver() bool { return p.Role == "DRIVER" }
func (p Principal) Has(permission string) bool {
	return p.Permissions[permission]
}

type Service struct {
	secret  []byte
	issuer  string
	expiry  time.Duration
}

func NewService(secret, issuer string, expirySeconds int) (*Service, error) {
	if len(secret) < 32 {
		return nil, errors.New("JWT_SECRET must be at least 32 bytes long")
	}
	return &Service{[]byte(secret), issuer, time.Duration(expirySeconds) * time.Second}, nil
}

func (s *Service) Expiry() time.Duration { return s.expiry }

func (s *Service) Issue(p Principal) (string, error) {
	perms := make([]string, 0, len(p.Permissions))
	for k := range p.Permissions {
		perms = append(perms, k)
	}
	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"iss":         s.issuer,
		"sub":         strconv.FormatInt(p.UserID, 10),
		"email":       p.Email,
		"role":        p.Role,
		"permissions": perms,
		"iat":         now.Unix(),
		"exp":         now.Add(s.expiry).Unix(),
	})
	return token.SignedString(s.secret)
}

func (s *Service) Parse(raw string) (Principal, error) {
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(raw, &claims, func(t *jwt.Token) (interface{}, error) {
		return s.secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer(s.issuer))
	if err != nil {
		return Principal{}, err
	}
	sub, _ := claims["sub"].(string)
	id, err := strconv.ParseInt(sub, 10, 64)
	if err != nil {
		return Principal{}, errors.New("token subject is not a user id")
	}
	perms := map[string]bool{}
	if raw, ok := claims["permissions"].([]interface{}); ok {
		for _, v := range raw {
			if s, ok := v.(string); ok {
				perms[s] = true
			}
		}
	}
	email, _ := claims["email"].(string)
	role, _ := claims["role"].(string)
	return Principal{UserID: id, Email: email, Role: role, Permissions: perms}, nil
}
