//go:build windows

package platform

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// Job is a Windows job object that kills every process in it when the handle is closed, so a
// crashed or force-closed panel can never leave sing-box (and its routes) running.
type Job struct{ h windows.Handle }

// NewKillOnCloseJob creates the job object.
func NewKillOnCloseJob() (*Job, error) {
	h, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, err
	}
	var info windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(h, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		windows.CloseHandle(h)
		return nil, err
	}
	return &Job{h: h}, nil
}

// Assign puts the process with the given pid into the job.
func (j *Job) Assign(pid int) error {
	ph, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		return err
	}
	defer windows.CloseHandle(ph)
	return windows.AssignProcessToJobObject(j.h, ph)
}

// Close closes the job handle (which kills the processes still inside).
func (j *Job) Close() error {
	if j == nil || j.h == 0 {
		return nil
	}
	err := windows.CloseHandle(j.h)
	j.h = 0
	return err
}
