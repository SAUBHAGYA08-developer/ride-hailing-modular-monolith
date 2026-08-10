package com.ridehailing.rbac.service;

import com.ridehailing.rbac.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the permission set of a role.
 *
 * Role definitions are master data that changes at deployment time, so an
 * in-process map is the right cache here; Redis would add a hop for nothing.
 */
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private final RoleRepository roleRepository;
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Set<String> permissionsOf(String roleCode) {
        return cache.computeIfAbsent(roleCode,
                code -> Set.copyOf(new LinkedHashSet<>(roleRepository.findPermissionCodesByRoleCode(code))));
    }

    /** Called after a role definition changes. */
    public void evict(String roleCode) {
        cache.remove(roleCode);
    }
}
