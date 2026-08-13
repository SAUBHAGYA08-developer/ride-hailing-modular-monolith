package httpx

import (
	"encoding/json"
	"net/http"
	"time"
)

// Byte for byte the Java envelope, so the existing web pages work against either service unchanged.
type Envelope struct {
	Success   bool        `json:"success"`
	Data      interface{} `json:"data,omitempty"`
	Error     *APIError   `json:"error,omitempty"`
	RequestID string      `json:"requestId,omitempty"`
	Timestamp time.Time   `json:"timestamp"`
}

type APIError struct {
	Code    string            `json:"code"`
	Message string            `json:"message"`
	Details map[string]string `json:"details,omitempty"`
}

// Same codes and statuses as ErrorCode, because clients already branch on them.
type Coded struct {
	Code   string
	Status int
	Msg    string
}

func (e Coded) Error() string { return e.Msg }

func Err(code string, status int, msg string) Coded { return Coded{code, status, msg} }

var (
	ErrUnauthenticated = func() Coded { return Err("UNAUTHENTICATED", 401, "Authentication is required") }
	ErrAccessDenied    = func() Coded { return Err("ACCESS_DENIED", 403, "You are not allowed to perform this operation") }
	ErrValidation      = func(m string) Coded { return Err("VALIDATION_ERROR", 400, m) }
	ErrMalformed       = func() Coded { return Err("MALFORMED_REQUEST", 400, "Request body or parameter could not be parsed") }
	ErrNoDriver        = func(m string) Coded { return Err("NO_DRIVER_IN_RADIUS", 404, m) }
	ErrRideNotFound    = func(m string) Coded { return Err("RIDE_NOT_FOUND", 404, m) }
	ErrDriverNotFound  = func(m string) Coded { return Err("DRIVER_NOT_FOUND", 404, m) }
	ErrInvalidTrip     = func(m string) Coded { return Err("INVALID_TRIP", 400, m) }
	ErrConcurrent      = func() Coded { return Err("CONCURRENT_MODIFICATION", 409, "The resource was modified concurrently, please retry") }
	ErrInternal        = func() Coded { return Err("INTERNAL_ERROR", 500, "Unexpected server error") }
)

func OK(w http.ResponseWriter, r *http.Request, data interface{}) {
	write(w, 200, Envelope{Success: true, Data: data, RequestID: RequestIDOf(r), Timestamp: time.Now().UTC()})
}

func Created(w http.ResponseWriter, r *http.Request, data interface{}) {
	write(w, 201, Envelope{Success: true, Data: data, RequestID: RequestIDOf(r), Timestamp: time.Now().UTC()})
}

func NoContent(w http.ResponseWriter) { w.WriteHeader(204) }

func Fail(w http.ResponseWriter, r *http.Request, err error) {
	c, ok := err.(Coded)
	if !ok {
		c = ErrInternal()
	}
	write(w, c.Status, Envelope{Success: false, RequestID: RequestIDOf(r), Timestamp: time.Now().UTC(),
		Error: &APIError{Code: c.Code, Message: c.Msg}})
}

func Decode(r *http.Request, into interface{}) error {
	if err := json.NewDecoder(r.Body).Decode(into); err != nil {
		return ErrMalformed()
	}
	return nil
}

func write(w http.ResponseWriter, status int, body Envelope) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
