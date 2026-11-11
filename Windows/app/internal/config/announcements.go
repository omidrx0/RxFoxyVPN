package config

import (
	"encoding/json"
	"os"
	"strings"
	"sync"
)

const maxAcknowledgedAnnouncements = 500

type announcementsDiskRecord struct {
	AcknowledgedIDs []string `json:"acknowledged_ids"`
}

// AnnouncementStore tracks announcement IDs that the user explicitly acknowledged
// via the "متوجه شدم" button.
type AnnouncementStore struct {
	path string
	mu   sync.RWMutex
}

// NewAnnouncementStore creates an announcement store backed by path.
func NewAnnouncementStore(path string) *AnnouncementStore {
	return &AnnouncementStore{path: path}
}

func (s *AnnouncementStore) loadListUnlocked() []string {
	raw, err := os.ReadFile(s.path)
	if err != nil {
		return nil
	}
	var rec announcementsDiskRecord
	if json.Unmarshal(raw, &rec) != nil {
		return nil
	}
	out := make([]string, 0, len(rec.AcknowledgedIDs))
	seen := make(map[string]struct{}, len(rec.AcknowledgedIDs))
	for _, id := range rec.AcknowledgedIDs {
		trimmed := strings.TrimSpace(id)
		if trimmed == "" {
			continue
		}
		if _, ok := seen[trimmed]; ok {
			continue
		}
		seen[trimmed] = struct{}{}
		out = append(out, trimmed)
	}
	return out
}

// AcknowledgedSet returns the set of acknowledged announcement IDs.
func (s *AnnouncementStore) AcknowledgedSet() map[string]bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	ids := s.loadListUnlocked()
	out := make(map[string]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out
}

// Acknowledge persists id as read. Blank IDs are ignored.
func (s *AnnouncementStore) Acknowledge(id string) error {
	trimmed := strings.TrimSpace(id)
	if trimmed == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	ids := s.loadListUnlocked()
	for _, existing := range ids {
		if existing == trimmed {
			return nil
		}
	}
	ids = append(ids, trimmed)
	if len(ids) > maxAcknowledgedAnnouncements {
		ids = ids[len(ids)-maxAcknowledgedAnnouncements:]
	}
	data, err := MarshalJSON(announcementsDiskRecord{AcknowledgedIDs: ids}, true)
	if err != nil {
		return err
	}
	return WriteFileAtomic(s.path, data, 0o600)
}
