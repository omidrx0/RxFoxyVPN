// Package apierr defines the error type the HTTP layer turns into a JSON error reply.
package apierr

// Error is an error carrying the HTTP status the panel should receive and an optional
// set of extra JSON fields (for example {"need_login": true}).
type Error struct {
	Message string
	Status  int
	Extra   map[string]any
}

func (e *Error) Error() string { return e.Message }

// New builds an Error. The status defaults to 400 like the original ApiError.
func New(message string, status ...int) *Error {
	s := 400
	if len(status) > 0 {
		s = status[0]
	}
	return &Error{Message: message, Status: s}
}

// WithExtra returns the same error with an extra JSON field attached.
func (e *Error) WithExtra(key string, value any) *Error {
	if e.Extra == nil {
		e.Extra = map[string]any{}
	}
	e.Extra[key] = value
	return e
}
