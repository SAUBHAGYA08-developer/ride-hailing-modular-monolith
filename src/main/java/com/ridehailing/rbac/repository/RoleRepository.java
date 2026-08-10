package com.ridehailing.rbac.repository;

import com.ridehailing.rbac.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    @Query("select p.code from Role r join r.permissions p where r.code = :roleCode order by p.code")
    List<String> findPermissionCodesByRoleCode(@Param("roleCode") String roleCode);
}
