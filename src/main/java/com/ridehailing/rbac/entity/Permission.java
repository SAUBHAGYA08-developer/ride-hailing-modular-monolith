package com.ridehailing.rbac.entity;

import com.ridehailing.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "permissions", catalog = "rbac_schema")
@Getter
@Setter
public class Permission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "resource", nullable = false, length = 40)
    private String resource;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "description", length = 255)
    private String description;
}
