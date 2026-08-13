package rbac

import (
	"database/sql"
	"sync"
)

// Users reference a role by code, not id, so the join starts from roles.code.
const permissionsByRoleCodeSQL = `
SELECT p.code
FROM rbac_schema.roles r
JOIN rbac_schema.role_permissions rp ON rp.role_id = r.id
JOIN rbac_schema.permissions p ON p.id = rp.permission_id
WHERE r.code = ?
ORDER BY p.code`

// Role definitions are deployment-time master data, so an in-process map is the right cache, as in RolePermissionService.
type Store struct {
	db    *sql.DB
	cache sync.Map
}

func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

// Cached hits never touch MySQL; a miss caches the empty set too, matching computeIfAbsent.
func (s *Store) PermissionsOf(roleCode string) (map[string]bool, error) {
	if cached, ok := s.cache.Load(roleCode); ok {
		return cached.(map[string]bool), nil
	}
	rows, err := s.db.Query(permissionsByRoleCodeSQL, roleCode)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	permissions := map[string]bool{}
	for rows.Next() {
		var code string
		if err := rows.Scan(&code); err != nil {
			return nil, err
		}
		permissions[code] = true
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	actual, _ := s.cache.LoadOrStore(roleCode, permissions)
	return actual.(map[string]bool), nil
}

// Called after a role definition changes.
func (s *Store) Evict(roleCode string) {
	s.cache.Delete(roleCode)
}
