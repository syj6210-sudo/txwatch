package com.txwatch.domain;

import java.time.Instant;

import com.txwatch.domain.Enums.RiskLevel;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String externalRef;
    private String name;
    private String countryCode;
    @Enumerated(EnumType.STRING)
    private RiskLevel occupationRisk;
    private boolean pep;
    private String nationalIdToken;
    private String phone;
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(String externalRef, String name, String countryCode, RiskLevel occupationRisk,
            boolean pep, String nationalIdToken, String phone, Instant createdAt) {
        this.externalRef = externalRef;
        this.name = name;
        this.countryCode = countryCode;
        this.occupationRisk = occupationRisk;
        this.pep = pep;
        this.nationalIdToken = nationalIdToken;
        this.phone = phone;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getName() {
        return name;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public RiskLevel getOccupationRisk() {
        return occupationRisk;
    }

    public boolean isPep() {
        return pep;
    }

    public String getNationalIdToken() {
        return nationalIdToken;
    }

    public String getPhone() {
        return phone;
    }
}
