package user

import (
	"net/http"
	"strings"

	"ridehailing/internal/httpx"
)

type Handler struct {
	svc *Service
}

func NewHandler(svc *Service) *Handler {
	return &Handler{svc: svc}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/auth/login", h.login)
}

func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var request LoginRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := validateLogin(request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	response, err := h.svc.Login(request)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, response)
}

// Stands in for @NotBlank @Email, down to the message the bean validation handler produces.
func validateLogin(request LoginRequest) error {
	if strings.TrimSpace(request.Email) == "" || strings.TrimSpace(request.Password) == "" {
		return httpx.ErrValidation("Request validation failed")
	}
	if !looksLikeEmail(request.Email) {
		return httpx.ErrValidation("Request validation failed")
	}
	return nil
}

// Deliberately as lax as Hibernate's @Email: one @, something either side, no whitespace.
func looksLikeEmail(email string) bool {
	address := strings.TrimSpace(email)
	if strings.ContainsAny(address, " \t") {
		return false
	}
	local, domain, found := strings.Cut(address, "@")
	return found && local != "" && domain != "" && !strings.Contains(domain, "@")
}
