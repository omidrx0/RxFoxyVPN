//go:build !windows

package platform

import "errors"

// Job is a stub outside Windows.
type Job struct{}

func NewKillOnCloseJob() (*Job, error) { return nil, errors.New("job objects are only available on Windows") }
func (*Job) Assign(int) error          { return errors.New("job objects are only available on Windows") }
func (*Job) Close() error              { return nil }
